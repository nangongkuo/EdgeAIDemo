package com.zjf.edgeai.agent.capabilities

import com.zjf.edgeai.agent.api.A2aAgentConfig
import com.zjf.edgeai.agent.api.AgentFailure
import com.zjf.edgeai.agent.api.Guardrail
import com.zjf.edgeai.agent.api.GuardrailInput
import com.zjf.edgeai.agent.api.GuardrailStage
import com.zjf.edgeai.agent.api.RemoteWorkerProvider
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.SecretStore
import com.zjf.edgeai.agent.api.SecretSanitizer
import com.zjf.edgeai.agent.api.TrustLevel
import com.zjf.edgeai.agent.api.WorkerId
import com.zjf.edgeai.agent.api.WorkerResult
import com.zjf.edgeai.agent.api.WorkerRun
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class A2aRemoteWorkerProvider(
    private val config: A2aAgentConfig,
    private val secretStore: SecretStore? = null,
    client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val guardrails: List<Guardrail> = emptyList(),
) : RemoteWorkerProvider {
    override val id: String = config.id
    private val client = client.newBuilder()
        .callTimeout(config.timeoutMillis, TimeUnit.MILLISECONDS)
        .build()
    private val active = ConcurrentHashMap<WorkerId, okhttp3.Call>()

    init {
        val url = config.endpoint.toHttpUrl()
        require(url.isHttps || url.host == "127.0.0.1" || url.host == "localhost") {
            "远程 A2A 必须使用 HTTPS"
        }
    }

    override suspend fun health(): Boolean = runCatching {
        val request = Request.Builder().url(config.endpoint).head().build()
        client.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    override suspend fun execute(worker: WorkerRun): WorkerResult = withContext(Dispatchers.IO) {
        if (worker.privacyLevel == PrivacyLevel.LOCAL_ONLY) {
            return@withContext WorkerResult(
                workerId = worker.workerId,
                successful = false,
                summary = "本地隐私策略禁止调用远程 Worker",
                untrusted = true,
                failure = AgentFailure("A2A_LOCAL_ONLY_DENIED", "LOCAL_ONLY 任务禁止远程出站", false),
            )
        }
        val safeObjective = guard(
            worker,
            GuardrailStage.CLOUD_EGRESS,
            SecretSanitizer.redact(worker.objective),
            TrustLevel.TRUSTED_LOCAL,
        )
        val safeInput = guard(
            worker,
            GuardrailStage.CLOUD_EGRESS,
            SecretSanitizer.redact(worker.input),
            TrustLevel.TRUSTED_LOCAL,
        )
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(worker.workerId.value))
            put("method", JsonPrimitive("message/send"))
            put("params", buildJsonObject {
                put("message", buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("messageId", JsonPrimitive(worker.workerId.value))
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("kind", JsonPrimitive("text"))
                            put("text", JsonPrimitive("目标：$safeObjective\n\n输入：$safeInput"))
                        })
                    })
                })
                put("metadata", buildJsonObject {
                    put("parentRunId", JsonPrimitive(worker.parentRunId.value))
                    put("maxSteps", JsonPrimitive(worker.budget.maxStepsPerAgent))
                    put("privacyLevel", JsonPrimitive(worker.privacyLevel.name))
                    put("capabilities", buildJsonArray {
                        worker.capabilityAllowlist.sortedBy { it.value }.forEach {
                            add(JsonPrimitive(it.value))
                        }
                    })
                })
            })
        }
        val request = Request.Builder()
            .url(config.endpoint)
            .post(payload.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .apply {
                config.authenticationSecretId?.let { secretId ->
                    header("Authorization", "Bearer ${secretStore?.get(secretId) ?: error("A2A 凭证不可用")}")
                }
            }
            .build()
        val call = client.newCall(request)
        active[worker.workerId] = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("A2A HTTP ${response.code}")
                val envelope = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                envelope["error"]?.let { error ->
                    throw IOException("A2A JSON-RPC 错误: ${SecretSanitizer.redact(error.toString()).take(1_000)}")
                }
                val remote = envelope["result"]?.jsonObject ?: throw IOException("A2A 响应缺少 result")
                val state = remote["status"]?.jsonObject?.get("state")
                    ?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                val directText = textParts(remote["parts"] as? JsonArray)
                val statusText = textParts(
                    remote["status"]?.jsonObject?.get("message")?.jsonObject?.get("parts") as? JsonArray
                )
                val artifactEvidence = (remote["artifacts"] as? JsonArray).orEmpty().flatMap { artifact ->
                    textParts(artifact.jsonObject["parts"] as? JsonArray)
                }
                val summary = (directText + statusText + artifactEvidence).joinToString("\n")
                    .ifBlank { "远程 Agent 已完成但未返回文本结果" }
                val successful = state !in setOf("failed", "canceled", "cancelled", "rejected")
                WorkerResult(
                    workerId = worker.workerId,
                    successful = successful,
                    summary = guard(
                        worker,
                        GuardrailStage.REMOTE_AGENT_CONTENT,
                        summary.take(12_000),
                        TrustLevel.UNTRUSTED_EXTERNAL,
                    ),
                    untrusted = true,
                    evidence = artifactEvidence.map { it.take(2_000) },
                    failure = if (successful) null else AgentFailure(
                        "A2A_REMOTE_FAILED",
                        "远程 Agent 状态为 ${state.ifBlank { "failed" }}",
                        recoverable = true,
                    ),
                )
            }
        } catch (failure: Throwable) {
            WorkerResult(
                workerId = worker.workerId,
                successful = false,
                summary = "远程 Worker 不可用，可回退本地 Worker",
                untrusted = true,
                failure = AgentFailure(
                    "A2A_UNAVAILABLE",
                    failure.message ?: failure::class.simpleName.orEmpty(),
                    recoverable = true,
                ),
            )
        } finally {
            active.remove(worker.workerId)
        }
    }

    override suspend fun cancel(workerId: WorkerId) {
        active.remove(workerId)?.cancel()
    }

    private suspend fun guard(
        worker: WorkerRun,
        stage: GuardrailStage,
        content: String,
        trust: TrustLevel,
    ): String {
        var current = content
        guardrails.forEach { guardrail ->
            val decision = guardrail.evaluate(
                GuardrailInput(
                    worker.parentRunId,
                    stage,
                    current,
                    trust,
                    worker.privacyLevel,
                    mapOf("a2aAgentId" to config.id, "workerId" to worker.workerId.value),
                )
            )
            require(decision.allowed) { decision.reason ?: "A2A 内容被安全策略拒绝" }
            current = decision.content
        }
        return current
    }

    override fun close() {
        active.values.forEach { it.cancel() }
        active.clear()
        client.connectionPool.evictAll()
    }

    private fun textParts(parts: JsonArray?): List<String> = parts.orEmpty().mapNotNull { part ->
        part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
