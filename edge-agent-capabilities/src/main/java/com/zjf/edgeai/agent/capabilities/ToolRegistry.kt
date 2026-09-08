package com.zjf.edgeai.agent.capabilities

import com.zjf.edgeai.agent.api.AgentFailure
import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.agent.api.ApprovalId
import com.zjf.edgeai.agent.api.ApprovalRequest
import com.zjf.edgeai.agent.api.ArtifactStore
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.RiskLevel
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.ToolCall
import com.zjf.edgeai.agent.api.ToolDescriptor
import com.zjf.edgeai.agent.api.ToolExecutor
import com.zjf.edgeai.agent.api.ToolProvider
import com.zjf.edgeai.agent.api.ToolResult
import com.zjf.edgeai.agent.api.ToolRuntime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ToolSchemaException(message: String) : IllegalArgumentException(message)

class ToolRegistry(
    private val providers: List<ToolProvider>,
    private val skillRuntime: SkillRuntime? = null,
    private val artifactStore: ArtifactStore? = null,
    private val maxInlineResultChars: Int = 12_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ToolRuntime {
    private data class Registered(
        val descriptor: ToolDescriptor,
        val executor: ToolExecutor? = null,
        val skillId: String? = null,
    )

    private val tools = ConcurrentHashMap<CapabilityId, Registered>()
    private val sessionGrants = ConcurrentHashMap.newKeySet<Pair<SessionId, CapabilityId>>()
    private val discoveryMutex = Mutex()
    @Volatile private var discovered = false

    override suspend fun descriptors(allowlist: Set<CapabilityId>): List<ToolDescriptor> {
        discoverIfNeeded()
        return tools.values.asSequence()
            .map { it.descriptor }
            .filter { allowlist.isEmpty() || it.capabilityId in allowlist }
            .sortedBy { it.capabilityId.value }
            .toList()
    }

    override suspend fun invoke(
        call: ToolCall,
        sessionId: SessionId,
        allowlist: Set<CapabilityId>,
        privacyLevel: PrivacyLevel,
        requestApproval: suspend (ApprovalRequest) -> ApprovalDecision,
        onExecutionStarted: suspend () -> Unit,
    ): ToolResult {
        discoverIfNeeded()
        if (allowlist.isNotEmpty() && call.capabilityId !in allowlist) {
            return denied(call, "Worker 能力白名单不包含 ${call.capabilityId.value}")
        }
        val registered = tools[call.capabilityId] ?: return denied(call, "能力未注册")
        if (privacyLevel == PrivacyLevel.LOCAL_ONLY && !registered.descriptor.local) {
            return denied(call, "LOCAL_ONLY 任务禁止调用远程能力 ${call.capabilityId.value}")
        }
        runCatching {
            validateJsonSchema(registered.descriptor.inputSchemaJson, call.argumentsJson)
        }.exceptionOrNull()?.let { failure ->
            return ToolResult(
                callId = call.id,
                successful = false,
                contentJson = json.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(
                        mapOf(
                            "error" to JsonPrimitive("TOOL_SCHEMA_INVALID"),
                            "message" to JsonPrimitive(failure.message.orEmpty().take(1_000)),
                            "repairable" to JsonPrimitive(true),
                        )
                    ),
                ),
                failure = AgentFailure(
                    "TOOL_SCHEMA_INVALID",
                    failure.message ?: "工具参数不符合 JSON Schema",
                    recoverable = true,
                ),
            )
        }
        when (registered.descriptor.riskLevel) {
            RiskLevel.BLOCKED -> return denied(call, "能力策略明确禁止执行")
            RiskLevel.LOW -> Unit
            RiskLevel.MEDIUM -> if ((sessionId to call.capabilityId) !in sessionGrants) {
                val decision = requestApproval(approval(call, registered.descriptor))
                if (decision == ApprovalDecision.DENY) return denied(call, "用户拒绝授权")
                if (decision == ApprovalDecision.APPROVE_SESSION) {
                    sessionGrants += sessionId to call.capabilityId
                }
            }
            RiskLevel.HIGH -> {
                val decision = requestApproval(approval(call, registered.descriptor))
                if (decision == ApprovalDecision.DENY) return denied(call, "用户拒绝授权")
            }
        }
        onExecutionStarted()
        val raw = runCatching {
            registered.skillId?.let { skillId ->
                val skill = requireNotNull(skillRuntime).execute(
                    skillId = skillId,
                    runId = call.runId,
                    sessionId = sessionId,
                    toolRuntime = this,
                    privacyLevel = privacyLevel,
                    inputJson = call.argumentsJson,
                    requestApproval = requestApproval,
                )
                val firstFailure = skill.toolResults.firstOrNull { !it.successful }?.failure
                ToolResult(
                    callId = call.id,
                    successful = firstFailure == null,
                    contentJson = json.encodeToString(
                        JsonObject.serializer(),
                        JsonObject(
                            mapOf(
                                "skillId" to JsonPrimitive(skill.skillId),
                                "backend" to JsonPrimitive(skill.backend.name),
                                "content" to JsonPrimitive(skill.content),
                            )
                        ),
                    ),
                    untrusted = skill.toolResults.any { it.untrusted },
                    artifactRefs = skill.toolResults.flatMap { it.artifactRefs },
                    failure = firstFailure,
                )
            } ?: requireNotNull(registered.executor).execute(call)
        }.getOrElse { failure ->
            ToolResult(
                callId = call.id,
                successful = false,
                contentJson = "{}",
                failure = AgentFailure(
                    code = "TOOL_EXECUTION_FAILED",
                    message = failure.message ?: failure::class.simpleName.orEmpty(),
                    recoverable = registered.descriptor.idempotent,
                ),
            )
        }
        registered.descriptor.outputSchemaJson?.let { schema ->
            if (raw.successful) {
                runCatching { validateJsonSchema(schema, raw.contentJson) }
                    .exceptionOrNull()?.let { failure ->
                        return ToolResult(
                            callId = call.id,
                            successful = false,
                            contentJson = "{}",
                            failure = AgentFailure(
                                "TOOL_RESULT_SCHEMA_INVALID",
                                failure.message ?: "工具结果不符合 JSON Schema",
                                recoverable = registered.descriptor.idempotent,
                            ),
                        )
                    }
            }
        }
        return externalizeLargeResult(call, raw)
    }

    private suspend fun discoverIfNeeded() = discoveryMutex.withLock {
        if (!discovered) {
            providers.forEach { provider ->
                provider.listTools().forEach { descriptor ->
                    require(descriptor.providerId.isNotBlank())
                    val executor = requireNotNull(provider.executor(descriptor.capabilityId)) {
                        "工具 ${descriptor.capabilityId.value} 没有执行器"
                    }
                    check(tools.putIfAbsent(descriptor.capabilityId, Registered(descriptor, executor)) == null) {
                        "重复 CapabilityId: ${descriptor.capabilityId.value}"
                    }
                }
            }
            skillRuntime?.list()?.filter { it.enabled }?.forEach { skill ->
                val capabilityId = CapabilityId("skill.${sanitizeSkillId(skill.id)}")
                val descriptor = ToolDescriptor(
                    capabilityId = capabilityId,
                    name = "skill_${sanitizeSkillId(skill.id).replace('-', '_').replace('.', '_')}",
                    description = "Skill：${skill.name}。${skill.description}",
                    inputSchemaJson = "{\"type\":\"object\"}",
                    riskLevel = RiskLevel.MEDIUM,
                    idempotent = false,
                    providerId = "skill",
                    local = true,
                )
                check(tools.putIfAbsent(capabilityId, Registered(descriptor, skillId = skill.id)) == null) {
                    "Skill CapabilityId 冲突: ${capabilityId.value}"
                }
            }
            discovered = true
        }
    }

    private fun sanitizeSkillId(id: String): String = id.lowercase()
        .replace(Regex("[^a-z0-9._-]"), "_")

    private fun approval(call: ToolCall, descriptor: ToolDescriptor) = ApprovalRequest(
        id = ApprovalId("approval:${call.id.value}"),
        runId = call.runId,
        toolCallId = call.id,
        capabilityId = call.capabilityId,
        riskLevel = descriptor.riskLevel,
        reason = buildString {
            append("${descriptor.name} 需要 ${descriptor.riskLevel} 风险授权")
            call.displayPreview?.takeIf(String::isNotBlank)?.let { append("\n").append(it) }
        },
        argumentsJson = call.argumentsJson.take(4_000),
        createdAtEpochMillis = now(),
        requiredAndroidPermissions = descriptor.requiredAndroidPermissions,
    )

    private suspend fun externalizeLargeResult(call: ToolCall, result: ToolResult): ToolResult {
        if (result.contentJson.length <= maxInlineResultChars || artifactStore == null) return result
        val ref = artifactStore.save(
            runId = call.runId,
            stepId = call.stepId,
            name = "tool-${call.id.value}.json",
            mimeType = "application/json",
            bytes = result.contentJson.encodeToByteArray(),
            summary = result.contentJson.take(1_000),
        )
        return result.copy(
            contentJson = "{\"truncated\":true,\"artifactId\":\"${ref.id.value}\",\"summary\":${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(result.contentJson.take(1_000)))}}",
            artifactRefs = result.artifactRefs + ref,
        )
    }

    private fun denied(call: ToolCall, reason: String) = ToolResult(
        callId = call.id,
        successful = false,
        contentJson = "{}",
        failure = AgentFailure("TOOL_DENIED", reason, recoverable = false),
    )

    override fun close() {
        sessionGrants.clear()
        tools.clear()
        providers.filterIsInstance<AutoCloseable>().forEach { provider ->
            runCatching { provider.close() }
        }
    }

    companion object {
        fun validateJsonSchema(schemaJson: String, valueJson: String) {
            val parser = Json { ignoreUnknownKeys = true }
            val schema = runCatching { parser.parseToJsonElement(schemaJson).jsonObject }
                .getOrElse { throw ToolSchemaException("工具 Schema 不是合法 JSON") }
            val value = runCatching { parser.parseToJsonElement(valueJson) }
                .getOrElse { throw ToolSchemaException("工具参数不是合法 JSON") }
            validateNode(schema, value, "\$")
        }

        private fun validateNode(schema: JsonObject, value: JsonElement, path: String) {
            val expected = schema["type"]?.jsonPrimitive?.contentOrNull
            val typeMatches = when (expected) {
                null -> true
                "object" -> value is JsonObject
                "array" -> value is kotlinx.serialization.json.JsonArray
                "string" -> value is JsonPrimitive && value.isString
                "number" -> value is JsonPrimitive && value.doubleOrNull != null
                "integer" -> value is JsonPrimitive && value.longOrNull != null
                "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
                "null" -> value is kotlinx.serialization.json.JsonNull
                else -> throw ToolSchemaException("$path 使用了不支持的 Schema type: $expected")
            }
            if (!typeMatches) throw ToolSchemaException("$path 类型应为 $expected")
            if (value is JsonObject) {
                val required = schema["required"] as? kotlinx.serialization.json.JsonArray
                required.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.forEach { key ->
                    if (key !in value) throw ToolSchemaException("$path 缺少必填字段 $key")
                }
                val properties = schema["properties"] as? JsonObject
                value.forEach { (key, child) ->
                    val childSchema = properties?.get(key) as? JsonObject
                    if (childSchema != null) validateNode(childSchema, child, "$path.$key")
                    else if (schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false) {
                        throw ToolSchemaException("$path 包含未声明字段 $key")
                    }
                }
            }
        }
    }
}
