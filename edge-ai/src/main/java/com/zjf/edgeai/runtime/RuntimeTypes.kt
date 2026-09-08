package com.zjf.edgeai.runtime

import com.zjf.edgeai.runtime.model.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

enum class RuntimeBackend { GPU, CPU }

sealed interface RuntimeState {
    data object Unloaded : RuntimeState
    data class Initializing(val preferredBackend: RuntimeBackend) : RuntimeState
    data class Ready(
        val preferredBackend: RuntimeBackend,
        val effectiveBackend: RuntimeBackend,
        val fallbackReason: String? = null
    ) : RuntimeState
    data class Generating(val effectiveBackend: RuntimeBackend) : RuntimeState
    data class Cancelling(val effectiveBackend: RuntimeBackend) : RuntimeState
    data class Error(val message: String, val recoverable: Boolean = true) : RuntimeState
}

sealed interface GenerationEvent {
    data class Delta(val text: String) : GenerationEvent
    data object Reset : GenerationEvent
    data class Completed(val diagnostics: RuntimeDiagnostics) : GenerationEvent
    data class Failed(val message: String) : GenerationEvent
}

data class RuntimeDiagnostics(
    val sdkVersion: String = BuildConfig.LITERT_LM_VERSION,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidApi: Int,
    val supportedAbis: List<String>,
    val modelName: String? = null,
    val modelSha256: String? = null,
    val modelSizeBytes: Long? = null,
    val preferredBackend: RuntimeBackend? = null,
    val effectiveBackend: RuntimeBackend? = null,
    val fallbackReason: String? = null,
    val initializationSeconds: Double? = null,
    val timeToFirstTokenSeconds: Double? = null,
    val prefillTokenCount: Int? = null,
    val decodeTokenCount: Int? = null,
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
    val totalConversationTokens: Int? = null,
    val lastRequestId: String? = null,
    val lastGenerationAttempts: Int? = null,
    val lastResponseRejectedAsEcho: Boolean? = null,
    val thinkingEnabled: Boolean? = null
)

@Deprecated(
    message = "请迁移到 EdgeAgentClient；该接口仅作为 1.x 单 Agent 兼容门面保留",
)
interface EdgeAiRuntime : Closeable {
    val state: StateFlow<RuntimeState>
    val diagnostics: StateFlow<RuntimeDiagnostics>

    suspend fun initialize(model: ModelDescriptor, preferred: RuntimeBackend = RuntimeBackend.GPU)
    fun generate(prompt: String): Flow<GenerationEvent>
    fun cancel()
    suspend fun resetConversation()
    suspend fun unload()
}

class RuntimeInitializationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
