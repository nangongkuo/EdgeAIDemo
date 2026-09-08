package com.zjf.edgeai.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.ApprovalSnapshot
import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.agent.api.ApprovalId
import com.zjf.edgeai.agent.api.ArtifactRef
import com.zjf.edgeai.agent.api.ModelPolicy
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.PendingInputSnapshot
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.WorkerSnapshot
import com.zjf.edgeai.agent.litertlm.AgentModelController
import com.zjf.edgeai.agent.litertlm.LiteRtAgentController
import com.zjf.edgeai.runtime.RuntimeBackend
import com.zjf.edgeai.runtime.RuntimeDiagnostics
import com.zjf.edgeai.runtime.RuntimeState
import com.zjf.edgeai.runtime.model.LocalModelStore
import com.zjf.edgeai.runtime.model.ModelDescriptor
import com.zjf.edgeai.runtime.model.ModelImportEvent
import com.zjf.edgeai.runtime.model.ModelStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EdgeAiUiState(
    val model: ModelDescriptor? = null,
    val runtimeState: RuntimeState = RuntimeState.Unloaded,
    val diagnostics: RuntimeDiagnostics? = null,
    val preferredBackend: RuntimeBackend = RuntimeBackend.GPU,
    val importStatus: String? = null,
    val importProgress: Int? = null,
    val isImporting: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val activeRunId: RunId? = null,
    val agentRunState: RunState? = null,
    val workers: List<WorkerSnapshot> = emptyList(),
    val approvals: List<ApprovalSnapshot> = emptyList(),
    val pendingInputs: List<PendingInputSnapshot> = emptyList(),
    val artifacts: List<ArtifactRef> = emptyList(),
    val timeline: List<String> = emptyList(),
    val foregroundExecutionEnabled: Boolean = false,
    val localOnlyMode: Boolean = true,
    val capabilitySummary: List<String> = listOf(
        "Tool Calling：手动执行 / Schema 校验 / 风险审批",
        "Skill：指令 / 声明式工作流 / 隔离 JavaScript",
        "MCP：Streamable HTTP / SSE Client",
        "A2A：Remote Worker Client / 本地降级",
        "模型：LiteRT-LM 本地优先 / 可选 OpenAI-compatible 云端",
        "记忆：本地 Room FTS / 可选 Embedding",
    ),
    val errorMessage: String? = null,
)

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val streaming: Boolean = false,
)

class EdgeAiViewModel(
    private val modelStore: ModelStore,
    private val modelController: AgentModelController,
    private val foregroundHost: AgentForegroundHost = AgentForegroundHost.None,
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
                        LiteRtAgentController(appContext),
                        AndroidAgentForegroundHost(appContext),
                    ) as T
                }
            }
        }
    }

    private val ids = AtomicLong(0L)
    private val _uiState = MutableStateFlow(
        EdgeAiUiState(
            model = modelStore.selectedModel.value,
            runtimeState = modelController.state.value,
            diagnostics = modelController.diagnostics.value,
        )
    )
    val uiState: StateFlow<EdgeAiUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private var startupJob: Job? = null
    private var sessionId = SessionId.create()

    init {
        viewModelScope.launch {
            modelStore.selectedModel.collect { model -> _uiState.update { it.copy(model = model) } }
        }
        viewModelScope.launch {
            modelController.state.collect { state -> _uiState.update { it.copy(runtimeState = state) } }
        }
        viewModelScope.launch {
            modelController.diagnostics.collect { diagnostics ->
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
            modelController.unload()
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgress = null,
                    importStatus = "准备导入模型…",
                    errorMessage = null,
                )
            }
            modelStore.importModel(uri)
                .catch { failure ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importStatus = null,
                            importProgress = null,
                            errorMessage = failure.message ?: "模型导入失败",
                        )
                    }
                }
                .collect(::handleImportEvent)
        }
    }

    fun initializeModel() {
        val model = _uiState.value.model ?: run {
            showError("请先导入模型")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            runCatching { modelController.initialize(model, _uiState.value.preferredBackend) }
                .onFailure { showError(it.message ?: "模型初始化失败") }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            stopGeneration()
            modelController.unload()
            clearRunUi()
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            stopGeneration()
            modelController.unload()
            modelStore.deleteSelectedModel()
            clearRunUi()
            _uiState.update { it.copy(importStatus = null, importProgress = null, errorMessage = null) }
        }
    }

    fun sendPrompt(prompt: String) {
        if (generationJob?.isActive == true || prompt.isBlank()) return
        if (_uiState.value.runtimeState !is RuntimeState.Ready) {
            showError("请先在模型页完成初始化")
            return
        }
        val client = modelController.client.value ?: run {
            showError("Agent SDK 尚未初始化")
            return
        }

        val userMessage = ChatMessage(ids.incrementAndGet(), ChatRole.USER, prompt)
        val assistantId = ids.incrementAndGet()
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage +
                    ChatMessage(assistantId, ChatRole.ASSISTANT, "", streaming = true),
                errorMessage = null,
                workers = emptyList(),
                approvals = emptyList(),
                artifacts = emptyList(),
                timeline = emptyList(),
                pendingInputs = emptyList(),
            )
        }

        generationJob = viewModelScope.launch {
            try {
                val localOnly = _uiState.value.localOnlyMode
                val handle = client.submit(
                    AgentRequest(
                        input = prompt,
                        sessionId = sessionId,
                        modelPolicy = if (localOnly) ModelPolicy.LOCAL_ONLY else ModelPolicy.PREFER_LOCAL,
                        privacyLevel = if (localOnly) PrivacyLevel.LOCAL_ONLY else PrivacyLevel.PRIVATE,
                    )
                )
                _uiState.update { it.copy(activeRunId = handle.runId, agentRunState = RunState.QUEUED) }
                if (_uiState.value.foregroundExecutionEnabled) foregroundHost.start(handle.runId)
                handle.events.transformWhile { event ->
                    emit(event)
                    event !is AgentEvent.Terminal
                }.collect { event -> handleAgentEvent(client, assistantId, event) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                updateAssistant(assistantId) { current ->
                    current.copy(
                        text = current.text.ifBlank { "生成失败：${failure.message ?: "未知错误"}" },
                        streaming = false,
                    )
                }
                showError(failure.message ?: "Agent 任务失败")
            } finally {
                foregroundHost.stop()
                updateAssistant(assistantId) { it.copy(streaming = false) }
            }
        }
    }

    fun stopGeneration() {
        val runId = _uiState.value.activeRunId
        val client = modelController.client.value
        if (runId != null && client != null && _uiState.value.agentRunState?.terminal != true) {
            viewModelScope.launch { client.cancel(runId) }
        }
        generationJob?.cancel()
        generationJob = null
        foregroundHost.stop()
        _uiState.update { state ->
            state.copy(messages = state.messages.map { it.copy(streaming = false) })
        }
    }

    fun clearConversation() {
        stopGeneration()
        sessionId = SessionId.create()
        clearRunUi()
    }

    fun setForegroundExecutionEnabled(enabled: Boolean) {
        _uiState.update { it.copy(foregroundExecutionEnabled = enabled) }
        if (!enabled) foregroundHost.stop()
    }

    fun setLocalOnlyMode(enabled: Boolean) {
        _uiState.update { it.copy(localOnlyMode = enabled) }
    }

    fun decideApproval(approvalId: ApprovalId, decision: ApprovalDecision) {
        val runId = _uiState.value.activeRunId ?: return
        val client = modelController.client.value ?: return
        viewModelScope.launch {
            runCatching {
                client.continueRun(runId, com.zjf.edgeai.agent.api.UserContinuation.Approval(approvalId, decision))
            }.onFailure { showError(it.message ?: "审批提交失败") }
        }
    }

    fun continueInput(requestId: String, value: String) {
        if (value.isBlank()) return
        val runId = _uiState.value.activeRunId ?: return
        val client = modelController.client.value ?: return
        viewModelScope.launch {
            runCatching {
                client.continueRun(runId, com.zjf.edgeai.agent.api.UserContinuation.Input(requestId, value))
            }.onFailure { showError(it.message ?: "补充信息提交失败") }
        }
    }

    fun cancelActiveRun() = stopGeneration()

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        startupJob?.cancel()
        generationJob?.cancel()
        foregroundHost.stop()
        modelController.close()
        super.onCleared()
    }

    private suspend fun handleAgentEvent(
        client: com.zjf.edgeai.agent.api.EdgeAgentClient,
        assistantId: Long,
        event: AgentEvent,
    ) {
        _uiState.update { state ->
            state.copy(timeline = (state.timeline + event.timelineLabel()).takeLast(100))
        }
        when (event) {
            is AgentEvent.ModelToken -> updateAssistant(assistantId) {
                it.copy(text = it.text + event.text)
            }
            is AgentEvent.ModelReset -> updateAssistant(assistantId) {
                it.copy(text = "", streaming = true)
            }
            is AgentEvent.RunStateChanged -> _uiState.update { it.copy(agentRunState = event.current) }
            is AgentEvent.WorkerStateChanged -> _uiState.update { state ->
                state.copy(
                    workers = state.workers.filterNot {
                        it.workerId == event.worker.workerId
                    } + event.worker
                )
            }
            is AgentEvent.ApprovalLifecycle -> {
                val snapshot = client.snapshot(event.runId)
                _uiState.update {
                    it.copy(
                        approvals = snapshot.pendingApprovals,
                        pendingInputs = snapshot.pendingInputs,
                    )
                }
            }
            is AgentEvent.UserInputRequired -> {
                val snapshot = client.snapshot(event.runId)
                _uiState.update { it.copy(pendingInputs = snapshot.pendingInputs) }
            }
            is AgentEvent.ArtifactCommitted -> _uiState.update { state ->
                state.copy(
                    artifacts = state.artifacts.filterNot { it.id == event.artifact.id } + event.artifact
                )
            }
            is AgentEvent.Terminal -> {
                updateAssistant(assistantId) { current ->
                    current.copy(
                        text = current.text.ifBlank {
                            event.output ?: event.failure?.let { "生成失败：${it.message}" }.orEmpty()
                        },
                        streaming = false,
                    )
                }
                _uiState.update {
                    it.copy(
                        agentRunState = event.state,
                        approvals = emptyList(),
                        pendingInputs = emptyList(),
                    )
                }
                event.failure?.let { showError(it.message) }
            }
            else -> Unit
        }
    }

    private fun AgentEvent.timelineLabel(): String = when (this) {
        is AgentEvent.RunStateChanged -> "#$sequence Run ${current.name}"
        is AgentEvent.RouteSelected -> "#$sequence 路由 ${route.name}"
        is AgentEvent.PlanCreated -> "#$sequence 计划 ${plan.nodes.size} 节点"
        is AgentEvent.WorkerStateChanged -> "#$sequence Worker ${worker.agentId.value} ${worker.state.name}"
        is AgentEvent.HandoffLifecycle -> "#$sequence Handoff ${fromAgentId.value} → ${toAgentId.value} ${state.name}"
        is AgentEvent.ModelToken -> "#$sequence 模型输出"
        is AgentEvent.ModelReset -> "#$sequence 模型重试"
        is AgentEvent.ToolLifecycle -> "#$sequence Tool ${call.capabilityId.value} ${state.name}"
        is AgentEvent.ApprovalLifecycle -> "#$sequence 审批 ${request.capabilityId.value}"
        is AgentEvent.UserInputRequired -> "#$sequence 等待输入"
        is AgentEvent.ArtifactCommitted -> "#$sequence Artifact ${artifact.name}"
        is AgentEvent.MemoryLifecycle -> "#$sequence 记忆 $action"
        is AgentEvent.Terminal -> "#$sequence 终态 ${state.name}"
    }

    private fun handleImportEvent(event: ModelImportEvent) {
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
                    model = event.model,
                    isImporting = false,
                    importProgress = 100,
                    importStatus = "模型导入完成",
                    messages = emptyList(),
                )
            }
        }
    }

    private fun clearRunUi() {
        _uiState.update {
            it.copy(
                messages = emptyList(),
                activeRunId = null,
                agentRunState = null,
                workers = emptyList(),
                approvals = emptyList(),
                pendingInputs = emptyList(),
                artifacts = emptyList(),
                timeline = emptyList(),
                errorMessage = null,
            )
        }
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
                        errorMessage = null,
                    )
                }
                modelStore.installBundledModel().collect { event ->
                    handleImportEvent(event)
                    if (event is ModelImportEvent.Completed) startupModel = event.model
                }
            }
            val model = startupModel ?: throw IllegalStateException("没有可初始化的内置模型")
            _uiState.update { it.copy(isImporting = false, importStatus = "正在自动初始化模型…") }
            modelController.initialize(model, _uiState.value.preferredBackend)
            _uiState.update { it.copy(importStatus = "模型已自动初始化", errorMessage = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _uiState.update {
                it.copy(
                    isImporting = false,
                    importProgress = null,
                    importStatus = null,
                    errorMessage = failure.message ?: "内置模型初始化失败",
                )
            }
        }
    }
}
