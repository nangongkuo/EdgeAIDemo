package com.zjf.edgeai.agent.adk

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.LoopAgent
import com.google.adk.kt.agents.ParallelAgent
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.zjf.edgeai.agent.api.AgentDefinition
import com.zjf.edgeai.agent.api.AgentEngineEvent
import com.zjf.edgeai.agent.api.AgentEngineRequest
import com.zjf.edgeai.agent.api.AgentId
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.DelegationMode
import com.zjf.edgeai.agent.api.HandoffState
import com.zjf.edgeai.agent.api.ModelCapabilities
import com.zjf.edgeai.agent.api.ModelId
import com.zjf.edgeai.agent.api.ModelProvider
import com.zjf.edgeai.agent.api.ModelRequest
import com.zjf.edgeai.agent.api.ModelResponse
import com.zjf.edgeai.agent.api.RunId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdkAgentEngineContractTest {
    @Test
    fun fixedVersionFastPathMapsStreamingAndTerminalEvents() = runTest {
        val provider = FakeProvider {
            listOf(
                ModelResponse.Reset,
                ModelResponse.Delta("流式"),
                ModelResponse.Completed("最终答复"),
            )
        }
        val engine = AdkAgentEngine(listOf(provider))

        val events = engine.execute(
            AgentEngineRequest.Single(RUN_ID, AgentRequest("你好"), ROOT)
        ).toList()

        assertEquals("0.8.0", AdkCompatibility.BASELINE_VERSION)
        assertTrue(AdkCompatibility.llmAgentClassName.endsWith("LlmAgent"))
        assertTrue(events.any { it is AgentEngineEvent.Reset })
        assertTrue(
            "ADK 事件：$events",
            events.any { it is AgentEngineEvent.Token && it.text.contains("流式") },
        )
        assertEquals("最终答复", events.filterIsInstance<AgentEngineEvent.Completed>().single().output)
        assertEquals("你好", provider.requests.single().messages.last().text)
    }

    @Test
    fun handoffContractRemainsProjectOwnedBehindAdkAdapter() = runTest {
        val target = ROOT.copy(id = AgentId("expert"), name = "expert")
        val supervisor = ROOT.copy(
            delegationMode = DelegationMode.HANDOFF,
            childAgents = listOf(target.id),
        )
        val engine = AdkAgentEngine(listOf(FakeProvider { listOf(ModelResponse.Completed("x")) }))

        val events = engine.execute(
            AgentEngineRequest.Supervisor(
                RUN_ID,
                AgentRequest(
                    input = "后台任务",
                    requestedHandoffAgentId = target.id,
                    interactive = false,
                ),
                supervisor,
                listOf(target),
            )
        ).toList()

        assertEquals(HandoffState.REJECTED, events.filterIsInstance<AgentEngineEvent.Handoff>().last().state)
        assertEquals("HANDOFF_REJECTED", events.filterIsInstance<AgentEngineEvent.Failed>().single().failure.code)
    }

    @Test
    fun workflowAndAgentToolBuildersMatchPinnedAdkSurface() {
        val model = object : Model {
            override val name = "contract-model"
            override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
                flowOf(
                    LlmResponse.builder()
                        .content(Content.fromText("model", "ok"))
                        .partial(false)
                        .build()
                )
        }
        fun agent(name: String) = LlmAgent.builder().name(name).model(model).build()
        val sequential: SequentialAgent = AdkTopologyFactory.sequential("sequence", listOf(agent("s")))
        val parallel: ParallelAgent = AdkTopologyFactory.parallel(
            "parallel", listOf(agent("p1"), agent("p2"))
        )
        val loop: LoopAgent = AdkTopologyFactory.loop("loop", listOf(agent("l")), 2)
        assertEquals("sequence", sequential.name)
        assertEquals("parallel", parallel.name)
        assertEquals("loop", loop.name)
        assertEquals("tool_target", AdkTopologyFactory.asTool(agent("tool_target")).name)
    }

    private class FakeProvider(
        private val responses: (ModelRequest) -> List<ModelResponse>,
    ) : ModelProvider {
        val requests = mutableListOf<ModelRequest>()
        override val id = "local"
        override val models = mapOf(MODEL_ID to ModelCapabilities(local = true))
        override fun generate(request: ModelRequest): Flow<ModelResponse> = flow {
            requests += request
            responses(request).forEach { emit(it) }
        }
        override fun close() = Unit
    }

    private companion object {
        val RUN_ID = RunId("run")
        val MODEL_ID = ModelId("model")
        val ROOT = AgentDefinition(
            id = AgentId("root"),
            name = "root",
            description = "root",
            instructions = "root",
            preferredModelId = MODEL_ID,
        )
    }
}
