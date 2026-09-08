package com.zjf.edgeai.agent.api

import java.io.Closeable
import kotlinx.coroutines.flow.Flow

data class RunHandle(
    val runId: RunId,
    val events: Flow<AgentEvent>,
)

interface EdgeAgentClient : Closeable {
    suspend fun submit(request: AgentRequest): RunHandle
    fun observe(runId: RunId): Flow<AgentEvent>
    suspend fun snapshot(runId: RunId): RunSnapshot
    suspend fun continueRun(runId: RunId, continuation: UserContinuation)
    suspend fun cancel(runId: RunId)
    suspend fun retry(runId: RunId, fromStepId: StepId? = null)
}

interface RunStore : Closeable {
    suspend fun create(runId: RunId, request: AgentRequest, timestampEpochMillis: Long): RunSnapshot
    suspend fun request(runId: RunId): AgentRequest
    suspend fun append(
        runId: RunId,
        eventFactory: (sequence: Long, timestampEpochMillis: Long) -> AgentEvent,
    ): AgentEvent
    fun observe(runId: RunId): Flow<AgentEvent>
    suspend fun events(runId: RunId): List<AgentEvent>
    suspend fun snapshot(runId: RunId): RunSnapshot
    suspend fun recoverableRuns(): List<RunId>
    suspend fun sessionHistory(sessionId: SessionId, limit: Int = 20): List<SessionTurn>
}

fun interface RunStoreFactory {
    fun create(databaseName: String): RunStore
}

data class AgentSdkConfig(
    val rootAgent: AgentDefinition,
    val workerAgents: List<AgentDefinition> = emptyList(),
    val workflows: List<WorkflowDefinition> = emptyList(),
    val modelProviders: List<ModelProvider> = emptyList(),
    val toolProviders: List<ToolProvider> = emptyList(),
    val guardrails: List<Guardrail> = emptyList(),
    val traceSinks: List<TraceSink> = emptyList(),
    val defaultBudget: RunBudget = RunBudget(),
    val localInferenceConcurrency: Int = 1,
    val ioConcurrency: Int = 3,
    val longTermMemoryEnabled: Boolean = true,
    val databaseName: String = "edge-agent-v1.db",
    val mcpServers: List<McpServerConfig> = emptyList(),
    val a2aAgents: List<A2aAgentConfig> = emptyList(),
    val skillSources: List<SkillSource> = emptyList(),
    val skillSignatureVerifier: SkillSignatureVerifier? = null,
    val skillsEnabled: Boolean = true,
    val agentEngine: AgentEngine? = null,
    val runStoreFactory: RunStoreFactory? = null,
    val memoryService: MemoryService? = null,
    val artifactStore: ArtifactStore? = null,
    val toolRuntime: ToolRuntime? = null,
    val secretStore: SecretStore? = null,
    val remoteWorkerProviders: List<RemoteWorkerProvider> = emptyList(),
) {
    init {
        require(localInferenceConcurrency == 1) { "Agent SDK 1.0 的端侧推理并发固定为 1" }
        require(ioConcurrency in 1..16)
        require(agentEngine != null || modelProviders.isNotEmpty()) {
            "未提供自定义 AgentEngine 时至少需要一个 ModelProvider"
        }
        require(workerAgents.map { it.id }.distinct().size == workerAgents.size)
        require(workflows.map { it.id }.distinct().size == workflows.size)
        require(toolRuntime == null || (toolProviders.isEmpty() && mcpServers.isEmpty() && skillSources.isEmpty())) {
            "自定义 ToolRuntime 不能与 ToolProvider、MCP 或自动 Skill 注册同时配置"
        }
    }
}
