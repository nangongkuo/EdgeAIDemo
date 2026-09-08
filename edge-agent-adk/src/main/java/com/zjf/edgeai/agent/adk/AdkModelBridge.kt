package com.zjf.edgeai.agent.adk

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.zjf.edgeai.agent.api.ModelMessage
import com.zjf.edgeai.agent.api.ModelProvider
import com.zjf.edgeai.agent.api.ModelRequest
import com.zjf.edgeai.agent.api.ModelResponse
import com.zjf.edgeai.agent.api.ModelSelection
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.core.ModelExecutionBroker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class AdkModelBridge(
    override val name: String,
    private val runId: RunId,
    private val selection: ModelSelection,
    private val provider: ModelProvider,
    private val broker: ModelExecutionBroker,
) : Model {
    override fun generateContent(llmRequest: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
        val messages = llmRequest.contents.map { content ->
            ModelMessage(
                role = content.role ?: "user",
                text = content.parts.joinToString("") { it.text.orEmpty() },
            )
        }
        broker.generate(
            provider,
            ModelRequest(
                runId = runId,
                modelId = selection.modelId,
                systemInstruction = "",
                messages = messages,
                maxOutputTokens = llmRequest.config.maxOutputTokens,
            ),
        ).collect { response ->
            when (response) {
                ModelResponse.Reset -> emit(
                    LlmResponse.builder()
                        .content(Content.fromText("model", ""))
                        .partial(true)
                        .customMetadata(mapOf("edgeAgentReset" to true))
                        .build()
                )
                is ModelResponse.Delta -> emit(
                    LlmResponse.builder()
                        .content(Content.fromText("model", response.text))
                        .partial(true)
                        .build()
                )
                is ModelResponse.Completed -> emit(
                    LlmResponse.builder()
                        .content(Content.fromText("model", response.text))
                        .partial(false)
                        .build()
                )
                is ModelResponse.ToolCalls -> error("ADK 快速路径未注册工具，不能接收 Tool Call")
            }
        }
    }
}
