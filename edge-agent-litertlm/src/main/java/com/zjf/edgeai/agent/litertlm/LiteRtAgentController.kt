package com.zjf.edgeai.agent.litertlm

import android.content.Context
import com.zjf.edgeai.agent.adk.AdkAgentEngine
import com.zjf.edgeai.agent.api.AgentDefinition
import com.zjf.edgeai.agent.api.AgentId
import com.zjf.edgeai.agent.api.AgentSdkConfig
import com.zjf.edgeai.agent.api.EdgeAgentClient
import com.zjf.edgeai.agent.api.EdgeAgentSdk
import com.zjf.edgeai.agent.api.ModelCapabilities
import com.zjf.edgeai.agent.api.ModelId
import com.zjf.edgeai.agent.capabilities.ToolRegistry
import com.zjf.edgeai.agent.capabilities.calendar.AndroidCalendarCapability
import com.zjf.edgeai.agent.capabilities.calendar.AndroidCalendarToolProvider
import com.zjf.edgeai.agent.capabilities.calendar.CalendarActionResolver
import com.zjf.edgeai.runtime.RuntimeBackend
import com.zjf.edgeai.runtime.RuntimeDiagnostics
import com.zjf.edgeai.runtime.RuntimeState
import com.zjf.edgeai.runtime.model.ModelDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface AgentModelController : AutoCloseable {
    val state: StateFlow<RuntimeState>
    val diagnostics: StateFlow<RuntimeDiagnostics>
    val client: StateFlow<EdgeAgentClient?>
    suspend fun initialize(model: ModelDescriptor, preferred: RuntimeBackend = RuntimeBackend.GPU)
    suspend fun initialize(models: List<LocalModelRegistration>) {
        require(models.size == 1) { "此 Controller 实现不支持多模型" }
        initialize(models.single().descriptor, models.single().preferredBackend)
    }
    suspend fun unload()
}

class LiteRtAgentController(context: Context) : AgentModelController {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Unloaded)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()
    private val _diagnostics = MutableStateFlow(emptyDiagnostics())
    override val diagnostics: StateFlow<RuntimeDiagnostics> = _diagnostics.asStateFlow()
    private val _client = MutableStateFlow<EdgeAgentClient?>(null)
    override val client: StateFlow<EdgeAgentClient?> = _client.asStateFlow()
    private var host: LiteRtEngineHost? = null
    private val observers = mutableListOf<Job>()

    override suspend fun initialize(model: ModelDescriptor, preferred: RuntimeBackend) {
        initialize(
            listOf(
                LocalModelRegistration(
                    modelId = ModelId("gemma3-local"),
                    descriptor = model,
                    preferredBackend = preferred,
                )
            )
        )
    }

    override suspend fun initialize(models: List<LocalModelRegistration>) {
        unload()
        require(models.isNotEmpty()) { "至少需要一个本地模型" }
        require(models.size <= 2) { "Agent SDK 1.0 最多同时注册两个本地模型" }
        require(models.all { it.capabilities.local }) { "LiteRT 注册项必须是本地模型" }
        val general = models.firstOrNull { it.role == LocalModelRole.GENERAL } ?: models.first()
        val nextHost = LiteRtEngineHost(
            appContext,
            models,
            maxResidentModels = if (isLowMemoryDevice()) 1 else 2,
        )
        host = nextHost
        observers += scope.launch { nextHost.state(general.modelId).collect { _state.value = it } }
        observers += scope.launch { nextHost.diagnostics(general.modelId).collect { _diagnostics.value = it } }
        try {
            nextHost.load(general.modelId)
            val provider = EdgeLiteRtModelAdapter(
                id = "litertlm-local",
                models = models.associate { it.modelId to it.capabilities },
                host = nextHost,
            )
            val root = AgentDefinition(
                id = AgentId("root-supervisor"),
                name = "root_supervisor",
                description = "端侧请求路由、执行与最终汇总 Agent",
                instructions = "使用简体中文直接回答；只能调用已注册、已授权的能力；未获得成功 ToolResult 前不得声称外部操作已完成；复杂任务先验证 Worker 证据，再给出唯一最终结论。",
                preferredModelId = general.modelId,
                capabilityAllowlist = setOf(AndroidCalendarCapability.CREATE_EVENT),
                childAgents = listOf(AgentId("analysis-worker"), AgentId("verification-worker")),
            )
            val workers = listOf(
                AgentDefinition(
                    AgentId("analysis-worker"),
                    "analysis_worker",
                    "独立分析任务并返回事实摘要",
                    "只处理分配目标，输出简明结论和证据，不接管用户会话。",
                    preferredModelId = general.modelId,
                ),
                AgentDefinition(
                    AgentId("verification-worker"),
                    "verification_worker",
                    "独立检查结论、风险与遗漏",
                    "验证其他结果的正确性，明确不确定项，不直接面向用户作最终回答。",
                    preferredModelId = general.modelId,
                ),
            )
            val toolRegistry = ToolRegistry(
                providers = listOf(AndroidCalendarToolProvider(appContext)),
            )
            val adkEngine = AdkAgentEngine(listOf(provider), toolRuntime = toolRegistry)
            _client.value = EdgeAgentSdk.create(
                appContext,
                AgentSdkConfig(
                    rootAgent = root,
                    workerAgents = workers,
                    modelProviders = listOf(provider),
                    agentEngine = adkEngine,
                    toolRuntime = toolRegistry,
                    actionResolvers = listOf(CalendarActionResolver()),
                ),
            )
        } catch (failure: Throwable) {
            nextHost.close()
            host = null
            throw failure
        }
    }

    override suspend fun unload() {
        _client.value?.close()
        _client.value = null
        observers.forEach(Job::cancel)
        observers.clear()
        host?.close()
        host = null
        _state.value = RuntimeState.Unloaded
        _diagnostics.value = emptyDiagnostics()
    }

    override fun close() {
        _client.value?.close()
        _client.value = null
        observers.forEach(Job::cancel)
        host?.close()
        host = null
        scope.cancel()
    }

    private fun isLowMemoryDevice(): Boolean {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.isLowRamDevice
    }

    private fun emptyDiagnostics() = RuntimeDiagnostics(
        deviceManufacturer = android.os.Build.MANUFACTURER,
        deviceModel = android.os.Build.MODEL,
        androidApi = android.os.Build.VERSION.SDK_INT,
        supportedAbis = android.os.Build.SUPPORTED_ABIS.toList(),
    )
}
