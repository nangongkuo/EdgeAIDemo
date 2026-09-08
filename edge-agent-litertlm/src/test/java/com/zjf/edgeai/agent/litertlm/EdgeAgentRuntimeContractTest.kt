package com.zjf.edgeai.agent.litertlm

import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.EdgeAgentClient
import com.zjf.edgeai.agent.api.RunHandle
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunSnapshot
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.StepId
import com.zjf.edgeai.agent.api.UserContinuation
import com.zjf.edgeai.runtime.GenerationEvent
import com.zjf.edgeai.runtime.RuntimeBackend
import com.zjf.edgeai.runtime.RuntimeDiagnostics
import com.zjf.edgeai.runtime.RuntimeState
import com.zjf.edgeai.runtime.model.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class EdgeAgentRuntimeContractTest {
    @Test
    fun existingCallerReceivesCompatibleGenerationEvents() = runTest {
        val client = FakeClient(
            flowOf(
                AgentEvent.ModelToken(RUN_ID, 1, 1, "旧"),
                AgentEvent.ModelReset(RUN_ID, 2, 2),
                AgentEvent.ModelToken(RUN_ID, 3, 3, "新回答"),
                AgentEvent.Terminal(RUN_ID, 4, 4, RunState.COMPLETED, "新回答"),
            )
        )
        val controller = FakeController(client)
        val runtime = EdgeAgentRuntime(controller)

        runtime.initialize(MODEL, RuntimeBackend.CPU)
        val events = runtime.generate("兼容请求").toList()

        assertEquals("兼容请求", client.lastRequest?.input)
        assertEquals(com.zjf.edgeai.agent.api.RoutingHint.SINGLE_AGENT, client.lastRequest?.routingHint)
        assertEquals(com.zjf.edgeai.agent.api.ModelPolicy.LOCAL_ONLY, client.lastRequest?.modelPolicy)
        assertEquals(
            listOf(
                GenerationEvent.Delta("旧"),
                GenerationEvent.Reset,
                GenerationEvent.Delta("新回答"),
            ),
            events.dropLast(1),
        )
        assertTrue(events.last() is GenerationEvent.Completed)
        assertEquals(MODEL, controller.initialized)
    }

    @Test
    fun blankPromptFailsWithoutSubmitting() = runTest {
        val client = FakeClient(flowOf())
        val events = EdgeAgentRuntime(FakeController(client)).generate("  ").toList()
        assertTrue(events.single() is GenerationEvent.Failed)
        assertEquals(null, client.lastRequest)
    }

    private class FakeController(private val fakeClient: EdgeAgentClient) : AgentModelController {
        override val state: StateFlow<RuntimeState> = MutableStateFlow(
            RuntimeState.Ready(RuntimeBackend.GPU, RuntimeBackend.GPU)
        )
        override val diagnostics: StateFlow<RuntimeDiagnostics> = MutableStateFlow(DIAGNOSTICS)
        override val client: StateFlow<EdgeAgentClient?> = MutableStateFlow(fakeClient)
        var initialized: ModelDescriptor? = null

        override suspend fun initialize(model: ModelDescriptor, preferred: RuntimeBackend) {
            initialized = model
        }

        override suspend fun unload() = Unit
        override fun close() = Unit
    }

    private class FakeClient(private val source: Flow<AgentEvent>) : EdgeAgentClient {
        var lastRequest: AgentRequest? = null
        override suspend fun submit(request: AgentRequest): RunHandle {
            lastRequest = request
            return RunHandle(RUN_ID, source)
        }
        override fun observe(runId: RunId) = source
        override suspend fun snapshot(runId: RunId) = RunSnapshot(
            runId = runId,
            sessionId = lastRequest?.sessionId ?: SessionId("session"),
            state = RunState.COMPLETED,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )
        override suspend fun continueRun(runId: RunId, continuation: UserContinuation) = Unit
        override suspend fun cancel(runId: RunId) = Unit
        override suspend fun retry(runId: RunId, fromStepId: StepId?) = Unit
        override fun close() = Unit
    }

    private companion object {
        val RUN_ID = RunId("compat-run")
        val MODEL = ModelDescriptor("gemma", "/private/gemma", 10, "sha", 1)
        val DIAGNOSTICS = RuntimeDiagnostics(
            deviceManufacturer = "test",
            deviceModel = "test",
            androidApi = 31,
            supportedAbis = listOf("arm64-v8a"),
        )
    }
}
