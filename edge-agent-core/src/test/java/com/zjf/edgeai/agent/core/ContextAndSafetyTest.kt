package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentDefinition
import com.zjf.edgeai.agent.api.AgentEngineRequest
import com.zjf.edgeai.agent.api.AgentId
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.Guardrail
import com.zjf.edgeai.agent.api.GuardrailDecision
import com.zjf.edgeai.agent.api.GuardrailStage
import com.zjf.edgeai.agent.api.MemoryId
import com.zjf.edgeai.agent.api.MemoryRecord
import com.zjf.edgeai.agent.api.MemorySensitivity
import com.zjf.edgeai.agent.api.ModelCapabilities
import com.zjf.edgeai.agent.api.ModelId
import com.zjf.edgeai.agent.api.ModelMessage
import com.zjf.edgeai.agent.api.ModelPolicy
import com.zjf.edgeai.agent.api.ModelProvider
import com.zjf.edgeai.agent.api.ModelRequest
import com.zjf.edgeai.agent.api.ModelResponse
import com.zjf.edgeai.agent.api.ModelToolResult
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.TraceRecord
import com.zjf.edgeai.agent.api.TraceSink
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAndSafetyTest {
    @Test
    fun `上下文按固定优先级装配且保留结构化工具结果`() {
        val context = ContextAssembler(estimateTokens = String::length).assemble(
            ContextInput(
                securityInstruction = "安全",
                agentInstruction = "指令",
                objective = "目标",
                memories = listOf(memory("高优记忆", 0.9), memory("低优记忆", 0.1)),
                branchMessages = listOf(
                    ModelMessage(
                        role = "tool",
                        toolResults = listOf(
                            ModelToolResult("web", "{\"value\":1}", untrusted = true)
                        ),
                    )
                ),
                toolSummaries = listOf("较低优先级工具摘要"),
                compactedHistory = "最低优先级历史",
            ),
            maxTokens = 40,
        )

        assertTrue(context.systemInstruction.startsWith("安全\n\n指令"))
        assertEquals("高优记忆", context.messages.first().text)
        val structured = context.messages.firstOrNull { it.toolResults.isNotEmpty() }
        assertEquals(true, structured?.toolResults?.single()?.untrusted)
        assertTrue(context.omittedItems > 0)
        assertTrue(context.estimatedTokens <= 40)
    }

    @Test
    fun `云端出站统一脱敏并拒绝本地专用内容`() = runTest {
        val pipeline = GuardrailPipeline(emptyList())
        val redacted = pipeline.evaluate(
            RunId("run"),
            GuardrailStage.CLOUD_EGRESS,
            "authorization: Bearer abcdefgh api_key=super-secret",
            com.zjf.edgeai.agent.api.TrustLevel.TRUSTED_LOCAL,
            PrivacyLevel.PRIVATE,
        )
        assertFalse(redacted.contains("abcdefgh"))
        assertFalse(redacted.contains("super-secret"))
        assertThrows(GuardrailRejectedException::class.java) {
            runBlocking {
                pipeline.evaluate(
                    RunId("run"),
                    GuardrailStage.CLOUD_EGRESS,
                    "不可外发",
                    com.zjf.edgeai.agent.api.TrustLevel.TRUSTED_LOCAL,
                    PrivacyLevel.LOCAL_ONLY,
                )
            }
        }
    }

    @Test
    fun `云端模型只能收到脱敏后的上下文`() = runTest {
        var captured: ModelRequest? = null
        val cloud = object : ModelProvider {
            override val id = "cloud"
            override val models = mapOf(MODEL_ID to ModelCapabilities(local = false))
            override fun generate(request: ModelRequest) = flow {
                captured = request
                emit(ModelResponse.Completed("完成"))
            }
            override fun close() = Unit
        }
        val stages = CopyOnWriteArrayList<GuardrailStage>()
        val guardrail = Guardrail { input ->
            stages += input.stage
            GuardrailDecision(true, input.content)
        }
        ProviderAgentEngine(listOf(cloud), guardrails = listOf(guardrail)).use { engine ->
            engine.execute(
                AgentEngineRequest.Single(
                    RunId("cloud-run"),
                    AgentRequest(
                        input = "请处理 api_key=top-secret-value",
                        modelPolicy = ModelPolicy.CLOUD_ONLY,
                        privacyLevel = PrivacyLevel.PRIVATE,
                    ),
                    ROOT.copy(modelPolicy = ModelPolicy.CLOUD_ONLY),
                )
            ).toList()
        }

        assertFalse(captured!!.messages.joinToString { it.text }.contains("top-secret-value"))
        assertTrue(GuardrailStage.CLOUD_EGRESS in stages)
        assertTrue(GuardrailStage.MODEL_OUTPUT in stages)
    }

    @Test
    fun `Trace 默认不记录正文且事件仍可完整回放`() = runTest {
        val delegate = InMemoryRunStore { 10L }
        val traces = mutableListOf<TraceRecord>()
        val store = TracingRunStore(delegate, listOf(TraceSink { traces += it }))
        val runId = RunId("trace-run")
        val secretText = "authorization: Bearer should-never-appear"
        store.create(runId, AgentRequest(secretText, SessionId("trace-session")), 1)
        store.append(runId) { sequence, timestamp ->
            com.zjf.edgeai.agent.api.AgentEvent.ModelToken(
                runId,
                sequence,
                timestamp,
                secretText,
            )
        }
        store.append(runId) { sequence, timestamp ->
            com.zjf.edgeai.agent.api.AgentEvent.Terminal(
                runId,
                sequence,
                timestamp,
                RunState.COMPLETED,
                output = secretText,
            )
        }

        assertEquals(2, traces.size)
        assertTrue(traces.all { trace -> secretText !in trace.toString() })
        assertEquals(secretText, store.events(runId).filterIsInstance<com.zjf.edgeai.agent.api.AgentEvent.Terminal>().single().output)
    }

    private fun memory(content: String, importance: Double) = MemoryRecord(
        id = MemoryId(content),
        content = content,
        sourceRunIds = setOf(RunId("source-$content")),
        confidence = 1.0,
        importance = importance,
        sensitivity = MemorySensitivity.PRIVATE,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private companion object {
        val MODEL_ID = ModelId("cloud-model")
        val ROOT = AgentDefinition(
            id = AgentId("root"),
            name = "root",
            description = "root",
            instructions = "不得泄露隐私",
            preferredModelId = MODEL_ID,
        )
    }
}
