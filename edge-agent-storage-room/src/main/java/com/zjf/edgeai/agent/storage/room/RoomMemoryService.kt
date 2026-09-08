package com.zjf.edgeai.agent.storage.room

import com.zjf.edgeai.agent.api.MemoryCandidate
import com.zjf.edgeai.agent.api.MemoryId
import com.zjf.edgeai.agent.api.MemoryEmbeddingProvider
import com.zjf.edgeai.agent.api.MemoryQuery
import com.zjf.edgeai.agent.api.MemoryRecord
import com.zjf.edgeai.agent.api.MemorySensitivity
import com.zjf.edgeai.agent.api.MemoryService
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.SessionId
import java.security.MessageDigest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class RoomMemoryService(
    database: AgentDatabase,
    private val enabled: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val embeddingProvider: MemoryEmbeddingProvider? = null,
    private val json: Json = Json,
) : MemoryService {
    private val dao = database.memoryDao()

    override suspend fun search(query: MemoryQuery): List<MemoryRecord> {
        if (!enabled()) return emptyList()
        dao.deleteExpired(now())
        val match = query.text.split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}_-]"), "") }
            .filter(String::isNotBlank)
            .take(8)
            .joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
        val timestamp = now()
        val boundedLimit = query.limit.coerceIn(1, 100)
        val querySessionId = query.sessionId?.value
        val embeddings = embeddingProvider
        val ftsRows = if (match.isBlank()) emptyList() else runCatching {
            dao.searchFts(match, timestamp, boundedLimit * 4)
        }.getOrDefault(emptyList())
        // Unicode/CJK 分词在不同 SQLite 构建上能力不同，LIKE 作为确定性的本地补充召回。
        val lexicalRows = (ftsRows + dao.search(
            query.text.take(100), querySessionId, timestamp, boundedLimit * 4,
        )).distinctBy { it.memoryId }
        val semanticRows = if (embeddings == null) emptyList() else {
            dao.candidates(querySessionId, timestamp, (boundedLimit * 12).coerceAtMost(200))
        }
        val rows = (lexicalRows + semanticRows).distinctBy { it.memoryId }
            .filter { entity ->
                (querySessionId == null || entity.sessionId == null || entity.sessionId == querySessionId) &&
                    MemorySensitivity.valueOf(entity.sensitivity).ordinal <= query.maximumSensitivity.ordinal
            }
        val queryEmbedding = embeddings?.embed(query.text)
        return rows.map { entity ->
            val semantic = queryEmbedding?.let { queryVector ->
                cosine(queryVector, requireNotNull(embeddings).embed(entity.content))
            } ?: 0.0
            val lexical = lexicalScore(query.text, entity.content)
            val ageMillis = (timestamp - entity.updatedAtEpochMillis).coerceAtLeast(0)
            val recency = 1.0 / (1.0 + ageMillis.toDouble() / THIRTY_DAYS_MILLIS)
            val score = if (queryEmbedding == null) {
                lexical * 0.45 + entity.importance * 0.30 + entity.confidence * 0.10 + recency * 0.15
            } else {
                semantic * 0.50 + lexical * 0.15 + entity.importance * 0.20 +
                    entity.confidence * 0.05 + recency * 0.10
            }
            entity to score
        }.sortedWith(compareByDescending<Pair<MemoryEntity, Double>> { it.second }
            .thenByDescending { it.first.updatedAtEpochMillis }
            .thenBy { it.first.memoryId })
            .take(boundedLimit)
            .map { toRecord(it.first) }
    }

    override suspend fun commit(candidate: MemoryCandidate): MemoryRecord? {
        if (!enabled() || candidate.sensitivity == MemorySensitivity.FORBIDDEN) return null
        if (containsSecret(candidate.content)) return null
        val timestamp = now()
        if (candidate.expiresAtEpochMillis?.let { it <= timestamp } == true) return null
        val normalized = candidate.content.trim().lowercase().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return null
        val hash = sha256(normalized)
        val existing = dao.byNormalizedHash(hash, candidate.sessionId?.value)
        val entity = if (existing == null) {
            MemoryEntity(
                memoryId = MemoryId.create().value,
                sessionId = candidate.sessionId?.value,
                content = candidate.content.trim(),
                normalizedHash = hash,
                source = json.encodeToString(
                    SetSerializer(String.serializer()),
                    setOf(candidate.sourceRunId.value),
                ),
                confidence = candidate.confidence.coerceIn(0.0, 1.0),
                sensitivity = candidate.sensitivity.name,
                importance = candidate.importance.coerceIn(0.0, 1.0),
                createdAtEpochMillis = timestamp,
                updatedAtEpochMillis = timestamp,
                expiresAtEpochMillis = candidate.expiresAtEpochMillis,
                metadataJson = json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    candidate.metadata,
                ),
            )
        } else {
            val sources = decodeSources(existing.source) + candidate.sourceRunId.value
            existing.copy(
                source = json.encodeToString(SetSerializer(String.serializer()), sources),
                confidence = maxOf(existing.confidence, candidate.confidence.coerceIn(0.0, 1.0)),
                importance = maxOf(existing.importance, candidate.importance.coerceIn(0.0, 1.0)),
                expiresAtEpochMillis = candidate.expiresAtEpochMillis ?: existing.expiresAtEpochMillis,
                updatedAtEpochMillis = timestamp,
            )
        }
        dao.upsertMemory(entity)
        return toRecord(entity).copy(updatedAtEpochMillis = timestamp)
    }

    override suspend fun delete(id: MemoryId): Boolean = dao.deleteMemory(id.value)

    override suspend fun clear() = dao.clearAll()

    private fun toRecord(entity: MemoryEntity) = MemoryRecord(
        id = MemoryId(entity.memoryId),
        content = entity.content,
        sourceRunIds = decodeSources(entity.source).mapTo(linkedSetOf(), ::RunId),
        sessionId = entity.sessionId?.let(::SessionId),
        confidence = entity.confidence,
        importance = entity.importance,
        sensitivity = MemorySensitivity.valueOf(entity.sensitivity),
        createdAtEpochMillis = entity.createdAtEpochMillis,
        updatedAtEpochMillis = entity.updatedAtEpochMillis,
        expiresAtEpochMillis = entity.expiresAtEpochMillis,
        metadata = runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                entity.metadataJson,
            )
        }.getOrDefault(emptyMap()),
    )

    private fun decodeSources(value: String): Set<String> = runCatching {
        json.decodeFromString(SetSerializer(String.serializer()), value)
    }.getOrDefault(emptySet())

    private fun containsSecret(value: String): Boolean = SECRET_PATTERNS.any { it.containsMatchIn(value) }

    private fun lexicalScore(query: String, content: String): Double {
        val queryTerms = query.lowercase().split(TERM_SEPARATOR).filter(String::isNotBlank).toSet()
        if (queryTerms.isEmpty()) return 0.0
        val contentTerms = content.lowercase().split(TERM_SEPARATOR).filter(String::isNotBlank).toSet()
        return queryTerms.count { it in contentTerms }.toDouble() / queryTerms.size
    }

    private fun cosine(left: FloatArray, right: FloatArray): Double {
        if (left.isEmpty() || left.size != right.size) return 0.0
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        left.indices.forEach { index ->
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0
        return (dot / kotlin.math.sqrt(leftNorm * rightNorm)).coerceIn(-1.0, 1.0)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val SECRET_PATTERNS = listOf(
            Regex("(?i)authorization\\s*[:=]\\s*bearer\\s+\\S+"),
            Regex("(?i)(api[_-]?key|password|passwd|access[_-]?token|refresh[_-]?token)\\s*[:=]\\s*\\S+"),
            Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
            Regex("(?i)[\"']?(?:secret|client_secret|credential)[\"']?\\s*[:=]\\s*[\"']?[^\\s,;}\"']{8,}"),
        )
        val TERM_SEPARATOR = Regex("[^\\p{L}\\p{N}_-]+")
        const val THIRTY_DAYS_MILLIS = 30.0 * 24 * 60 * 60 * 1_000
    }
}
