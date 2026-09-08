package com.zjf.edgeai.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.zjf.edgeai.runtime.model.ModelDescriptor
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalApi::class)
@Suppress("DEPRECATION")
@Deprecated("请使用 edge-agent-litertlm 的 EdgeAgentRuntime 或 EdgeAgentClient")
class LiteRtLmRuntime(context: Context) : EdgeAiRuntime {
    companion object {
        private const val ERROR_LIMIT = 600
        private const val LOG_PREVIEW_LIMIT = 400
        private const val TAG = "EdgeAiGeneration"
        private const val ECHO_FAILURE_MESSAGE =
            "模型连续两次复述了问题，已丢弃异常回复。请换一种问法或清空会话后重试。"
    }

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lite-rt-lm-runtime")
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val runtimeScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val generationActive = AtomicBoolean(false)
    private val generationIds = AtomicLong(0L)

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Unloaded)
    override val state: StateFlow<RuntimeState> = _state

    private val _diagnostics = MutableStateFlow(environmentDiagnostics())
    override val diagnostics: StateFlow<RuntimeDiagnostics> = _diagnostics

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var activeModel: ModelDescriptor? = null
    private var readyState: RuntimeState.Ready? = null
    private val acceptedHistory = mutableListOf<Message>()
    private var closed = false

    override suspend fun initialize(model: ModelDescriptor, preferred: RuntimeBackend) {
        withContext(dispatcher) {
            check(!closed) { "运行时已关闭" }
            check(!generationActive.get()) { "生成期间不能重新初始化模型" }
            closeNative()
            acceptedHistory.clear()
            activeModel = model
            readyState = null
            _state.value = RuntimeState.Initializing(preferred)
            _diagnostics.value = environmentDiagnostics().withModel(model).copy(
                preferredBackend = preferred
            )

            try {
                val selection = selectRuntimeBackend(
                    preferred = preferred,
                    gpuPreflightFailure = emulatorGpuPreflightFailure(
                        preferred = preferred,
                        device = currentDeviceSignature()
                    )
                ) { backend ->
                    initializeBackend(model, backend)
                }
                markReady(
                    model = model,
                    preferred = preferred,
                    effective = selection.effectiveBackend,
                    fallbackReason = selection.gpuFailure?.let {
                        sanitizeError(it, model.absolutePath)
                    }
                )
            } catch (fallback: BackendFallbackException) {
                closeNative()
                val gpuReason = sanitizeError(fallback.gpuFailure, model.absolutePath)
                val cpuReason = sanitizeError(fallback.cpuFailure, model.absolutePath)
                val message = "GPU 初始化失败：$gpuReason；CPU 回退失败：$cpuReason"
                _state.value = RuntimeState.Error(message)
                _diagnostics.value = _diagnostics.value.copy(fallbackReason = message)
                throw RuntimeInitializationException(message, fallback.cpuFailure)
            } catch (failure: Throwable) {
                closeNative()
                val message = "CPU 初始化失败：${sanitizeError(failure, model.absolutePath)}"
                _state.value = RuntimeState.Error(message)
                throw RuntimeInitializationException(message, failure)
            }
        }
    }

    override fun generate(prompt: String): Flow<GenerationEvent> = flow {
        if (prompt.isBlank()) {
            emit(GenerationEvent.Failed("请输入问题"))
            return@flow
        }
        if (!generationActive.compareAndSet(false, true)) {
            emit(GenerationEvent.Failed("已有生成任务正在运行"))
            return@flow
        }

        val currentReady = readyState
        if (conversation == null || currentReady == null) {
            generationActive.set(false)
            emit(GenerationEvent.Failed("请先加载模型"))
            return@flow
        }

        _state.value = RuntimeState.Generating(currentReady.effectiveBackend)
        val requestId = "generation-${generationIds.incrementAndGet()}"
        var rejectedAnyAttempt = false
        debugLog(
            requestId,
            "start model=${activeModel?.sha256.orEmpty()} backend=${currentReady.effectiveBackend} " +
                "topK=${ChatLanguagePolicy.TOP_K} topP=${ChatLanguagePolicy.TOP_P} " +
                "temperature=${ChatLanguagePolicy.TEMPERATURE} seed=${ChatLanguagePolicy.SEED} " +
                "repetitionPenalty=${ChatLanguagePolicy.REPETITION_PENALTY} " +
                "noRepeatNgram=${ChatLanguagePolicy.NO_REPEAT_NGRAM_SIZE} " +
                "thinkingEnabled=${ChatLanguagePolicy.THINKING_ENABLED} " +
                "prompt=${logPreview(prompt)}"
        )
        try {
            for (attempt in 1..ChatResponseQualityPolicy.MAX_GENERATION_ATTEMPTS) {
                val currentConversation = requireNotNull(conversation) { "Conversation 重建失败" }
                val startedAtNanos = System.nanoTime()
                var firstTokenAtNanos: Long? = null
                val generatedText = StringBuilder()
                debugLog(requestId, "attempt=$attempt begin acceptedHistory=${acceptedHistory.size}")

                currentConversation.sendMessageAsync(
                    text = ChatLanguagePolicy.userTurnPayload(prompt),
                    repetitionPenaltyConfig = ChatLanguagePolicy.repetitionPenaltyConfig(),
                    noRepeatNgramConfig = ChatLanguagePolicy.noRepeatNgramConfig()
                ).collect { message ->
                    val text = message.toString()
                    debugLog(
                        requestId,
                        "attempt=$attempt message role=${message.role} " +
                            "channels=${message.channels.keys.sorted()} content=${logPreview(text)}"
                    )
                    if (text.isNotEmpty()) {
                        if (firstTokenAtNanos == null) {
                            firstTokenAtNanos = System.nanoTime()
                            debugLog(
                                requestId,
                                "attempt=$attempt firstTokenMs=${nanosToMillis(firstTokenAtNanos!! - startedAtNanos)}"
                            )
                        }
                        generatedText.append(text)
                        emit(GenerationEvent.Delta(text))
                    }
                }

                updateBenchmark(currentConversation)
                val response = generatedText.toString()
                val quality = ChatResponseQualityPolicy.assess(prompt, response)
                rejectedAnyAttempt = rejectedAnyAttempt || quality.rejectedAsEcho
                _diagnostics.value = _diagnostics.value.copy(
                    lastRequestId = requestId,
                    lastGenerationAttempts = attempt,
                    lastResponseRejectedAsEcho = rejectedAnyAttempt,
                    thinkingEnabled = ChatLanguagePolicy.THINKING_ENABLED
                )
                debugLog(
                    requestId,
                    "attempt=$attempt quality similarity=${"%.3f".format(quality.similarity)} " +
                        "rejected=${quality.rejectedAsEcho} response=${logPreview(response)}"
                )

                if (!quality.rejectedAsEcho) {
                    acceptTurn(prompt, response)
                    ChatLanguagePolicy.literalPreservationSuffix(prompt, response)
                        .takeIf { it.isNotEmpty() }
                        ?.let { suffix -> emit(GenerationEvent.Delta(suffix)) }
                    debugBenchmark(requestId, attempt, currentConversation)
                    emit(GenerationEvent.Completed(_diagnostics.value))
                    debugLog(requestId, "complete attempts=$attempt rejectedAny=$rejectedAnyAttempt")
                    return@flow
                }

                emit(GenerationEvent.Reset)
                rebuildConversationFromAcceptedHistory()
                debugLog(requestId, "attempt=$attempt rejected conversationRebuilt=true")
            }

            emit(GenerationEvent.Failed(ECHO_FAILURE_MESSAGE))
            debugLog(requestId, "failed reason=echo attempts=${ChatResponseQualityPolicy.MAX_GENERATION_ATTEMPTS}")
        } catch (cancelled: CancellationException) {
            runCatching { conversation?.cancelProcess() }
            debugLog(requestId, "cancelled")
            throw cancelled
        } catch (failure: Throwable) {
            if (rejectedAnyAttempt) {
                emit(GenerationEvent.Reset)
                runCatching { rebuildConversationFromAcceptedHistory() }
            }
            debugLog(requestId, "failed reason=${sanitizeError(failure, activeModel?.absolutePath)}")
            emit(GenerationEvent.Failed(sanitizeError(failure, activeModel?.absolutePath)))
        } finally {
            generationActive.set(false)
            _state.value = if (engine != null && conversation != null) {
                readyState ?: RuntimeState.Unloaded
            } else {
                RuntimeState.Unloaded
            }
        }
    }.flowOn(dispatcher)

    override fun cancel() {
        val ready = readyState ?: return
        if (!generationActive.get()) return
        _state.value = RuntimeState.Cancelling(ready.effectiveBackend)
        runtimeScope.runCatchingLaunch {
            conversation?.cancelProcess()
        }
    }

    override suspend fun resetConversation() {
        withContext(dispatcher) {
            check(!generationActive.get()) { "请先停止当前生成" }
            val currentEngine = engine ?: throw IllegalStateException("请先加载模型")
            conversation?.close()
            acceptedHistory.clear()
            conversation = currentEngine.createConversation(defaultConversationConfig())
            _state.value = readyState ?: RuntimeState.Unloaded
            _diagnostics.value = _diagnostics.value.copy(
                timeToFirstTokenSeconds = null,
                prefillTokenCount = null,
                decodeTokenCount = null,
                prefillTokensPerSecond = null,
                decodeTokensPerSecond = null,
                totalConversationTokens = null,
                lastRequestId = null,
                lastGenerationAttempts = null,
                lastResponseRejectedAsEcho = null,
                thinkingEnabled = ChatLanguagePolicy.THINKING_ENABLED
            )
        }
    }

    override suspend fun unload() {
        withContext(dispatcher) {
            if (generationActive.get()) runCatching { conversation?.cancelProcess() }
            closeNative()
            generationActive.set(false)
            acceptedHistory.clear()
            activeModel = null
            readyState = null
            _state.value = RuntimeState.Unloaded
            _diagnostics.value = environmentDiagnostics()
        }
    }

    override fun close() {
        if (closed) return
        runBlocking { unload() }
        closed = true
        runtimeScope.cancel()
        dispatcher.close()
        executor.shutdown()
    }

    private fun initializeBackend(model: ModelDescriptor, backend: RuntimeBackend) {
        val cacheDir = File(appContext.filesDir, "litertlm-cache/${model.sha256}").apply { mkdirs() }
        val candidate = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = backend.toSdkBackend(),
                cacheDir = cacheDir.absolutePath
            )
        )
        try {
            candidate.initialize()
            val candidateConversation = candidate.createConversation(defaultConversationConfig())
            engine = candidate
            conversation = candidateConversation
        } catch (failure: Throwable) {
            runCatching { candidate.close() }
            throw failure
        }
    }

    private fun markReady(
        model: ModelDescriptor,
        preferred: RuntimeBackend,
        effective: RuntimeBackend,
        fallbackReason: String?
    ) {
        val ready = RuntimeState.Ready(preferred, effective, fallbackReason)
        readyState = ready
        _state.value = ready
        val initialized = _diagnostics.value.withModel(model).copy(
            preferredBackend = preferred,
            effectiveBackend = effective,
            fallbackReason = fallbackReason
        )
        _diagnostics.value = runCatching {
            val benchmark = conversation?.getBenchmarkInfo()
            initialized.copy(initializationSeconds = benchmark?.initTimeInSecond?.validMetric())
        }.getOrDefault(initialized)
    }

    private fun updateBenchmark(currentConversation: Conversation) {
        _diagnostics.value = runCatching {
            val benchmark = currentConversation.getBenchmarkInfo()
            _diagnostics.value.copy(
                initializationSeconds = benchmark.initTimeInSecond.validMetric(),
                timeToFirstTokenSeconds = benchmark.timeToFirstTokenInSecond.validMetric(),
                prefillTokenCount = benchmark.lastPrefillTokenCount.validCount(),
                decodeTokenCount = benchmark.lastDecodeTokenCount.validCount(),
                prefillTokensPerSecond = benchmark.lastPrefillTokensPerSecond.validMetric(),
                decodeTokensPerSecond = benchmark.lastDecodeTokensPerSecond.validMetric(),
                totalConversationTokens = currentConversation.getTokenCount().validCount()
            )
        }.getOrDefault(_diagnostics.value)
    }

    private fun closeNative() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
    }

    private fun acceptTurn(prompt: String, response: String) {
        acceptedHistory += Message.user(prompt)
        acceptedHistory += Message.model(response)
    }

    private fun rebuildConversationFromAcceptedHistory() {
        val currentEngine = engine ?: throw IllegalStateException("模型运行时不可用")
        conversation?.close()
        conversation = currentEngine.createConversation(defaultConversationConfig(acceptedHistory.toList()))
    }

    private fun defaultConversationConfig(history: List<Message> = emptyList()) =
        ChatLanguagePolicy.createConversationConfig(history)

    private fun debugBenchmark(requestId: String, attempt: Int, currentConversation: Conversation) {
        if (!BuildConfig.DEBUG) return
        val diagnostics = _diagnostics.value
        Log.d(
            TAG,
            "requestId=$requestId attempt=$attempt benchmark " +
                "ttft=${diagnostics.timeToFirstTokenSeconds} " +
                "prefillTokens=${diagnostics.prefillTokenCount} " +
                "decodeTokens=${diagnostics.decodeTokenCount} " +
                "prefillRate=${diagnostics.prefillTokensPerSecond} " +
                "decodeRate=${diagnostics.decodeTokensPerSecond} " +
                "conversationTokens=${runCatching { currentConversation.getTokenCount() }.getOrNull()}"
        )
    }

    private fun debugLog(requestId: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "requestId=$requestId $message")
    }

    private fun logPreview(value: String): String = value
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .take(LOG_PREVIEW_LIMIT)

    private fun nanosToMillis(value: Long): Long = value / 1_000_000L

    private fun RuntimeBackend.toSdkBackend(): Backend = when (this) {
        RuntimeBackend.GPU -> Backend.GPU()
        RuntimeBackend.CPU -> Backend.CPU()
    }

    private fun environmentDiagnostics() = RuntimeDiagnostics(
        deviceManufacturer = Build.MANUFACTURER,
        deviceModel = Build.MODEL,
        androidApi = Build.VERSION.SDK_INT,
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        thinkingEnabled = ChatLanguagePolicy.THINKING_ENABLED
    )

    private fun currentDeviceSignature() = AndroidDeviceSignature(
        fingerprint = Build.FINGERPRINT,
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        device = Build.DEVICE,
        hardware = Build.HARDWARE
    )

    private fun RuntimeDiagnostics.withModel(model: ModelDescriptor) = copy(
        modelName = model.displayName,
        modelSha256 = model.sha256,
        modelSizeBytes = model.sizeBytes
    )

    private fun sanitizeError(failure: Throwable?, modelPath: String?): String {
        val raw = failure?.message ?: failure?.javaClass?.simpleName ?: "未知错误"
        val sanitized = if (modelPath.isNullOrBlank()) raw else raw.replace(modelPath, "<model>")
        return sanitized.take(ERROR_LIMIT)
    }

    private fun Double.validMetric(): Double? = takeIf { it.isFinite() && it >= 0.0 }
    private fun Int.validCount(): Int? = takeIf { it >= 0 }
}

private fun CoroutineScope.runCatchingLaunch(block: suspend () -> Unit) =
    launch { runCatching { block() } }
