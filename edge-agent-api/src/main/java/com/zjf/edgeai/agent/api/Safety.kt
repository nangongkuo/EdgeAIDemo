package com.zjf.edgeai.agent.api

import kotlinx.serialization.Serializable

@Serializable
enum class TrustLevel { TRUSTED_SYSTEM, TRUSTED_LOCAL, UNTRUSTED_EXTERNAL }

@Serializable
enum class GuardrailStage {
    USER_INPUT,
    MODEL_OUTPUT,
    TOOL_ARGUMENTS,
    TOOL_RESULT,
    SKILL_CONTENT,
    MCP_CONTENT,
    REMOTE_AGENT_CONTENT,
    MEMORY_WRITE,
    CLOUD_EGRESS,
}

@Serializable
data class GuardrailInput(
    val runId: RunId,
    val stage: GuardrailStage,
    val content: String,
    val trustLevel: TrustLevel,
    val privacyLevel: PrivacyLevel,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class GuardrailDecision(
    val allowed: Boolean,
    val content: String,
    val reason: String? = null,
    val redacted: Boolean = false,
)

fun interface Guardrail {
    suspend fun evaluate(input: GuardrailInput): GuardrailDecision
}

@Serializable
data class TraceRecord(
    val runId: RunId,
    val sequence: Long,
    val category: String,
    val name: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
    val failureCode: String? = null,
)

fun interface TraceSink {
    suspend fun record(trace: TraceRecord)
}

interface SecretStore {
    fun put(id: String, value: String)
    fun get(id: String): String?
    fun delete(id: String): Boolean
}

/** 跨 Provider 复用的确定性脱敏器；不依赖日志框架，也不会保留原始命中值。 */
object SecretSanitizer {
    private val patterns = listOf(
        Regex("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;}]+"),
        Regex("(?i)((?:api[_-]?key|password|passwd|access[_-]?token|refresh[_-]?token)\\s*[:=]\\s*)[^\\s,;}]+"),
        Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
        Regex("(?i)([\"']?(?:secret|client_secret|credential)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;}\"']{8,}"),
    )

    fun redact(value: String): String = patterns.fold(value) { current, regex ->
        regex.replace(current) { match -> match.groups[1]?.value.orEmpty() + "<redacted>" }
    }
}
