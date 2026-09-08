package com.zjf.edgeai.agent.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelCapabilities(
    val toolCalling: Boolean = false,
    val structuredOutput: Boolean = false,
    val vision: Boolean = false,
    val audio: Boolean = false,
    val maxContextTokens: Int = 4_096,
    val local: Boolean = true,
)

@Serializable
data class ModelMessage(
    val role: String,
    val text: String = "",
    val toolCalls: List<ModelToolCall> = emptyList(),
    val toolResults: List<ModelToolResult> = emptyList(),
)

@Serializable
data class ModelToolCall(
    val name: String,
    val argumentsJson: String,
    /** Provider 原生调用 ID；没有原生 ID 时由 Agent 层补成稳定 ID。 */
    val id: String? = null,
)

@Serializable
data class ModelToolResult(
    val name: String,
    val responseJson: String,
    /** true 表示内容来自网页、MCP 或远程 Agent，只能作为数据，不能作为指令。 */
    val untrusted: Boolean = false,
    /** 对应 Provider 原生 Tool Call ID，用于云端多轮工具协议闭环。 */
    val toolCallId: String? = null,
)

@Serializable
data class ModelRequest(
    val runId: RunId,
    val modelId: ModelId? = null,
    val systemInstruction: String,
    val messages: List<ModelMessage>,
    val tools: List<ToolDescriptor> = emptyList(),
    val maxOutputTokens: Int? = null,
    val privacyLevel: PrivacyLevel = PrivacyLevel.PRIVATE,
    /** 仅供语义质量和字面保留策略使用，绝不包含 system、历史或工具 Schema。 */
    val originalUserInput: String? = null,
)

@Serializable
sealed interface ModelResponse {
    @Serializable
    @SerialName("reset")
    data object Reset : ModelResponse

    @Serializable
    @SerialName("delta")
    data class Delta(val text: String, val thinking: String? = null) : ModelResponse

    @Serializable
    @SerialName("tool_calls")
    data class ToolCalls(val calls: List<ModelToolCall>) : ModelResponse

    @Serializable
    @SerialName("completed")
    data class Completed(val text: String, val usage: ModelUsage? = null) : ModelResponse
}

@Serializable
data class ModelUsage(val inputTokens: Int? = null, val outputTokens: Int? = null)

interface ModelProvider : AutoCloseable {
    val id: String
    val models: Map<ModelId, ModelCapabilities>
    suspend fun health(): Boolean = true
    fun generate(request: ModelRequest): Flow<ModelResponse>
    suspend fun cancel(runId: RunId) {}
}

@Serializable
data class ModelSelection(
    val providerId: String,
    val modelId: ModelId,
    val capabilities: ModelCapabilities,
    val fallback: Boolean = false,
    val reason: String,
)
