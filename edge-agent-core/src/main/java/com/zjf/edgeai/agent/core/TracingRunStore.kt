package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunSnapshot
import com.zjf.edgeai.agent.api.RunStore
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.SessionTurn
import com.zjf.edgeai.agent.api.TraceRecord
import com.zjf.edgeai.agent.api.TraceSink
import kotlinx.coroutines.flow.Flow

/**
 * 将持久事件转换为无正文的结构化 Trace。Event Journal 仍负责完整回放，Trace 仅用于指标和诊断。
 * Sink 故障不会反向破坏 Agent 任务。
 */
class TracingRunStore(
    private val delegate: RunStore,
    private val sinks: List<TraceSink>,
) : RunStore {
    override suspend fun create(runId: RunId, request: AgentRequest, timestampEpochMillis: Long) =
        delegate.create(runId, request, timestampEpochMillis)

    override suspend fun request(runId: RunId): AgentRequest = delegate.request(runId)

    override suspend fun append(
        runId: RunId,
        eventFactory: (sequence: Long, timestampEpochMillis: Long) -> AgentEvent,
    ): AgentEvent {
        val event = delegate.append(runId, eventFactory)
        val trace = event.toSanitizedTrace()
        sinks.forEach { sink -> runCatching { sink.record(trace) } }
        return event
    }

    override fun observe(runId: RunId): Flow<AgentEvent> = delegate.observe(runId)
    override suspend fun events(runId: RunId): List<AgentEvent> = delegate.events(runId)
    override suspend fun snapshot(runId: RunId): RunSnapshot = delegate.snapshot(runId)
    override suspend fun recoverableRuns(): List<RunId> = delegate.recoverableRuns()
    override suspend fun sessionHistory(sessionId: SessionId, limit: Int): List<SessionTurn> =
        delegate.sessionHistory(sessionId, limit)
    override fun close() = delegate.close()

    private fun AgentEvent.toSanitizedTrace(): TraceRecord {
        val (category, attributes, failureCode) = when (this) {
            is AgentEvent.RunStateChanged -> Triple(
                "run", mapOf("previous" to previous?.name.orEmpty(), "current" to current.name), null,
            )
            is AgentEvent.RouteSelected -> Triple("agent", mapOf("route" to route.name), null)
            is AgentEvent.PlanCreated -> Triple("plan", mapOf("nodeCount" to plan.nodes.size.toString()), null)
            is AgentEvent.WorkerStateChanged -> Triple(
                "worker",
                mapOf(
                    "workerId" to worker.workerId.value,
                    "agentId" to worker.agentId.value,
                    "state" to worker.state.name,
                    "depth" to worker.depth.toString(),
                ),
                null,
            )
            is AgentEvent.HandoffLifecycle -> Triple(
                "handoff", mapOf("from" to fromAgentId.value, "to" to toAgentId.value, "state" to state.name), null,
            )
            is AgentEvent.ModelToken -> Triple("model", mapOf("characterCount" to text.length.toString()), null)
            is AgentEvent.ModelReset -> Triple("model", emptyMap(), null)
            is AgentEvent.ToolLifecycle -> Triple(
                "tool",
                mapOf(
                    "callId" to call.id.value,
                    "capabilityId" to call.capabilityId.value,
                    "state" to state.name,
                    "idempotent" to call.idempotent.toString(),
                    "resultBytes" to (result?.contentJson?.encodeToByteArray()?.size ?: 0).toString(),
                ),
                result?.failure?.code,
            )
            is AgentEvent.ApprovalLifecycle -> Triple(
                "approval",
                mapOf(
                    "approvalId" to request.id.value,
                    "capabilityId" to request.capabilityId.value,
                    "risk" to request.riskLevel.name,
                    "decision" to decision?.name.orEmpty(),
                ),
                null,
            )
            is AgentEvent.UserInputRequired -> Triple(
                "input", mapOf("requestId" to requestId, "resolved" to (response != null).toString()), null,
            )
            is AgentEvent.ArtifactCommitted -> Triple(
                "artifact",
                mapOf("artifactId" to artifact.id.value, "mimeType" to artifact.mimeType, "size" to artifact.sizeBytes.toString()),
                null,
            )
            is AgentEvent.MemoryLifecycle -> Triple("memory", mapOf("action" to action), null)
            is AgentEvent.Terminal -> Triple(
                "terminal", mapOf("state" to state.name, "hasOutput" to (output != null).toString()), failure?.code,
            )
        }
        return TraceRecord(
            runId = runId,
            sequence = sequence,
            category = category,
            name = this::class.simpleName.orEmpty(),
            startedAtEpochMillis = timestampEpochMillis,
            finishedAtEpochMillis = timestampEpochMillis,
            attributes = attributes.mapValues { SecretRedactor.redact(it.value).take(512) },
            failureCode = failureCode,
        )
    }
}
