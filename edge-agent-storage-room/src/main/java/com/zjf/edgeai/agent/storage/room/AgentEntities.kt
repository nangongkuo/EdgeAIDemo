package com.zjf.edgeai.agent.storage.room

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index

@Entity(tableName = "runs")
data class RunEntity(
    @androidx.room.PrimaryKey val runId: String,
    val sessionId: String,
    val requestJson: String,
    val snapshotJson: String,
    val state: String,
    val eventSequence: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    /** 数据保留策略标识；由宿主后续的清理策略解释。 */
    val retentionClass: String = "DEFAULT",
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @androidx.room.PrimaryKey val sessionId: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val compactedSummary: String? = null,
)

@Entity(tableName = "agent_nodes", primaryKeys = ["runId", "nodeId"])
data class AgentNodeEntity(
    val runId: String,
    val nodeId: String,
    val type: String,
    val state: String,
    val payloadJson: String,
)

@Entity(tableName = "worker_runs", primaryKeys = ["runId", "workerId"])
data class WorkerRunEntity(
    val runId: String,
    val workerId: String,
    val parentWorkerId: String?,
    val state: String,
    val depth: Int,
    val payloadJson: String,
)

@Entity(tableName = "steps", primaryKeys = ["runId", "stepId"])
data class StepEntity(
    val runId: String,
    val stepId: String,
    val workerId: String?,
    val state: String,
    val attempt: Int,
    val idempotencyKey: String,
    val payloadJson: String,
)

@Entity(
    tableName = "events",
    primaryKeys = ["runId", "sequence"],
    indices = [Index(value = ["runId", "timestampEpochMillis"])],
)
data class EventEntity(
    val runId: String,
    val sequence: Long,
    val timestampEpochMillis: Long,
    val type: String,
    val payloadJson: String,
)

@Entity(
    tableName = "tool_calls",
    primaryKeys = ["runId", "callId"],
    indices = [Index(value = ["idempotencyKey"], unique = true)],
)
data class ToolCallEntity(
    val runId: String,
    val callId: String,
    val stepId: String,
    val state: String,
    val idempotencyKey: String,
    val idempotent: Boolean,
    val payloadJson: String,
)

@Entity(tableName = "approvals")
data class ApprovalEntity(
    @androidx.room.PrimaryKey val approvalId: String,
    val runId: String,
    val callId: String,
    val state: String,
    val payloadJson: String,
    val updatedAtEpochMillis: Long,
)

@Entity(tableName = "checkpoints", primaryKeys = ["runId", "stepId"])
data class CheckpointEntity(
    val runId: String,
    val stepId: String,
    val safeToReplay: Boolean,
    val payloadJson: String,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "artifacts")
data class ArtifactEntity(
    @androidx.room.PrimaryKey val artifactId: String,
    val runId: String,
    val mimeType: String,
    val path: String,
    val sha256: String,
    val metadataJson: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "memories",
    indices = [Index(value = ["sessionId"]), Index(value = ["expiresAtEpochMillis"])],
)
data class MemoryEntity(
    @androidx.room.PrimaryKey val memoryId: String,
    val sessionId: String?,
    val content: String,
    val normalizedHash: String,
    val source: String,
    val confidence: Double,
    val sensitivity: String,
    val importance: Double,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val metadataJson: String,
)

@Fts4
@Entity(tableName = "memory_fts")
data class MemoryFtsEntity(
    val memoryId: String,
    val content: String,
)
