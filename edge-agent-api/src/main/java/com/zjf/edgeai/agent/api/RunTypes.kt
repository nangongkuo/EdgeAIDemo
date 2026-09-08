package com.zjf.edgeai.agent.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val EDGE_AGENT_PROTOCOL_VERSION: Int = 1

@Serializable
enum class RunState {
    CREATED,
    QUEUED,
    PLANNING,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_USER_INPUT,
    PAUSED,
    RECOVERING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED;

    val terminal: Boolean
        get() = this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED
}

@Serializable
enum class RoutingHint { AUTO, SINGLE_AGENT, WORKFLOW, SUPERVISOR }

@Serializable
enum class ModelPolicy { LOCAL_ONLY, PREFER_LOCAL, PREFER_CLOUD, CLOUD_ONLY }

@Serializable
enum class PrivacyLevel { PUBLIC, PRIVATE, LOCAL_ONLY }

@Serializable
data class RunBudget(
    val maxStepsPerAgent: Int = 12,
    val maxWorkers: Int = 4,
    val maxWorkerDepth: Int = 2,
    val maxModelTokens: Int = 8_192,
    val maxToolCalls: Int = 24,
    val timeoutMillis: Long = 120_000,
) {
    init {
        require(maxStepsPerAgent in 1..128)
        require(maxWorkers in 0..32)
        require(maxWorkerDepth in 0..8)
        require(maxModelTokens > 0)
        require(maxToolCalls >= 0)
        require(timeoutMillis > 0)
    }
}

@Serializable
data class AttachmentRef(
    val id: String,
    val mimeType: String,
    val uri: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val localOnly: Boolean = true,
)

@Serializable
data class AgentRequest(
    val input: String,
    val sessionId: SessionId = SessionId.create(),
    val attachments: List<AttachmentRef> = emptyList(),
    val routingHint: RoutingHint = RoutingHint.AUTO,
    val workflowId: String? = null,
    val requestedHandoffAgentId: AgentId? = null,
    val modelPolicy: ModelPolicy = ModelPolicy.PREFER_LOCAL,
    val privacyLevel: PrivacyLevel = PrivacyLevel.PRIVATE,
    val budget: RunBudget = RunBudget(),
    val interactive: Boolean = true,
    val metadata: Map<String, String> = emptyMap(),
) {
    init { require(input.isNotBlank()) { "AgentRequest.input 不能为空" } }
}

@Serializable
data class WorkerSnapshot(
    val workerId: WorkerId,
    val agentId: AgentId,
    val parentWorkerId: WorkerId? = null,
    val objective: String,
    val state: RunState,
    val depth: Int,
    val outputSummary: String? = null,
)

@Serializable
data class ApprovalSnapshot(
    val approvalId: ApprovalId,
    val toolCallId: ToolCallId,
    val capabilityId: CapabilityId,
    val riskLevel: RiskLevel,
    val reason: String,
    val argumentsJson: String,
    val state: ApprovalState,
    val requiredAndroidPermissions: Set<String> = emptySet(),
)

@Serializable
data class PendingInputSnapshot(
    val requestId: String,
    val prompt: String,
    val choices: List<String> = emptyList(),
)

@Serializable
data class RunSnapshot(
    val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    val runId: RunId,
    val sessionId: SessionId,
    val state: RunState,
    val route: RoutingHint = RoutingHint.AUTO,
    val eventSequence: Long = 0,
    val workers: List<WorkerSnapshot> = emptyList(),
    val pendingApprovals: List<ApprovalSnapshot> = emptyList(),
    val pendingInputs: List<PendingInputSnapshot> = emptyList(),
    val artifacts: List<ArtifactRef> = emptyList(),
    val finalOutput: String? = null,
    val failure: AgentFailure? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class SessionTurn(
    val runId: RunId,
    val input: String,
    val output: String,
    val completedAtEpochMillis: Long,
)

@Serializable
data class AgentFailure(
    val code: String,
    val message: String,
    val recoverable: Boolean = false,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
sealed interface UserContinuation {
    @Serializable
    @SerialName("approval")
    data class Approval(
        val approvalId: ApprovalId,
        val decision: ApprovalDecision,
    ) : UserContinuation

    @Serializable
    @SerialName("input")
    data class Input(
        val requestId: String,
        val value: String,
    ) : UserContinuation
}
