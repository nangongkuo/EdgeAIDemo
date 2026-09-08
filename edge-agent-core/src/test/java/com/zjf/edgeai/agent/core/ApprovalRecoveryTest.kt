package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentDefinition
import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentId
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.agent.api.ApprovalId
import com.zjf.edgeai.agent.api.ApprovalRequest
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.ModelCapabilities
import com.zjf.edgeai.agent.api.ModelId
import com.zjf.edgeai.agent.api.ModelProvider
import com.zjf.edgeai.agent.api.ModelRequest
import com.zjf.edgeai.agent.api.ModelResponse
import com.zjf.edgeai.agent.api.ModelToolCall
import com.zjf.edgeai.agent.api.RiskLevel
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.StepId
import com.zjf.edgeai.agent.api.ToolCallId
import com.zjf.edgeai.agent.api.ToolDescriptor
import com.zjf.edgeai.agent.api.ToolExecutor
import com.zjf.edgeai.agent.api.ToolProvider
import com.zjf.edgeai.agent.api.ToolResult
import com.zjf.edgeai.agent.api.UserContinuation
import com.zjf.edgeai.agent.capabilities.ToolRegistry
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ApprovalRecoveryTest {
    @Test
    fun persistedHighRiskApprovalExecutesOriginalCallExactlyOnce() = runTest {
        val store = InMemoryRunStore { 100L }
        val request = AgentRequest("执行高风险写入")
        store.create(RUN_ID, request, 1)
        store.append(RUN_ID) { sequence, timestamp ->
            AgentEvent.RunStateChanged(RUN_ID, sequence, timestamp, RunState.CREATED, RunState.RUNNING)
        }
        val approval = ApprovalRequest(
            id = APPROVAL_ID,
            runId = RUN_ID,
            toolCallId = TOOL_CALL_ID,
            capabilityId = CAPABILITY,
            riskLevel = RiskLevel.HIGH,
            reason = "写入需要确认",
            argumentsJson = "{\"value\":\"original\"}",
            createdAtEpochMillis = 50,
        )
        store.append(RUN_ID) { sequence, timestamp ->
            AgentEvent.ApprovalLifecycle(RUN_ID, sequence, timestamp, approval)
        }
        store.append(RUN_ID) { sequence, timestamp ->
            AgentEvent.RunStateChanged(
                RUN_ID,
                sequence,
                timestamp,
                RunState.RUNNING,
                RunState.WAITING_APPROVAL,
            )
        }

        var executions = 0
        var executedArguments: String? = null
        val toolProvider = object : ToolProvider {
            override suspend fun listTools() = listOf(
                ToolDescriptor(
                    CAPABILITY,
                    "write",
                    "写入",
                    "{\"type\":\"object\",\"required\":[\"value\"]}",
                    riskLevel = RiskLevel.HIGH,
                    idempotent = false,
                )
            )
            override fun executor(capabilityId: CapabilityId) = ToolExecutor { call ->
                executions++
                executedArguments = call.argumentsJson
                ToolResult(call.id, true, "{\"ok\":true}")
            }
        }
        val model = object : ModelProvider {
            override val id = "local"
            override val models = mapOf(MODEL_ID to ModelCapabilities(toolCalling = true, local = true))
            override fun generate(request: ModelRequest) = flow {
                if (request.messages.any { it.toolResults.isNotEmpty() }) {
                    emit(ModelResponse.Completed("写入完成"))
                } else {
                    emit(
                        ModelResponse.ToolCalls(
                            listOf(ModelToolCall("write", "{\"value\":\"original\"}"))
                        )
                    )
                }
            }
            override fun close() = Unit
        }
        val engine = ProviderAgentEngine(
            listOf(model),
            toolRuntime = ToolRegistry(listOf(toolProvider)),
        )
        val scheduler = RunScheduler(
            store = store,
            engine = engine,
            rootAgent = ROOT,
            workerAgents = emptyList(),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            advanceUntilIdle()
            assertEquals(RunState.WAITING_APPROVAL, store.snapshot(RUN_ID).state)

            scheduler.continueRun(
                RUN_ID,
                UserContinuation.Approval(APPROVAL_ID, ApprovalDecision.APPROVE_ONCE),
            )
            advanceUntilIdle()

            assertEquals(1, executions)
            assertEquals("{\"value\":\"original\"}", executedArguments)
            assertEquals(
                "events=${store.events(RUN_ID)}",
                RunState.COMPLETED,
                store.snapshot(RUN_ID).state,
            )
            assertTrue(store.snapshot(RUN_ID).pendingApprovals.isEmpty())
            assertEquals(
                1,
                store.events(RUN_ID).filterIsInstance<AgentEvent.ToolLifecycle>()
                    .count { it.state == com.zjf.edgeai.agent.api.ToolCallState.RUNNING },
            )
        } finally {
            scheduler.close()
        }
    }

    private companion object {
        val RUN_ID = RunId("run")
        val MODEL_ID = ModelId("model")
        val CAPABILITY = CapabilityId("local.write")
        val TOOL_CALL_ID = ToolCallId("${RUN_ID.value}:tool:1:0:${CAPABILITY.value}")
        val APPROVAL_ID = ApprovalId("approval:${TOOL_CALL_ID.value}")
        val ROOT = AgentDefinition(
            id = AgentId("root"),
            name = "root",
            description = "root",
            instructions = "root",
            preferredModelId = MODEL_ID,
            capabilityAllowlist = setOf(CAPABILITY),
        )
    }
}
