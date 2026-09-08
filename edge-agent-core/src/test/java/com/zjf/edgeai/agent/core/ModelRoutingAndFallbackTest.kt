package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentDefinition
import com.zjf.edgeai.agent.api.AgentEngineEvent
import com.zjf.edgeai.agent.api.AgentEngineRequest
import com.zjf.edgeai.agent.api.AgentId
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.ModelCapabilities
import com.zjf.edgeai.agent.api.ModelId
import com.zjf.edgeai.agent.api.ModelPolicy
import com.zjf.edgeai.agent.api.ModelProvider
import com.zjf.edgeai.agent.api.ModelRequest
import com.zjf.edgeai.agent.api.ModelResponse
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.RunId
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRoutingAndFallbackTest {
    @Test
    fun `首选模型不可用时选择同策略下的健康模型`() = runTest {
        val unavailable = FakeProvider(
            "preferred",
            mapOf(ModelId("function-gemma") to ModelCapabilities(local = true)),
            healthy = false,
        ) { listOf(ModelResponse.Completed("不可到达")) }
        val available = FakeProvider(
            "local",
            mapOf(ModelId("gemma3") to ModelCapabilities(local = true)),
        ) { listOf(ModelResponse.Completed("ok")) }

        val selection = ModelRouter(listOf(unavailable, available)).select(
            ModelPolicy.PREFER_LOCAL,
            PrivacyLevel.PRIVATE,
            preferredModel = ModelId("function-gemma"),
        )

        assertEquals("local", selection.providerId)
        assertEquals(ModelId("gemma3"), selection.modelId)
    }

    @Test
    fun `本地流式生成失败后清屏并回退云端一次`() = runTest {
        val local = FakeProvider(
            "local",
            mapOf(LOCAL_MODEL to ModelCapabilities(local = true)),
        ) {
            listOf(ModelResponse.Delta("不完整"), IOException("本地推理失败"))
        }
        val cloud = FakeProvider(
            "cloud",
            mapOf(CLOUD_MODEL to ModelCapabilities(local = false)),
        ) { listOf(ModelResponse.Completed("云端完成")) }
        val events = ProviderAgentEngine(listOf(local, cloud)).execute(
            AgentEngineRequest.Single(
                RUN_ID,
                AgentRequest("任务", modelPolicy = ModelPolicy.PREFER_LOCAL),
                ROOT.copy(modelPolicy = ModelPolicy.PREFER_LOCAL, preferredModelId = LOCAL_MODEL),
            )
        ).toList()

        assertEquals(1, local.requests.size)
        assertEquals(1, cloud.requests.size)
        assertTrue(events.any { it is AgentEngineEvent.Reset })
        assertEquals("云端完成", events.filterIsInstance<AgentEngineEvent.Completed>().single().output)
    }

    @Test
    fun `本地专用任务失败时绝不调用云端`() = runTest {
        val local = FakeProvider(
            "local",
            mapOf(LOCAL_MODEL to ModelCapabilities(local = true)),
        ) { listOf(IOException("离线失败")) }
        val cloud = FakeProvider(
            "cloud",
            mapOf(CLOUD_MODEL to ModelCapabilities(local = false)),
        ) { listOf(ModelResponse.Completed("不应执行")) }

        val events = ProviderAgentEngine(listOf(local, cloud)).execute(
            AgentEngineRequest.Single(
                RUN_ID,
                AgentRequest(
                    "隐私任务",
                    modelPolicy = ModelPolicy.LOCAL_ONLY,
                    privacyLevel = PrivacyLevel.LOCAL_ONLY,
                ),
                ROOT.copy(modelPolicy = ModelPolicy.PREFER_CLOUD, preferredModelId = LOCAL_MODEL),
            )
        ).toList()

        assertEquals(1, local.requests.size)
        assertTrue(cloud.requests.isEmpty())
        assertTrue(events.last() is AgentEngineEvent.Failed)
    }

    @Test
    fun `同一端侧Provider可按Agent偏好路由双模型`() = runTest {
        val provider = FakeProvider(
            "litertlm",
            linkedMapOf(
                ModelId("function-gemma") to ModelCapabilities(toolCalling = true, local = true),
                ModelId("gemma3") to ModelCapabilities(local = true),
            ),
        ) { request -> listOf(ModelResponse.Completed(request.modelId!!.value)) }
        val router = ModelRouter(listOf(provider))

        val planner = router.select(
            ModelPolicy.LOCAL_ONLY,
            PrivacyLevel.LOCAL_ONLY,
            preferredModel = ModelId("function-gemma"),
            required = { it.toolCalling },
        )
        val writer = router.select(
            ModelPolicy.LOCAL_ONLY,
            PrivacyLevel.LOCAL_ONLY,
            preferredModel = ModelId("gemma3"),
        )

        assertEquals(ModelId("function-gemma"), planner.modelId)
        assertEquals(ModelId("gemma3"), writer.modelId)
        assertFalse(planner.fallback)
    }

    private class FakeProvider(
        override val id: String,
        override val models: Map<ModelId, ModelCapabilities>,
        private val healthy: Boolean = true,
        private val responses: (ModelRequest) -> List<Any>,
    ) : ModelProvider {
        val requests = mutableListOf<ModelRequest>()
        override suspend fun health() = healthy
        override fun generate(request: ModelRequest): Flow<ModelResponse> = flow {
            requests += request
            responses(request).forEach { response ->
                when (response) {
                    is Throwable -> throw response
                    is ModelResponse -> emit(response)
                    else -> error("非法测试脚本事件")
                }
            }
        }
        override fun close() = Unit
    }

    private companion object {
        val RUN_ID = RunId("fallback-run")
        val LOCAL_MODEL = ModelId("local-model")
        val CLOUD_MODEL = ModelId("cloud-model")
        val ROOT = AgentDefinition(
            AgentId("root"),
            "root",
            "root",
            "root",
            preferredModelId = LOCAL_MODEL,
        )
    }
}
