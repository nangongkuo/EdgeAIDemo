package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentDefinition
import com.zjf.edgeai.agent.api.AgentEngine
import com.zjf.edgeai.agent.api.AgentEngineEvent
import com.zjf.edgeai.agent.api.AgentEngineRequest
import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentId
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.ArtifactId
import com.zjf.edgeai.agent.api.ArtifactRef
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.StepId
import com.zjf.edgeai.agent.api.ToolCall
import com.zjf.edgeai.agent.api.ToolCallId
import com.zjf.edgeai.agent.api.ToolCallState
import com.zjf.edgeai.agent.api.ToolResult
import com.zjf.edgeai.agent.api.UserContinuation
import com.zjf.edgeai.agent.api.WorkerId
import com.zjf.edgeai.agent.api.WorkerRun
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RunSchedulerRecoveryTest {
    @Test
    fun startupReplaysSafeRunningRun() = runTest {
        val store = runningStore()
        val engine = RecordingEngine { flow { emit(AgentEngineEvent.Completed("恢复完成")) } }
        val scheduler = scheduler(store, engine)
        try {
            advanceUntilIdle()

            assertEquals(1, engine.executions)
            assertEquals(RunState.COMPLETED, store.snapshot(RUN_ID).state)
            assertTrue(store.events(RUN_ID).any {
                it is AgentEvent.RunStateChanged && it.current == RunState.RECOVERING
            })
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun startupSuspendsUnknownNonIdempotentWrite() = runTest {
        val store = runningStore()
        store.append(RUN_ID) { sequence, timestamp ->
            AgentEvent.ToolLifecycle(
                RUN_ID,
                sequence,
                timestamp,
                ToolCall(
                    id = ToolCallId("write-call"),
                    runId = RUN_ID,
                    stepId = StepId("write-step"),
                    capabilityId = CapabilityId("remote.write"),
                    argumentsJson = "{\"value\":1}",
                    idempotent = false,
                ),
                ToolCallState.RUNNING,
            )
        }
        val engine = RecordingEngine { flow { emit(AgentEngineEvent.Completed("不应执行")) } }
        val scheduler = scheduler(store, engine)
        try {
            advanceUntilIdle()

            assertEquals(0, engine.executions)
            assertEquals(RunState.WAITING_USER_INPUT, store.snapshot(RUN_ID).state)
            assertTrue(store.events(RUN_ID).filterIsInstance<AgentEvent.UserInputRequired>()
                .single().requestId.startsWith("recovery:"))
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun duplicateCompletionProducesOneTerminalEvent() = runTest {
        val store = InMemoryRunStore { 10L }
        val engine = RecordingEngine {
            flow {
                emit(AgentEngineEvent.Completed("第一次"))
                emit(AgentEngineEvent.Completed("重复回调"))
            }
        }
        val scheduler = scheduler(store, engine)
        try {
            store.create(RUN_ID, AgentRequest("任务", SessionId("session")), 1)
            store.append(RUN_ID) { sequence, timestamp ->
                AgentEvent.RunStateChanged(RUN_ID, sequence, timestamp, RunState.CREATED, RunState.QUEUED)
            }
            scheduler.enqueue(RUN_ID)
            advanceUntilIdle()

            val terminal = store.events(RUN_ID).filterIsInstance<AgentEvent.Terminal>()
            assertEquals(1, terminal.size)
            assertEquals("第一次", terminal.single().output)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun parentCancellationMarksActiveWorkersCancelled() = runTest {
        val store = InMemoryRunStore { 10L }
        val worker = WorkerRun(
            workerId = WorkerId("worker"),
            parentRunId = RUN_ID,
            agentId = AgentId("worker-agent"),
            objective = "独立任务",
            input = "任务",
            capabilityAllowlist = emptySet(),
            budget = com.zjf.edgeai.agent.api.RunBudget(),
            depth = 1,
        )
        val engine = RecordingEngine {
            flow {
                emit(AgentEngineEvent.WorkerStarted(worker))
                awaitCancellation()
            }
        }
        val scheduler = scheduler(store, engine)
        try {
            store.create(RUN_ID, AgentRequest("任务"), 1)
            store.append(RUN_ID) { sequence, timestamp ->
                AgentEvent.RunStateChanged(RUN_ID, sequence, timestamp, RunState.CREATED, RunState.QUEUED)
            }
            scheduler.enqueue(RUN_ID)
            runCurrent()
            assertEquals(RunState.RUNNING, store.snapshot(RUN_ID).workers.single().state)

            scheduler.cancel(RUN_ID)
            runCurrent()

            assertTrue(engine.cancelled)
            assertEquals(RunState.CANCELLED, store.snapshot(RUN_ID).state)
            assertEquals(RunState.CANCELLED, store.snapshot(RUN_ID).workers.single().state)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun staleContinuationIsRejectedWithoutStateChange() = runTest {
        val store = InMemoryRunStore { 10L }
        store.create(RUN_ID, AgentRequest("任务"), 1)
        store.append(RUN_ID) { sequence, timestamp ->
            AgentEvent.Terminal(RUN_ID, sequence, timestamp, RunState.COMPLETED, "完成")
        }
        val scheduler = scheduler(store, RecordingEngine { flow { } })
        try {
            val failure = runCatching {
                scheduler.continueRun(
                    RUN_ID,
                    UserContinuation.Input("missing", "value"),
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(RunState.COMPLETED, store.snapshot(RUN_ID).state)
            assertFalse(store.events(RUN_ID).any { it is AgentEvent.UserInputRequired })
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun schedulerCloseReleasesAgentEngine() = runTest {
        val store = InMemoryRunStore { 10L }
        val engine = RecordingEngine { flow { } }
        val scheduler = scheduler(store, engine)

        scheduler.close()

        assertTrue(engine.closed)
    }

    @Test
    fun toolArtifactsAreProjectedIntoRunSnapshot() = runTest {
        val store = InMemoryRunStore { 10L }
        val call = ToolCall(
            ToolCallId("artifact-call"),
            RUN_ID,
            StepId("artifact-step"),
            capabilityId = CapabilityId("local.large_result"),
            argumentsJson = "{}",
        )
        val artifact = ArtifactRef(
            id = ArtifactId("artifact"),
            runId = RUN_ID,
            stepId = call.stepId,
            name = "result.json",
            mimeType = "application/json",
            sizeBytes = 20_000,
            sha256 = "sha256",
            createdAtEpochMillis = 1,
        )
        val engine = RecordingEngine {
            flow {
                emit(
                    AgentEngineEvent.ToolCompleted(
                        call,
                        ToolResult(call.id, true, "{\"truncated\":true}", artifactRefs = listOf(artifact)),
                        ToolCallState.SUCCEEDED,
                    )
                )
                emit(AgentEngineEvent.Completed("完成"))
            }
        }
        val scheduler = scheduler(store, engine)
        try {
            store.create(RUN_ID, AgentRequest("任务"), 1)
            store.append(RUN_ID) { sequence, timestamp ->
                AgentEvent.RunStateChanged(RUN_ID, sequence, timestamp, RunState.CREATED, RunState.QUEUED)
            }
            scheduler.enqueue(RUN_ID)
            advanceUntilIdle()

            assertEquals(listOf(artifact), store.snapshot(RUN_ID).artifacts)
            assertEquals(1, store.events(RUN_ID).filterIsInstance<AgentEvent.ArtifactCommitted>().size)
        } finally {
            scheduler.close()
        }
    }

    private suspend fun runningStore(): InMemoryRunStore {
        val store = InMemoryRunStore { 10L }
        store.create(RUN_ID, AgentRequest("恢复任务"), 1)
        store.append(RUN_ID) { sequence, timestamp ->
            AgentEvent.RunStateChanged(RUN_ID, sequence, timestamp, RunState.CREATED, RunState.QUEUED)
        }
        store.append(RUN_ID) { sequence, timestamp ->
            AgentEvent.RunStateChanged(RUN_ID, sequence, timestamp, RunState.QUEUED, RunState.RUNNING)
        }
        return store
    }

    private fun kotlinx.coroutines.test.TestScope.scheduler(
        store: InMemoryRunStore,
        engine: RecordingEngine,
    ) = RunScheduler(
        store = store,
        engine = engine,
        rootAgent = ROOT_AGENT,
        workerAgents = emptyList(),
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private class RecordingEngine(
        private val source: (AgentEngineRequest) -> Flow<AgentEngineEvent>,
    ) : AgentEngine {
        var executions = 0
        var cancelled = false
        var closed = false
        override fun execute(request: AgentEngineRequest): Flow<AgentEngineEvent> {
            executions++
            return source(request)
        }
        override suspend fun continueRun(runId: RunId, continuation: UserContinuation) = Unit
        override suspend fun cancel(runId: RunId) { cancelled = true }
        override fun close() { closed = true }
    }

    private companion object {
        val RUN_ID = RunId("run")
        val ROOT_AGENT = AgentDefinition(
            id = AgentId("root"),
            name = "root",
            description = "root",
            instructions = "root",
        )
    }
}
