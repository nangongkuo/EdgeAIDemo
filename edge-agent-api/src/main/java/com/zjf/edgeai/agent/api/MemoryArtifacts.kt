package com.zjf.edgeai.agent.api

import kotlinx.serialization.Serializable

@Serializable
enum class MemorySensitivity { PUBLIC, PRIVATE, SENSITIVE, FORBIDDEN }

@Serializable
data class MemoryCandidate(
    val content: String,
    val sourceRunId: RunId,
    val sessionId: SessionId? = null,
    val confidence: Double = 1.0,
    val importance: Double = 0.5,
    val sensitivity: MemorySensitivity = MemorySensitivity.PRIVATE,
    val expiresAtEpochMillis: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class MemoryRecord(
    val id: MemoryId,
    val content: String,
    val sourceRunIds: Set<RunId>,
    val sessionId: SessionId? = null,
    val confidence: Double,
    val importance: Double,
    val sensitivity: MemorySensitivity,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class MemoryQuery(
    val text: String,
    val sessionId: SessionId? = null,
    val limit: Int = 8,
    val maximumSensitivity: MemorySensitivity = MemorySensitivity.PRIVATE,
)

/** 可选语义检索扩展；不配置时 MemoryService 保持纯本地 FTS/时间/重要性排序。 */
fun interface MemoryEmbeddingProvider {
    suspend fun embed(text: String): FloatArray
}

interface MemoryService {
    suspend fun search(query: MemoryQuery): List<MemoryRecord>
    suspend fun commit(candidate: MemoryCandidate): MemoryRecord?
    suspend fun delete(id: MemoryId): Boolean
    suspend fun clear()
}

@Serializable
data class ArtifactRef(
    val id: ArtifactId,
    val runId: RunId,
    val stepId: StepId? = null,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val summary: String? = null,
    val createdAtEpochMillis: Long,
)

interface ArtifactStore {
    suspend fun save(
        runId: RunId,
        stepId: StepId?,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        summary: String? = null,
    ): ArtifactRef

    suspend fun read(ref: ArtifactRef): ByteArray
    suspend fun delete(id: ArtifactId): Boolean
}
