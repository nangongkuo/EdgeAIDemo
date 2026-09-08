package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.ApprovalSnapshot
import com.zjf.edgeai.agent.api.ApprovalState
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunSnapshot
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.RunStore
import com.zjf.edgeai.agent.api.PendingInputSnapshot
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.SessionTurn
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryRunStore(
    private val now: () -> Long = System::currentTimeMillis,
) : RunStore {
    private data class Record(
        val request: AgentRequest,
        var snapshot: RunSnapshot,
        val events: MutableList<AgentEvent> = mutableListOf(),
        val stream: MutableStateFlow<List<AgentEvent>> = MutableStateFlow(emptyList()),
        val mutex: Mutex = Mutex(),
    )

    private val records = ConcurrentHashMap<RunId, Record>()
    @Volatile private var closed = false

    override suspend fun create(
        runId: RunId,
        request: AgentRequest,
        timestampEpochMillis: Long,
    ): RunSnapshot {
        check(!closed) { "RunStore 已关闭" }
        val snapshot = RunSnapshot(
            runId = runId,
            sessionId = request.sessionId,
            state = RunState.CREATED,
            createdAtEpochMillis = timestampEpochMillis,
            updatedAtEpochMillis = timestampEpochMillis,
        )
        check(records.putIfAbsent(runId, Record(request, snapshot)) == null) {
            "Run 已存在: ${runId.value}"
        }
        return snapshot
    }

    override suspend fun request(runId: RunId): AgentRequest = record(runId).request

    override suspend fun append(
        runId: RunId,
        eventFactory: (sequence: Long, timestampEpochMillis: Long) -> AgentEvent,
    ): AgentEvent {
        val record = record(runId)
        return record.mutex.withLock {
            val event = eventFactory(record.events.size.toLong() + 1L, now())
            require(event.runId == runId) { "事件 RunId 不匹配" }
            require(event.sequence == record.events.size.toLong() + 1L) { "事件序号必须单调递增" }
            record.snapshot = reduce(record.snapshot, event)
            record.events += event
            record.stream.value = record.events.toList()
            event
        }
    }

    override fun observe(runId: RunId): Flow<AgentEvent> = flow {
        var emitted = 0
        record(runId).stream.collect { current ->
            current.drop(emitted).forEach { emit(it) }
            emitted = current.size
        }
    }

    override suspend fun events(runId: RunId): List<AgentEvent> =
        record(runId).mutex.withLock { record(runId).events.toList() }

    override suspend fun snapshot(runId: RunId): RunSnapshot =
        record(runId).mutex.withLock { record(runId).snapshot }

    override suspend fun recoverableRuns(): List<RunId> = records.entries
        .filter { !it.value.snapshot.state.terminal && it.value.snapshot.state != RunState.CREATED }
        .map { it.key }
        .sortedBy { it.value }

    override suspend fun sessionHistory(sessionId: SessionId, limit: Int): List<SessionTurn> =
        records.entries.asSequence()
            .filter { it.value.request.sessionId == sessionId }
            .mapNotNull { (runId, record) ->
                val snapshot = record.snapshot
                snapshot.finalOutput?.let {
                    SessionTurn(runId, record.request.input, it, snapshot.updatedAtEpochMillis)
                }
            }
            .sortedBy { it.completedAtEpochMillis }
            .toList()
            .takeLast(limit.coerceIn(1, 100))

    override fun close() {
        closed = true
        records.clear()
    }

    private fun record(runId: RunId): Record =
        records[runId] ?: throw NoSuchElementException("未知 Run: ${runId.value}")

    private fun reduce(snapshot: RunSnapshot, event: AgentEvent): RunSnapshot = when (event) {
        is AgentEvent.RunStateChanged -> snapshot.copy(
            state = event.current,
            eventSequence = event.sequence,
            updatedAtEpochMillis = event.timestampEpochMillis,
        )
        is AgentEvent.RouteSelected -> snapshot.copy(
            route = event.route,
            eventSequence = event.sequence,
            updatedAtEpochMillis = event.timestampEpochMillis,
        )
        is AgentEvent.WorkerStateChanged -> snapshot.copy(
            workers = snapshot.workers.filterNot { it.workerId == event.worker.workerId } + event.worker,
            eventSequence = event.sequence,
            updatedAtEpochMillis = event.timestampEpochMillis,
        )
        is AgentEvent.ApprovalLifecycle -> {
            val request = event.request
            val approval = ApprovalSnapshot(
                approvalId = request.id,
                toolCallId = request.toolCallId,
                capabilityId = request.capabilityId,
                riskLevel = request.riskLevel,
                reason = request.reason,
                argumentsJson = request.argumentsJson,
                state = when (event.decision) {
                    null -> ApprovalState.PENDING
                    com.zjf.edgeai.agent.api.ApprovalDecision.DENY -> ApprovalState.DENIED
                    else -> ApprovalState.APPROVED
                },
            )
            val remaining = snapshot.pendingApprovals.filterNot { it.approvalId == request.id }
            snapshot.copy(
                pendingApprovals = if (event.decision == null) remaining + approval else remaining,
                eventSequence = event.sequence,
                updatedAtEpochMillis = event.timestampEpochMillis,
            )
        }
        is AgentEvent.ArtifactCommitted -> snapshot.copy(
            artifacts = snapshot.artifacts.filterNot { it.id == event.artifact.id } + event.artifact,
            eventSequence = event.sequence,
            updatedAtEpochMillis = event.timestampEpochMillis,
        )
        is AgentEvent.UserInputRequired -> {
            val remaining = snapshot.pendingInputs.filterNot { it.requestId == event.requestId }
            snapshot.copy(
                pendingInputs = if (event.response == null) {
                    remaining + PendingInputSnapshot(event.requestId, event.prompt, event.choices)
                } else remaining,
                eventSequence = event.sequence,
                updatedAtEpochMillis = event.timestampEpochMillis,
            )
        }
        is AgentEvent.Terminal -> snapshot.copy(
            state = event.state,
            pendingApprovals = emptyList(),
            pendingInputs = emptyList(),
            finalOutput = event.output,
            failure = event.failure,
            eventSequence = event.sequence,
            updatedAtEpochMillis = event.timestampEpochMillis,
        )
        else -> snapshot.copy(
            eventSequence = event.sequence,
            updatedAtEpochMillis = event.timestampEpochMillis,
        )
    }
}
