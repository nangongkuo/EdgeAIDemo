package com.zjf.edgeai.agent.cloud.openai

import com.zjf.edgeai.agent.api.ModelCapabilities
import com.zjf.edgeai.agent.api.ModelId
import com.zjf.edgeai.agent.api.ModelMessage
import com.zjf.edgeai.agent.api.ModelRequest
import com.zjf.edgeai.agent.api.ModelToolCall
import com.zjf.edgeai.agent.api.ModelToolResult
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.SecretStore
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleModelProviderTest {
    @Test
    fun toolCallAndResultKeepProviderCallId() {
        val modelId = ModelId("cloud-model")
        val provider = OpenAiCompatibleModelProvider(
            OpenAiCompatibleConfig(
                providerId = "cloud",
                baseUrl = "https://example.com/v1",
                apiKeySecretId = "api-key",
                modelCapabilities = mapOf(modelId to ModelCapabilities(toolCalling = true)),
            ),
            InMemorySecretStore(),
        )
        val request = ModelRequest(
            runId = RunId("run"),
            modelId = modelId,
            systemInstruction = "system",
            messages = listOf(
                ModelMessage("user", "查询天气"),
                ModelMessage(
                    role = "assistant",
                    toolCalls = listOf(ModelToolCall("weather", "{\"city\":\"上海\"}", "call-42")),
                ),
                ModelMessage(
                    role = "tool",
                    toolResults = listOf(
                        ModelToolResult(
                            "weather",
                            "{\"temperature\":28}",
                            untrusted = true,
                            toolCallId = "call-42",
                        )
                    ),
                ),
            ),
        )

        val messages = provider.buildRequest(request, modelId)["messages"]!!.jsonArray
        val assistant = messages[2].jsonObject
        val tool = messages[3].jsonObject

        assertEquals("call-42", assistant["tool_calls"]!!.jsonArray.single()
            .jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("call-42", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertTrue(tool["content"]!!.jsonPrimitive.content.startsWith("[UNTRUSTED_EXTERNAL_DATA"))
        provider.close()
    }

    private class InMemorySecretStore : SecretStore {
        private val values = mutableMapOf("api-key" to "secret")
        override fun put(id: String, value: String) { values[id] = value }
        override fun get(id: String): String? = values[id]
        override fun delete(id: String): Boolean = values.remove(id) != null
    }
}
