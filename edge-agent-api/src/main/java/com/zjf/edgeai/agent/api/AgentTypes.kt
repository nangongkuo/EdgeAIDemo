package com.zjf.edgeai.agent.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryScope { NONE, WORKING, SESSION, LONG_TERM }

@Serializable
enum class DelegationMode { NONE, AGENT_AS_TOOL, HANDOFF }

@Serializable
enum class HandoffState { REQUESTED, ACTIVE, RETURNED, REJECTED }

@Serializable
data class AgentDefinition(
    val id: AgentId,
    val name: String,
    val description: String,
    val instructions: String,
    val modelPolicy: ModelPolicy = ModelPolicy.PREFER_LOCAL,
    val preferredModelId: ModelId? = null,
    val capabilityAllowlist: Set<CapabilityId> = emptySet(),
    val memoryScope: MemoryScope = MemoryScope.SESSION,
    val budget: RunBudget = RunBudget(),
    val delegationMode: DelegationMode = DelegationMode.AGENT_AS_TOOL,
    val childAgents: List<AgentId> = emptyList(),
)

@Serializable
enum class PlanNodeType { AGENT, TOOL, SEQUENTIAL, PARALLEL, LOOP, HUMAN_GATE }

@Serializable
data class PlanNode(
    val id: String,
    val type: PlanNodeType,
    val objective: String,
    val dependsOn: Set<String> = emptySet(),
    val agentId: AgentId? = null,
    val capabilityId: CapabilityId? = null,
    val children: List<String> = emptyList(),
    val maxIterations: Int = 1,
    val outputContractJson: String? = null,
)

@Serializable
data class ExecutionPlan(
    val version: Int = 1,
    val nodes: List<PlanNode>,
    val finalNodeId: String,
)

@Serializable
data class WorkflowDefinition(
    val id: String,
    val plan: ExecutionPlan,
    val agents: List<AgentDefinition> = emptyList(),
)

@Serializable
data class WorkerRun(
    val workerId: WorkerId,
    val parentRunId: RunId,
    val parentWorkerId: WorkerId? = null,
    val agentId: AgentId,
    val objective: String,
    val input: String,
    val capabilityAllowlist: Set<CapabilityId>,
    val budget: RunBudget,
    val privacyLevel: PrivacyLevel = PrivacyLevel.PRIVATE,
    val depth: Int,
    val state: RunState = RunState.CREATED,
    val outputContractJson: String? = null,
)

@Serializable
data class WorkerResult(
    val workerId: WorkerId,
    val successful: Boolean,
    val summary: String,
    val untrusted: Boolean = false,
    val evidence: List<String> = emptyList(),
    val artifacts: List<ArtifactRef> = emptyList(),
    val stateDelta: Map<String, String> = emptyMap(),
    val memoryCandidates: List<MemoryCandidate> = emptyList(),
    val failure: AgentFailure? = null,
)

@Serializable
sealed interface AgentEngineRequest {
    val runId: RunId

    @Serializable
    @SerialName("single")
    data class Single(
        override val runId: RunId,
        val request: AgentRequest,
        val agent: AgentDefinition,
    ) : AgentEngineRequest

    @Serializable
    @SerialName("supervisor")
    data class Supervisor(
        override val runId: RunId,
        val request: AgentRequest,
        val supervisor: AgentDefinition,
        val workers: List<AgentDefinition>,
    ) : AgentEngineRequest

    @Serializable
    @SerialName("workflow")
    data class Workflow(
        override val runId: RunId,
        val request: AgentRequest,
        val rootAgent: AgentDefinition,
        val definition: WorkflowDefinition,
    ) : AgentEngineRequest

    /** 已由受信任的客户端 Resolver 规范化、仍须经 ToolRuntime 审批和执行的动作。 */
    @Serializable
    @SerialName("tool_action")
    data class ToolAction(
        override val runId: RunId,
        val request: AgentRequest,
        val agent: AgentDefinition,
        val invocation: PreparedToolInvocation,
    ) : AgentEngineRequest
}

interface AgentEngine : AutoCloseable {
    fun execute(request: AgentEngineRequest): kotlinx.coroutines.flow.Flow<AgentEngineEvent>
    suspend fun continueRun(runId: RunId, continuation: UserContinuation)
    suspend fun cancel(runId: RunId)
}

interface RemoteWorkerProvider : AutoCloseable {
    val id: String
    suspend fun health(): Boolean
    suspend fun execute(worker: WorkerRun): WorkerResult
    suspend fun cancel(workerId: WorkerId) {}
}

@Serializable
sealed interface AgentEngineEvent {
    @Serializable
    @SerialName("reset")
    data object Reset : AgentEngineEvent

    @Serializable
    @SerialName("token")
    data class Token(val text: String, val thinking: String? = null) : AgentEngineEvent

    @Serializable
    @SerialName("plan")
    data class Plan(val plan: ExecutionPlan) : AgentEngineEvent

    @Serializable
    @SerialName("worker_started")
    data class WorkerStarted(val worker: WorkerRun) : AgentEngineEvent

    @Serializable
    @SerialName("worker_completed")
    data class WorkerCompleted(val result: WorkerResult) : AgentEngineEvent

    @Serializable
    @SerialName("handoff")
    data class Handoff(
        val fromAgentId: AgentId,
        val toAgentId: AgentId,
        val state: HandoffState,
        val reason: String? = null,
    ) : AgentEngineEvent

    @Serializable
    @SerialName("tool_call")
    data class ToolRequested(val call: ToolCall) : AgentEngineEvent

    @Serializable
    @SerialName("tool_started")
    data class ToolStarted(val call: ToolCall) : AgentEngineEvent

    @Serializable
    @SerialName("tool_completed")
    data class ToolCompleted(
        val call: ToolCall,
        val result: ToolResult,
        val state: ToolCallState,
    ) : AgentEngineEvent

    @Serializable
    @SerialName("approval")
    data class ApprovalRequired(val request: ApprovalRequest) : AgentEngineEvent

    @Serializable
    @SerialName("input_required")
    data class InputRequired(
        val requestId: String,
        val prompt: String,
        val choices: List<String> = emptyList(),
    ) : AgentEngineEvent

    @Serializable
    @SerialName("memory_changed")
    data class MemoryChanged(
        val memoryId: MemoryId,
        val action: String,
    ) : AgentEngineEvent

    @Serializable
    @SerialName("completed")
    data class Completed(val output: String) : AgentEngineEvent

    @Serializable
    @SerialName("failed")
    data class Failed(val failure: AgentFailure) : AgentEngineEvent
}
