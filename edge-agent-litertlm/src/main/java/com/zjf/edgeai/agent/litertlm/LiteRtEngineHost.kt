package com.zjf.edgeai.agent.litertlm

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.zjf.edgeai.agent.api.ModelId
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.runtime.AndroidDeviceSignature
import com.zjf.edgeai.runtime.BackendFallbackException
import com.zjf.edgeai.runtime.ChatLanguagePolicy
import com.zjf.edgeai.runtime.ChatResponseQualityPolicy
import com.zjf.edgeai.runtime.RuntimeBackend
import com.zjf.edgeai.runtime.RuntimeDiagnostics
import com.zjf.edgeai.runtime.RuntimeInitializationException
import com.zjf.edgeai.runtime.RuntimeState
import com.zjf.edgeai.runtime.emulatorGpuPreflightFailure
import com.zjf.edgeai.runtime.model.ModelDescriptor
import com.zjf.edgeai.runtime.selectRuntimeBackend
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LocalModelRegistration(
    val modelId: ModelId,
    val descriptor: ModelDescriptor,
    val preferredBackend: RuntimeBackend = RuntimeBackend.GPU,
    val role: LocalModelRole = LocalModelRole.GENERAL,
    val capabilities: com.zjf.edgeai.agent.api.ModelCapabilities = com.zjf.edgeai.agent.api.ModelCapabilities(
        toolCalling = role == LocalModelRole.FUNCTION_CALLING,
        structuredOutput = role == LocalModelRole.FUNCTION_CALLING,
        maxContextTokens = 4_096,
        local = true,
    ),
)

enum class LocalModelRole { GENERAL, FUNCTION_CALLING }

sealed interface LiteRtHostEvent {
    data class Delta(val text: String) : LiteRtHostEvent
    data object Reset : LiteRtHostEvent
    data class Completed(val diagnostics: RuntimeDiagnostics) : LiteRtHostEvent
}

/**
 * 新 Agent SDK 路径中唯一拥有 LiteRT Engine/Conversation 的宿主。
 *
 * 所有 JNI 生命周期操作都在同一专用 dispatcher 上执行；逻辑 Worker 只持有 ModelProvider，
 * 不接触原生对象。Conversation 是每次模型请求可重建的缓存，上下文真相来自 Agent Session/Event。
 */
@OptIn(ExperimentalApi::class)
class LiteRtEngineHost(
    context: Context,
    registrations: List<LocalModelRegistration>,
    private val maxResidentModels: Int = 1,
) : AutoCloseable {
    private data class Slot(
        val registration: LocalModelRegistration,
        val state: MutableStateFlow<RuntimeState>,
        val diagnostics: MutableStateFlow<RuntimeDiagnostics>,
        var engine: Engine? = null,
        var conversation: Conversation? = null,
        var ready: RuntimeState.Ready? = null,
        var initialized: Boolean = false,
        var lastUsedNanos: Long = 0,
    )

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "edge-agent-litert-host")
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val executionMutex = Mutex()
    private val slots: Map<ModelId, Slot>
    private val activeRuns = ConcurrentHashMap<RunId, Slot>()
    @Volatile private var closed = false

    init {
        require(registrations.isNotEmpty()) { "至少注册一个 LiteRT-LM 模型" }
        require(registrations.map { it.modelId }.distinct().size == registrations.size)
        require(maxResidentModels in 1..2)
        slots = registrations.associate { registration ->
            registration.modelId to Slot(
                registration = registration,
                state = MutableStateFlow(RuntimeState.Unloaded),
                diagnostics = MutableStateFlow(environmentDiagnostics()),
            )
        }
    }

    fun generate(runId: RunId, modelId: ModelId, prompt: String): Flow<LiteRtHostEvent> = flow {
        executionMutex.withLock {
            check(!closed) { "LiteRT Engine Host 已关闭" }
            val slot = slots[modelId] ?: error("未注册本地模型 ${modelId.value}")
            ensureInitialized(slot)
            rebuildConversation(slot)
            val ready = requireNotNull(slot.ready)
            slot.state.value = RuntimeState.Generating(ready.effectiveBackend)
            activeRuns[runId] = slot
            try {
                for (attempt in 1..ChatResponseQualityPolicy.MAX_GENERATION_ATTEMPTS) {
                    val conversation = requireNotNull(slot.conversation)
                    val response = StringBuilder()
                    conversation.sendMessageAsync(
                        text = ChatLanguagePolicy.userTurnPayload(prompt),
                        repetitionPenaltyConfig = ChatLanguagePolicy.repetitionPenaltyConfig(),
                        noRepeatNgramConfig = ChatLanguagePolicy.noRepeatNgramConfig(),
                    ).collect { message ->
                        val text = message.toString()
                        if (text.isNotEmpty()) {
                            response.append(text)
                            emit(LiteRtHostEvent.Delta(text))
                        }
                    }
                    updateBenchmark(slot, conversation, runId, attempt)
                    val quality = ChatResponseQualityPolicy.assess(prompt, response.toString())
                    slot.diagnostics.value = slot.diagnostics.value.copy(
                        lastGenerationAttempts = attempt,
                        lastResponseRejectedAsEcho = quality.rejectedAsEcho,
                    )
                    if (!quality.rejectedAsEcho) {
                        ChatLanguagePolicy.literalPreservationSuffix(prompt, response.toString())
                            .takeIf(String::isNotEmpty)
                            ?.let { emit(LiteRtHostEvent.Delta(it)) }
                        slot.lastUsedNanos = System.nanoTime()
                        emit(LiteRtHostEvent.Completed(slot.diagnostics.value))
                        return@withLock
                    }
                    emit(LiteRtHostEvent.Reset)
                    rebuildConversation(slot)
                }
                error("模型连续两次复述问题，已终止本次生成")
            } catch (cancelled: CancellationException) {
                runCatching { slot.conversation?.cancelProcess() }
                throw cancelled
            } finally {
                activeRuns.remove(runId, slot)
                slot.state.value = if (slot.initialized) slot.ready ?: RuntimeState.Unloaded
                else RuntimeState.Unloaded
            }
        }
    }.flowOn(dispatcher)

    suspend fun load(modelId: ModelId) = withContext(dispatcher) {
        executionMutex.withLock {
            check(!closed) { "LiteRT Engine Host 已关闭" }
            ensureInitialized(slots[modelId] ?: error("未注册本地模型 ${modelId.value}"))
        }
    }

    suspend fun unload(modelId: ModelId) = withContext(dispatcher) {
        executionMutex.withLock {
            val slot = slots[modelId] ?: error("未注册本地模型 ${modelId.value}")
            check(slot !in activeRuns.values) { "模型仍有活动租约，不能卸载" }
            closeSlot(slot)
        }
    }

    fun state(modelId: ModelId): StateFlow<RuntimeState> = slot(modelId).state
    fun diagnostics(modelId: ModelId): StateFlow<RuntimeDiagnostics> = slot(modelId).diagnostics

    fun cancel(runId: RunId) {
        val slot = activeRuns[runId] ?: return
        val ready = slot.ready ?: return
        slot.state.value = RuntimeState.Cancelling(ready.effectiveBackend)
        scope.launch { runCatching { slot.conversation?.cancelProcess() } }
    }

    suspend fun onMemoryPressure() = withContext(dispatcher) {
        executionMutex.withLock {
            val keep = if (maxResidentModels > 1) 1 else 0
            evictTo(keep)
        }
    }

    private fun ensureInitialized(slot: Slot) {
        if (slot.initialized) {
            slot.lastUsedNanos = System.nanoTime()
            return
        }
        evictTo(maxResidentModels - 1)
        val registration = slot.registration
        val model = registration.descriptor
        slot.state.value = RuntimeState.Initializing(registration.preferredBackend)
        slot.diagnostics.value = environmentDiagnostics().copy(
            modelName = model.displayName,
            modelSha256 = model.sha256,
            modelSizeBytes = model.sizeBytes,
            preferredBackend = registration.preferredBackend,
        )
        try {
            val selection = selectRuntimeBackend(
                preferred = registration.preferredBackend,
                gpuPreflightFailure = emulatorGpuPreflightFailure(
                    registration.preferredBackend,
                    currentDeviceSignature(),
                ),
            ) { backend -> initializeBackend(slot, backend) }
            val fallbackReason = selection.gpuFailure?.let { sanitizeError(it, model.absolutePath) }
            slot.ready = RuntimeState.Ready(
                registration.preferredBackend,
                selection.effectiveBackend,
                fallbackReason,
            )
            slot.initialized = true
            slot.lastUsedNanos = System.nanoTime()
            slot.state.value = requireNotNull(slot.ready)
            slot.diagnostics.value = slot.diagnostics.value.copy(
                effectiveBackend = selection.effectiveBackend,
                fallbackReason = fallbackReason,
                initializationSeconds = runCatching {
                    slot.conversation?.getBenchmarkInfo()?.initTimeInSecond?.validMetric()
                }.getOrNull(),
            )
        } catch (fallback: BackendFallbackException) {
            closeSlot(slot)
            val message = "GPU 初始化失败：${sanitizeError(fallback.gpuFailure, model.absolutePath)}；" +
                "CPU 回退失败：${sanitizeError(fallback.cpuFailure, model.absolutePath)}"
            slot.state.value = RuntimeState.Error(message)
            slot.diagnostics.value = slot.diagnostics.value.copy(fallbackReason = message)
            throw RuntimeInitializationException(message, fallback.cpuFailure)
        } catch (failure: Throwable) {
            closeSlot(slot)
            val message = "模型初始化失败：${sanitizeError(failure, model.absolutePath)}"
            slot.state.value = RuntimeState.Error(message)
            throw RuntimeInitializationException(message, failure)
        }
    }

    private fun initializeBackend(slot: Slot, backend: RuntimeBackend) {
        closeNative(slot)
        val model = slot.registration.descriptor
        val cacheDir = File(appContext.filesDir, "litertlm-cache/${model.sha256}").apply { mkdirs() }
        val candidate = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = backend.toSdkBackend(),
                cacheDir = cacheDir.absolutePath,
            )
        )
        try {
            candidate.initialize()
            val conversation = candidate.createConversation(ChatLanguagePolicy.createConversationConfig())
            slot.engine = candidate
            slot.conversation = conversation
        } catch (failure: Throwable) {
            runCatching { candidate.close() }
            throw failure
        }
    }

    private fun rebuildConversation(slot: Slot) {
        val engine = slot.engine ?: error("LiteRT Engine 不可用")
        slot.conversation?.close()
        slot.conversation = engine.createConversation(ChatLanguagePolicy.createConversationConfig())
    }

    private fun updateBenchmark(
        slot: Slot,
        conversation: Conversation,
        runId: RunId,
        attempt: Int,
    ) {
        slot.diagnostics.value = runCatching {
            val benchmark = conversation.getBenchmarkInfo()
            slot.diagnostics.value.copy(
                initializationSeconds = benchmark.initTimeInSecond.validMetric(),
                timeToFirstTokenSeconds = benchmark.timeToFirstTokenInSecond.validMetric(),
                prefillTokenCount = benchmark.lastPrefillTokenCount.validCount(),
                decodeTokenCount = benchmark.lastDecodeTokenCount.validCount(),
                prefillTokensPerSecond = benchmark.lastPrefillTokensPerSecond.validMetric(),
                decodeTokensPerSecond = benchmark.lastDecodeTokensPerSecond.validMetric(),
                totalConversationTokens = conversation.getTokenCount().validCount(),
                lastRequestId = runId.value,
                lastGenerationAttempts = attempt,
                thinkingEnabled = ChatLanguagePolicy.THINKING_ENABLED,
            )
        }.getOrDefault(slot.diagnostics.value)
    }

    private fun evictTo(target: Int) {
        val activeSlots = activeRuns.values.toSet()
        slots.values.asSequence()
            .filter { it.initialized && it !in activeSlots }
            .sortedBy { it.lastUsedNanos }
            .take((slots.values.count { it.initialized } - target).coerceAtLeast(0))
            .forEach(::closeSlot)
    }

    private fun closeSlot(slot: Slot) {
        closeNative(slot)
        slot.initialized = false
        slot.ready = null
        slot.state.value = RuntimeState.Unloaded
    }

    private fun closeNative(slot: Slot) {
        runCatching { slot.conversation?.close() }
        slot.conversation = null
        runCatching { slot.engine?.close() }
        slot.engine = null
    }

    private fun slot(modelId: ModelId): Slot = slots[modelId]
        ?: error("未注册本地模型 ${modelId.value}")

    private fun environmentDiagnostics() = RuntimeDiagnostics(
        deviceManufacturer = Build.MANUFACTURER,
        deviceModel = Build.MODEL,
        androidApi = Build.VERSION.SDK_INT,
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        thinkingEnabled = ChatLanguagePolicy.THINKING_ENABLED,
    )

    private fun currentDeviceSignature() = AndroidDeviceSignature(
        fingerprint = Build.FINGERPRINT,
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        device = Build.DEVICE,
        hardware = Build.HARDWARE,
    )

    private fun sanitizeError(failure: Throwable?, modelPath: String?): String {
        val raw = failure?.message ?: failure?.javaClass?.simpleName ?: "未知错误"
        return if (modelPath.isNullOrBlank()) raw.take(600)
        else raw.replace(modelPath, "<model>").take(600)
    }

    private fun RuntimeBackend.toSdkBackend(): Backend = when (this) {
        RuntimeBackend.GPU -> Backend.GPU()
        RuntimeBackend.CPU -> Backend.CPU()
    }

    private fun Double.validMetric(): Double? = takeIf { it.isFinite() && it >= 0.0 }
    private fun Int.validCount(): Int? = takeIf { it >= 0 }

    override fun close() {
        if (closed) return
        runBlocking {
            withContext(dispatcher) {
                executionMutex.withLock {
                    activeRuns.values.forEach { runCatching { it.conversation?.cancelProcess() } }
                    activeRuns.clear()
                    slots.values.forEach(::closeSlot)
                    closed = true
                }
            }
        }
        scope.cancel()
        dispatcher.close()
        executor.shutdown()
    }
}
