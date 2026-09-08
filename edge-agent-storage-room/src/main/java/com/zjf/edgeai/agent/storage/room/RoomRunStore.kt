package com.zjf.edgeai.agent.storage.room

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.agent.api.ApprovalSnapshot
import com.zjf.edgeai.agent.api.ApprovalState
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunSnapshot
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.RunStore
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.SessionTurn
import com.zjf.edgeai.agent.api.RunStoreFactory
import com.zjf.edgeai.agent.api.PendingInputSnapshot
import com.zjf.edgeai.agent.api.ToolCallState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class RoomRunStoreFactory(
    context: Context,
    private val inMemory: Boolean = false,
) : RunStoreFactory {
    private val appContext = context.applicationContext

    override fun create(databaseName: String): RunStore {
        val builder = if (inMemory) {
            Room.inMemoryDatabaseBuilder(appContext, AgentDatabase::class.java)
        } else {
            Room.databaseBuilder(appContext, AgentDatabase::class.java, databaseName)
        }
        return RoomRunStore(builder.addMigrations(AgentDatabase.MIGRATION_1_2).build())
    }
}

class RoomRunStore(
    private val database: AgentDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val json: Json = Json {
        classDiscriminator = "eventType"
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) : RunStore {
    private val appendMutex = Mutex()
    private val dao = database.runDao()
    private val graphDao = database.executionGraphDao()
    private val recoveryDao = database.recoveryDao()

    override suspend fun create(
        runId: RunId,
        request: AgentRequest,
        timestampEpochMillis: Long,
    ): RunSnapshot {
        val snapshot = RunSnapshot(
            runId = runId,
            sessionId = request.sessionId,
            state = RunState.CREATED,
            createdAtEpochMillis = timestampEpochMillis,
            updatedAtEpochMillis = timestampEpochMillis,
        )
        database.withTransaction {
            dao.insertSession(
                SessionEntity(
                    sessionId = request.sessionId.value,
                    createdAtEpochMillis = timestampEpochMillis,
                    updatedAtEpochMillis = timestampEpochMillis,
                )
            )
            dao.insertRun(
                RunEntity(
                    runId = runId.value,
                    sessionId = request.sessionId.value,
                    requestJson = json.encodeToString(AgentRequest.serializer(), request),
                    snapshotJson = json.encodeToString(RunSnapshot.serializer(), snapshot),
                    state = snapshot.state.name,
                    eventSequence = 0,
                    createdAtEpochMillis = timestampEpochMillis,
                    updatedAtEpochMillis = timestampEpochMillis,
                )
            )
        }
        return snapshot
    }

    override suspend fun request(runId: RunId): AgentRequest = json.decodeFromString(
        AgentRequest.serializer(),
        requireRun(runId).requestJson,
    )

    override suspend fun append(
        runId: RunId,
        eventFactory: (sequence: Long, timestampEpochMillis: Long) -> AgentEvent,
    ): AgentEvent = appendMutex.withLock {
        database.withTransaction {
            val current = requireRun(runId)
            val nextSequence = current.eventSequence + 1
            val timestamp = now()
            val event = eventFactory(nextSequence, timestamp)
            require(event.runId == runId) { "事件 RunId 不匹配" }
            require(event.sequence == nextSequence) { "事件序号必须单调递增" }
            val snapshot = reduce(decodeSnapshot(current), event)
            dao.insertEvent(
                EventEntity(
                    runId = runId.value,
                    sequence = event.sequence,
                    timestampEpochMillis = event.timestampEpochMillis,
                    type = event::class.simpleName.orEmpty(),
                    payloadJson = encodeEvent(event),
                )
            )
            projectExecutionGraph(event)
            dao.updateRun(
                current.copy(
                    snapshotJson = json.encodeToString(RunSnapshot.serializer(), snapshot),
                    state = snapshot.state.name,
                    eventSequence = event.sequence,
                    updatedAtEpochMillis = event.timestampEpochMillis,
                )
            )
            dao.touchSession(current.sessionId, event.timestampEpochMillis)
            event
        }
    }

    override fun observe(runId: RunId): Flow<AgentEvent> = dao.observeEvents(runId.value)
        .map { rows -> rows.map(::decodeEvent) }
        .distinctUntilChanged()
        .mapToEvents()

    override suspend fun events(runId: RunId): List<AgentEvent> =
        dao.events(runId.value).map(::decodeEvent)

    override suspend fun snapshot(runId: RunId): RunSnapshot = decodeSnapshot(requireRun(runId))

    override suspend fun recoverableRuns(): List<RunId> = dao.recoverableRunIds().map(::RunId)

    override suspend fun sessionHistory(sessionId: SessionId, limit: Int): List<SessionTurn> =
        dao.sessionRuns(sessionId.value, limit.coerceIn(1, 100)).asReversed().mapNotNull { entity ->
            val snapshot = decodeSnapshot(entity)
            snapshot.finalOutput?.let { output ->
                SessionTurn(
                    runId = RunId(entity.runId),
                    input = json.decodeFromString(AgentRequest.serializer(), entity.requestJson).input,
                    output = output,
                    completedAtEpochMillis = snapshot.updatedAtEpochMillis,
                )
            }
        }

    override fun close() = database.close()

    private suspend fun requireRun(runId: RunId): RunEntity =
        dao.run(runId.value) ?: throw NoSuchElementException("未知 Run: ${runId.value}")

    private fun decodeSnapshot(entity: RunEntity): RunSnapshot =
        json.decodeFromString(RunSnapshot.serializer(), entity.snapshotJson)

    private fun encodeEvent(event: AgentEvent): String =
        json.encodeToString(AgentEvent.serializer(), event)

    private fun decodeEvent(entity: EventEntity): AgentEvent =
        json.decodeFromString(AgentEvent.serializer(), entity.payloadJson)

    /**
     * Event Journal 是真相源；以下表是与事件同事务更新、可重建的恢复投影。
     * 这样进程死亡后无需解析全部历史即可判断外部副作用是否安全重放。
     */
    private suspend fun projectExecutionGraph(event: AgentEvent) {
        when (event) {
            is AgentEvent.PlanCreated -> event.plan.nodes.forEach { node ->
                graphDao.upsertAgentNode(
                    AgentNodeEntity(
                        runId = event.runId.value,
                        nodeId = node.id,
                        type = node.type.name,
                        state = "PLANNED",
                        payloadJson = json.encodeToString(
                            com.zjf.edgeai.agent.api.PlanNode.serializer(),
                            node,
                        ),
                    )
                )
            }

            is AgentEvent.WorkerStateChanged -> graphDao.upsertWorkerRun(
                WorkerRunEntity(
                    runId = event.runId.value,
                    workerId = event.worker.workerId.value,
                    parentWorkerId = event.worker.parentWorkerId?.value,
                    state = event.worker.state.name,
                    depth = event.worker.depth,
                    payloadJson = json.encodeToString(
                        com.zjf.edgeai.agent.api.WorkerSnapshot.serializer(),
                        event.worker,
                    ),
                )
            )

            is AgentEvent.ToolLifecycle -> {
                val payload = encodeEvent(event)
                graphDao.upsertStep(
                    StepEntity(
                        runId = event.runId.value,
                        stepId = event.call.stepId.value,
                        workerId = event.call.workerId?.value,
                        state = event.state.name,
                        attempt = 1,
                        idempotencyKey = event.call.idempotencyKey,
                        payloadJson = payload,
                    )
                )
                recoveryDao.upsertToolCall(
                    ToolCallEntity(
                        runId = event.runId.value,
                        callId = event.call.id.value,
                        stepId = event.call.stepId.value,
                        state = event.state.name,
                        idempotencyKey = event.call.idempotencyKey,
                        idempotent = event.call.idempotent,
                        payloadJson = payload,
                    )
                )
                if (event.state == ToolCallState.SUCCEEDED) {
                    recoveryDao.upsertCheckpoint(
                        CheckpointEntity(
                            runId = event.runId.value,
                            stepId = event.call.stepId.value,
                            safeToReplay = event.call.idempotent,
                            payloadJson = payload,
                            createdAtEpochMillis = event.timestampEpochMillis,
                        )
                    )
                }
            }

            is AgentEvent.ApprovalLifecycle -> recoveryDao.upsertApproval(
                ApprovalEntity(
                    approvalId = event.request.id.value,
                    runId = event.runId.value,
                    callId = event.request.toolCallId.value,
                    state = when (event.decision) {
                        null -> ApprovalState.PENDING
                        ApprovalDecision.DENY -> ApprovalState.DENIED
                        else -> ApprovalState.APPROVED
                    }.name,
                    payloadJson = encodeEvent(event),
                    updatedAtEpochMillis = event.timestampEpochMillis,
                )
            )

            else -> Unit
        }
    }

    private fun Flow<List<AgentEvent>>.mapToEvents(): Flow<AgentEvent> = kotlinx.coroutines.flow.flow {
        var emittedSequence = 0L
        collect { events ->
            events.asSequence()
                .filter { it.sequence > emittedSequence }
                .forEach {
                    emit(it)
                    emittedSequence = it.sequence
                }
        }
    }

    private fun reduce(snapshot: RunSnapshot, event: AgentEvent): RunSnapshot = when (event) {
        is AgentEvent.RunStateChanged -> snapshot.copy(state = event.current).advanced(event)
        is AgentEvent.RouteSelected -> snapshot.copy(route = event.route).advanced(event)
        is AgentEvent.WorkerStateChanged -> snapshot.copy(
            workers = snapshot.workers.filterNot { it.workerId == event.worker.workerId } + event.worker,
        ).advanced(event)
        is AgentEvent.ApprovalLifecycle -> {
            val state = when (event.decision) {
                null -> ApprovalState.PENDING
                ApprovalDecision.DENY -> ApprovalState.DENIED
                else -> ApprovalState.APPROVED
            }
            val remaining = snapshot.pendingApprovals.filterNot {
                it.approvalId == event.request.id
            }
            val approval = ApprovalSnapshot(
                    approvalId = event.request.id,
                    toolCallId = event.request.toolCallId,
                    capabilityId = event.request.capabilityId,
                    riskLevel = event.request.riskLevel,
                    reason = event.request.reason,
                    argumentsJson = event.request.argumentsJson,
                    state = state,
                    requiredAndroidPermissions = event.request.requiredAndroidPermissions,
                )
            snapshot.copy(
                pendingApprovals = if (event.decision == null) remaining + approval else remaining,
            ).advanced(event)
        }
        is AgentEvent.ArtifactCommitted -> snapshot.copy(
            artifacts = snapshot.artifacts.filterNot { it.id == event.artifact.id } + event.artifact,
        ).advanced(event)
        is AgentEvent.UserInputRequired -> {
            val remaining = snapshot.pendingInputs.filterNot { it.requestId == event.requestId }
            snapshot.copy(
                pendingInputs = if (event.response == null) {
                    remaining + PendingInputSnapshot(event.requestId, event.prompt, event.choices)
                } else remaining,
            ).advanced(event)
        }
        is AgentEvent.Terminal -> snapshot.copy(
            state = event.state,
            pendingApprovals = emptyList(),
            pendingInputs = emptyList(),
            finalOutput = event.output,
            failure = event.failure,
        ).advanced(event)
        else -> snapshot.advanced(event)
    }

    private fun RunSnapshot.advanced(event: AgentEvent): RunSnapshot = copy(
        eventSequence = event.sequence,
        updatedAtEpochMillis = event.timestampEpochMillis,
    )
}
