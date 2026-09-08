package com.zjf.edgeai.agent.storage.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.ApprovalId
import com.zjf.edgeai.agent.api.ApprovalRequest
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.ExecutionPlan
import com.zjf.edgeai.agent.api.PlanNode
import com.zjf.edgeai.agent.api.PlanNodeType
import com.zjf.edgeai.agent.api.RiskLevel
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.StepId
import com.zjf.edgeai.agent.api.ToolCall
import com.zjf.edgeai.agent.api.ToolCallId
import com.zjf.edgeai.agent.api.ToolCallState
import com.zjf.edgeai.agent.api.ToolResult
import com.zjf.edgeai.agent.api.WorkerId
import com.zjf.edgeai.agent.api.WorkerSnapshot
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomRunStoreTest {
    private lateinit var database: AgentDatabase
    private lateinit var store: RoomRunStore
    private val clock = AtomicLong(1_000)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomRunStore(database, now = clock::incrementAndGet)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `事件与完整恢复投影在同一数据库中按序落盘`() = runBlocking {
        val runId = RunId("run-room")
        val sessionId = SessionId("session-room")
        store.create(runId, AgentRequest("执行任务", sessionId), 1_000)

        val plan = ExecutionPlan(
            nodes = listOf(
                PlanNode("tool-step", PlanNodeType.TOOL, "读取数据", capabilityId = CapabilityId("local.read"))
            ),
            finalNodeId = "tool-step",
        )
        store.append(runId) { sequence, timestamp ->
            AgentEvent.PlanCreated(runId, sequence, timestamp, plan)
        }
        val worker = WorkerSnapshot(
            workerId = WorkerId("worker-1"),
            agentId = com.zjf.edgeai.agent.api.AgentId("researcher"),
            objective = "读取数据",
            state = RunState.RUNNING,
            depth = 1,
        )
        store.append(runId) { sequence, timestamp ->
            AgentEvent.WorkerStateChanged(runId, sequence, timestamp, worker)
        }
        val call = ToolCall(
            id = ToolCallId("call-1"),
            runId = runId,
            stepId = StepId("step-1"),
            workerId = worker.workerId,
            capabilityId = CapabilityId("local.read"),
            argumentsJson = "{}",
            idempotent = true,
        )
        store.append(runId) { sequence, timestamp ->
            AgentEvent.ToolLifecycle(runId, sequence, timestamp, call, ToolCallState.RUNNING)
        }
        store.append(runId) { sequence, timestamp ->
            AgentEvent.ToolLifecycle(
                runId,
                sequence,
                timestamp,
                call,
                ToolCallState.SUCCEEDED,
                ToolResult(call.id, true, "{\"value\":1}"),
            )
        }
        val approval = ApprovalRequest(
            id = ApprovalId("approval-1"),
            runId = runId,
            toolCallId = call.id,
            capabilityId = call.capabilityId,
            riskLevel = RiskLevel.HIGH,
            reason = "写入前确认",
            argumentsJson = call.argumentsJson,
            createdAtEpochMillis = 1_000,
        )
        store.append(runId) { sequence, timestamp ->
            AgentEvent.ApprovalLifecycle(runId, sequence, timestamp, approval)
        }

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), store.events(runId).map { it.sequence })
        assertEquals(1, database.executionGraphDao().agentNodes(runId.value).size)
        assertEquals(RunState.RUNNING.name, database.executionGraphDao().workerRuns(runId.value).single().state)
        assertEquals(ToolCallState.SUCCEEDED.name, database.executionGraphDao().steps(runId.value).single().state)
        assertEquals(ToolCallState.SUCCEEDED.name, database.recoveryDao().toolCalls(runId.value).single().state)
        assertTrue(database.recoveryDao().checkpoints(runId.value).single().safeToReplay)
        assertEquals("PENDING", database.recoveryDao().approvals(runId.value).single().state)
        assertEquals(approval.id, store.snapshot(runId).pendingApprovals.single().approvalId)
    }

    @Test
    fun `迟订阅者只收到一次完整回放和后续增量`() = runBlocking {
        val runId = RunId("run-replay")
        store.create(runId, AgentRequest("测试", SessionId("session-replay")), 1_000)
        store.append(runId) { sequence, timestamp ->
            AgentEvent.RunStateChanged(runId, sequence, timestamp, RunState.CREATED, RunState.QUEUED)
        }
        store.append(runId) { sequence, timestamp ->
            AgentEvent.RunStateChanged(runId, sequence, timestamp, RunState.QUEUED, RunState.RUNNING)
        }

        val observed = async { withTimeout(5_000) { store.observe(runId).take(3).toList() } }
        store.append(runId) { sequence, timestamp ->
            AgentEvent.Terminal(runId, sequence, timestamp, RunState.COMPLETED, output = "完成")
        }

        val events = observed.await()
        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
        assertEquals(1, events.count { it is AgentEvent.Terminal })
        assertTrue(store.snapshot(runId).state.terminal)
        assertFalse(store.recoverableRuns().contains(runId))
    }
}
