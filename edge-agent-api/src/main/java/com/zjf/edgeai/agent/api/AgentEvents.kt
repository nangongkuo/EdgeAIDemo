package com.zjf.edgeai.agent.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface AgentEvent {
    val protocolVersion: Int
    val runId: RunId
    val sequence: Long
    val timestampEpochMillis: Long

    @Serializable
    @SerialName("run_state")
    data class RunStateChanged(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val previous: RunState?,
        val current: RunState,
        val reason: String? = null,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("route")
    data class RouteSelected(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val route: RoutingHint,
        val reason: String,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("plan")
    data class PlanCreated(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val plan: ExecutionPlan,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("worker_state")
    data class WorkerStateChanged(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val worker: WorkerSnapshot,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("handoff")
    data class HandoffLifecycle(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val fromAgentId: AgentId,
        val toAgentId: AgentId,
        val state: HandoffState,
        val reason: String? = null,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("model_token")
    data class ModelToken(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val text: String,
        val agentId: AgentId? = null,
        val workerId: WorkerId? = null,
        val thinking: String? = null,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("model_reset")
    data class ModelReset(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("tool")
    data class ToolLifecycle(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val call: ToolCall,
        val state: ToolCallState,
        val result: ToolResult? = null,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("approval")
    data class ApprovalLifecycle(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val request: ApprovalRequest,
        val decision: ApprovalDecision? = null,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("user_input")
    data class UserInputRequired(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val requestId: String,
        val prompt: String,
        val choices: List<String> = emptyList(),
        val response: String? = null,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("artifact")
    data class ArtifactCommitted(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val artifact: ArtifactRef,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("memory")
    data class MemoryLifecycle(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val memoryId: MemoryId,
        val action: String,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent

    @Serializable
    @SerialName("terminal")
    data class Terminal(
        override val runId: RunId,
        override val sequence: Long,
        override val timestampEpochMillis: Long,
        val state: RunState,
        val output: String? = null,
        val failure: AgentFailure? = null,
        override val protocolVersion: Int = EDGE_AGENT_PROTOCOL_VERSION,
    ) : AgentEvent {
        init { require(state.terminal) { "Terminal 事件必须使用终态" } }
    }
}
