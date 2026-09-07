package com.zjf.edgeai.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zjf.edgeai.runtime.EdgeAiRuntime
import com.zjf.edgeai.runtime.GenerationEvent
import com.zjf.edgeai.runtime.LiteRtLmRuntime
import com.zjf.edgeai.runtime.RuntimeBackend
import com.zjf.edgeai.runtime.RuntimeDiagnostics
import com.zjf.edgeai.runtime.RuntimeState
import com.zjf.edgeai.runtime.model.LocalModelStore
import com.zjf.edgeai.runtime.model.ModelDescriptor
import com.zjf.edgeai.runtime.model.ModelImportEvent
import com.zjf.edgeai.runtime.model.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class EdgeAiUiState(
    val model: ModelDescriptor? = null,
    val runtimeState: RuntimeState = RuntimeState.Unloaded,
    val diagnostics: RuntimeDiagnostics? = null,
    val preferredBackend: RuntimeBackend = RuntimeBackend.GPU,
    val importStatus: String? = null,
    val importProgress: Int? = null,
    val isImporting: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val errorMessage: String? = null
)

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false
)

class EdgeAiViewModel(
    private val modelStore: ModelStore,
    private val runtime: EdgeAiRuntime
) : ViewModel() {
    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(EdgeAiViewModel::class.java)) {
                        "Unsupported ViewModel: ${modelClass.name}"
                    }
                    return EdgeAiViewModel(
                        LocalModelStore(appContext),
                        LiteRtLmRuntime(appContext)
                    ) as T
                }
            }
        }
    }

    private val ids = AtomicLong(0L)
    private val _uiState = MutableStateFlow(
        EdgeAiUiState(
            model = modelStore.selectedModel.value,
            runtimeState = runtime.state.value,
            diagnostics = runtime.diagnostics.value
        )
    )
    val uiState: StateFlow<EdgeAiUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private var startupJob: Job? = null

    init {
        viewModelScope.launch {
            modelStore.selectedModel.collect { model ->
                _uiState.update { it.copy(model = model) }
            }
        }
        viewModelScope.launch {
            runtime.state.collect { state ->
                _uiState.update { it.copy(runtimeState = state) }
            }
        }
        viewModelScope.launch {
            runtime.diagnostics.collect { diagnostics ->
                _uiState.update { it.copy(diagnostics = diagnostics) }
            }
        }
        startupJob = viewModelScope.launch { installAndInitializeStartupModel() }
    }

    fun setPreferredBackend(backend: RuntimeBackend) {
        _uiState.update { it.copy(preferredBackend = backend) }
    }

    fun importModel(uri: Uri) {
        if (_uiState.value.isImporting) return
        viewModelScope.launch {
            stopGeneration()
            runtime.unload()
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgress = null,
                    importStatus = "准备导入模型…",
                    errorMessage = null
                )
            }
            modelStore.importModel(uri)
                .catch { failure ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importStatus = null,
                            importProgress = null,
                            errorMessage = failure.message ?: "模型导入失败"
                        )
                    }
                }
                .collect { event ->
                    when (event) {
                        is ModelImportEvent.Started -> _uiState.update {
                            it.copy(importStatus = "正在导入 ${event.displayName}")
                        }
                        is ModelImportEvent.Progress -> {
                            val progress = event.totalBytes?.takeIf { it > 0L }
                                ?.let { total -> ((event.copiedBytes * 100L) / total).toInt().coerceIn(0, 100) }
                            _uiState.update { it.copy(importProgress = progress) }
                        }
                        is ModelImportEvent.Completed -> _uiState.update {
                            it.copy(
                                isImporting = false,
                                importProgress = 100,
                                importStatus = "模型导入完成",
                                messages = emptyList()
                            )
                        }
                    }
                }
        }
    }

    fun initializeModel() {
        val model = _uiState.value.model ?: run {
            showError("请先导入模型")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            runCatching { runtime.initialize(model, _uiState.value.preferredBackend) }
                .onFailure { showError(it.message ?: "模型初始化失败") }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            stopGeneration()
            runtime.unload()
            _uiState.update { it.copy(messages = emptyList(), errorMessage = null) }
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            stopGeneration()
            runtime.unload()
            modelStore.deleteSelectedModel()
            _uiState.update {
                it.copy(messages = emptyList(), importStatus = null, importProgress = null, errorMessage = null)
            }
        }
    }

    fun sendPrompt(prompt: String) {
        if (generationJob?.isActive == true) return
        if (_uiState.value.runtimeState !is RuntimeState.Ready) {
            showError("请先在模型页完成初始化")
            return
        }
        if (prompt.isBlank()) return

        val userMessage = ChatMessage(ids.incrementAndGet(), ChatRole.USER, prompt)
        val assistantId = ids.incrementAndGet()
        val assistantMessage = ChatMessage(assistantId, ChatRole.ASSISTANT, "", streaming = true)
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage + assistantMessage,
                errorMessage = null
            )
        }

        generationJob = viewModelScope.launch {
            runtime.generate(prompt).collect { event ->
                when (event) {
                    is GenerationEvent.Delta -> updateAssistant(assistantId) { current ->
                        current.copy(text = current.text + event.text)
                    }
                    GenerationEvent.Reset -> updateAssistant(assistantId) { current ->
                        current.copy(text = "", streaming = true)
                    }
                    is GenerationEvent.Completed -> updateAssistant(assistantId) { current ->
                        current.copy(streaming = false)
                    }
                    is GenerationEvent.Failed -> {
                        updateAssistant(assistantId) { current ->
                            current.copy(
                                text = current.text.takeIf { it.isNotBlank() }
                                    ?: "生成失败：${event.message}",
                                streaming = false
                            )
                        }
                        showError(event.message)
                    }
                }
            }
            updateAssistant(assistantId) { it.copy(streaming = false) }
        }
    }

    fun stopGeneration() {
        runtime.cancel()
        generationJob?.cancel()
        generationJob = null
        _uiState.update { state ->
            state.copy(messages = state.messages.map { it.copy(streaming = false) })
        }
    }

    fun clearConversation() {
        viewModelScope.launch {
            stopGeneration()
            if (runtime.state.value is RuntimeState.Ready) {
                runCatching { runtime.resetConversation() }
                    .onFailure { showError(it.message ?: "清空会话失败") }
            }
            _uiState.update { it.copy(messages = emptyList()) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        startupJob?.cancel()
        generationJob?.cancel()
        runtime.close()
        super.onCleared()
    }

    private fun updateAssistant(id: Long, transform: (ChatMessage) -> ChatMessage) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    private suspend fun installAndInitializeStartupModel() {
        var startupModel = modelStore.selectedModel.value
        try {
            if (startupModel == null) {
                _uiState.update {
                    it.copy(
                        isImporting = true,
                        importStatus = "正在释放内置模型…",
                        importProgress = null,
                        errorMessage = null
                    )
                }
                modelStore.installBundledModel().collect { event ->
                    when (event) {
                        is ModelImportEvent.Started -> _uiState.update {
                            it.copy(importStatus = "正在释放 ${event.displayName}")
                        }
                        is ModelImportEvent.Progress -> {
                            val progress = event.totalBytes?.takeIf { it > 0L }
                                ?.let { total ->
                                    ((event.copiedBytes * 100L) / total).toInt().coerceIn(0, 100)
                                }
                            _uiState.update { it.copy(importProgress = progress) }
                        }
                        is ModelImportEvent.Completed -> {
                            startupModel = event.model
                            _uiState.update {
                                it.copy(
                                    model = event.model,
                                    isImporting = false,
                                    importProgress = 100,
                                    importStatus = "内置模型已就绪"
                                )
                            }
                        }
                    }
                }
            }

            val model = startupModel ?: throw IllegalStateException("没有可初始化的内置模型")
            _uiState.update {
                it.copy(isImporting = false, importStatus = "正在自动初始化模型…")
            }
            runtime.initialize(model, _uiState.value.preferredBackend)
            _uiState.update { it.copy(importStatus = "模型已自动初始化", errorMessage = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _uiState.update {
                it.copy(
                    isImporting = false,
                    importProgress = null,
                    importStatus = null,
                    errorMessage = failure.message ?: "内置模型初始化失败"
                )
            }
        }
    }
}
