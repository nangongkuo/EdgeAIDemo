package com.zjf.edgeai.agent.capabilities

import com.zjf.edgeai.agent.api.AgentFailure
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.McpServerConfig
import com.zjf.edgeai.agent.api.RiskLevel
import com.zjf.edgeai.agent.api.Guardrail
import com.zjf.edgeai.agent.api.GuardrailInput
import com.zjf.edgeai.agent.api.GuardrailStage
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.SecretSanitizer
import com.zjf.edgeai.agent.api.SecretStore
import com.zjf.edgeai.agent.api.ToolCall
import com.zjf.edgeai.agent.api.ToolDescriptor
import com.zjf.edgeai.agent.api.ToolExecutor
import com.zjf.edgeai.agent.api.ToolProvider
import com.zjf.edgeai.agent.api.ToolResult
import com.zjf.edgeai.agent.api.TrustLevel
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

class McpToolProvider(
    private val config: McpServerConfig,
    private val secretStore: SecretStore? = null,
    client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val now: () -> Long = System::currentTimeMillis,
    private val cacheTtlMillis: Long = CACHE_TTL,
    private val guardrails: List<Guardrail> = emptyList(),
) : ToolProvider, AutoCloseable {
    private val client = client.newBuilder()
        .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(config.callTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()
    private val descriptors = AtomicReference<Map<CapabilityId, ToolDescriptor>>(emptyMap())
    private val initializationMutex = Mutex()
    private var sessionId: String? = null
    @Volatile private var initialized = false
    private var lastDiscoveryEpochMillis = 0L

    init {
        require(config.transport == "streamable-http" || config.transport == "sse") {
            "Android MCP 仅支持 Streamable HTTP 或兼容 SSE"
        }
        val scheme = config.endpoint.toHttpUrl().scheme
        require(scheme == "https" || config.endpoint.startsWith("http://127.0.0.1") ||
            config.endpoint.startsWith("http://localhost")) { "远程 MCP 必须使用 HTTPS" }
    }

    override suspend fun listTools(): List<ToolDescriptor> {
        val cached = descriptors.get()
        if (cached.isNotEmpty() && now() - lastDiscoveryEpochMillis < cacheTtlMillis) {
            return cached.values.sortedBy { it.capabilityId.value }
        }
        initializeIfNeeded()
        val result = rpc("tools/list", JsonObject(emptyMap()))
        val tools = result.jsonObject["tools"]?.jsonArray ?: JsonArray(emptyList())
        val next = tools.map { element ->
            val tool = element.jsonObject
            val remoteName = requireNotNull(tool["name"]?.jsonPrimitive?.contentOrNull)
            val capability = CapabilityId("mcp.${config.id}.${sanitize(remoteName)}")
            ToolDescriptor(
                capabilityId = capability,
                name = remoteName,
                description = tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchemaJson = tool["inputSchema"]?.toString() ?: "{\"type\":\"object\"}",
                outputSchemaJson = tool["outputSchema"]?.toString(),
                riskLevel = RiskLevel.MEDIUM,
                idempotent = false,
                providerId = "mcp.${config.id}",
                local = false,
            )
        }
        descriptors.set(next.associateBy { it.capabilityId })
        lastDiscoveryEpochMillis = now()
        return next
    }

    override fun executor(capabilityId: CapabilityId): ToolExecutor? {
        val descriptor = descriptors.get()[capabilityId] ?: return null
        return ToolExecutor { call ->
            var safeArguments = SecretSanitizer.redact(call.argumentsJson)
            guardrails.forEach { guardrail ->
                val decision = guardrail.evaluate(
                    GuardrailInput(
                        call.runId,
                        GuardrailStage.CLOUD_EGRESS,
                        safeArguments,
                        TrustLevel.TRUSTED_LOCAL,
                        PrivacyLevel.PRIVATE,
                        mapOf("mcpServerId" to config.id),
                    )
                )
                require(decision.allowed) { decision.reason ?: "MCP 出站参数被安全策略拒绝" }
                safeArguments = decision.content
            }
            val arguments = json.parseToJsonElement(safeArguments)
            val result = rpc(
                "tools/call",
                buildJsonObject {
                    put("name", JsonPrimitive(descriptor.name))
                    put("arguments", arguments)
                },
            )
            val failed = result.jsonObject["isError"]?.jsonPrimitive?.contentOrNull == "true"
            var safeResult = result.toString()
            guardrails.forEach { guardrail ->
                val decision = guardrail.evaluate(
                    GuardrailInput(
                        call.runId,
                        GuardrailStage.MCP_CONTENT,
                        safeResult,
                        TrustLevel.UNTRUSTED_EXTERNAL,
                        PrivacyLevel.PRIVATE,
                        mapOf("mcpServerId" to config.id),
                    )
                )
                require(decision.allowed) { decision.reason ?: "MCP 返回内容被安全策略拒绝" }
                safeResult = decision.content
            }
            ToolResult(
                callId = call.id,
                successful = !failed,
                contentJson = safeResult,
                untrusted = true,
                failure = if (failed) AgentFailure("MCP_TOOL_FAILED", "MCP 工具返回错误", true) else null,
            )
        }
    }

    suspend fun health(): Boolean = runCatching {
        initializeIfNeeded()
        rpc("ping", JsonObject(emptyMap()))
        true
    }.getOrDefault(false)

    private suspend fun initializeIfNeeded() = initializationMutex.withLock {
        if (initialized) return@withLock
        rpcOnce("initialize", initializeParams(), "notifications/initialized")
        initialized = true
    }

    private suspend fun rpc(
        method: String,
        params: JsonElement,
        notificationAfter: String? = null,
    ): JsonElement {
        return try {
            rpcOnce(method, params, notificationAfter)
        } catch (expired: McpSessionExpiredException) {
            if (method == "initialize") throw expired
            initializationMutex.withLock {
                sessionId = null
                initialized = false
                rpcOnce("initialize", initializeParams(), "notifications/initialized")
                initialized = true
            }
            rpcOnce(method, params, notificationAfter)
        }
    }

    private suspend fun rpcOnce(
        method: String,
        params: JsonElement,
        notificationAfter: String? = null,
    ): JsonElement = withContext(Dispatchers.IO) {
        val id = System.nanoTime().toString()
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }
        val requestBuilder = Request.Builder()
            .url(config.endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-06-18")
        sessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }
        applyCredentials(requestBuilder)
        val response = client.newCall(requestBuilder.build()).execute()
        response.use {
            if (it.code == 404 || it.code == 410) throw McpSessionExpiredException()
            if (!it.isSuccessful) throw IOException("MCP HTTP ${it.code}")
            it.header("Mcp-Session-Id")?.let { value -> sessionId = value }
            val raw = it.body?.string().orEmpty()
            val jsonText = if (it.header("Content-Type").orEmpty().startsWith("text/event-stream")) {
                raw.lineSequence().filter { line -> line.startsWith("data:") }
                    .map { line -> line.removePrefix("data:").trim() }
                    .lastOrNull { line -> line.isNotBlank() && line != "[DONE]" }
                    ?: throw IOException("MCP SSE 没有数据")
            } else raw
            val envelope = json.parseToJsonElement(jsonText).jsonObject
            envelope["error"]?.let { error -> throw IOException("MCP JSON-RPC 错误: $error") }
            val result = envelope["result"] ?: JsonObject(emptyMap())
            if (notificationAfter != null) sendNotification(notificationAfter)
            result
        }
    }

    private suspend fun sendNotification(method: String) {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive(method))
        }
        val builder = Request.Builder().url(config.endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("MCP-Protocol-Version", "2025-06-18")
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        applyCredentials(builder)
        client.newCall(builder.build()).execute().use {
            if (!it.isSuccessful) throw IOException("MCP initialized 通知 HTTP ${it.code}")
        }
    }

    private suspend fun applyCredentials(builder: Request.Builder) {
        config.headersSecretId?.let { secretId ->
            val secret = secretStore?.get(secretId) ?: error("MCP 凭证不可用: $secretId")
            secret.lineSequence().filter(String::isNotBlank).forEach { line ->
                val separator = line.indexOf(':')
                require(separator > 0) { "MCP 请求头凭证格式错误" }
                builder.header(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
            }
        }
    }

    private fun initializeParams() = buildJsonObject {
        put("protocolVersion", JsonPrimitive("2025-06-18"))
        put("capabilities", JsonObject(emptyMap()))
        put("clientInfo", buildJsonObject {
            put("name", JsonPrimitive("EdgeAgentSDK"))
            put("version", JsonPrimitive("1.0"))
        })
    }

    override fun close() {
        initialized = false
        sessionId = null
        descriptors.set(emptyMap())
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun sanitize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9._:-]"), "_")

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CACHE_TTL = 5 * 60 * 1_000L
    }

    private class McpSessionExpiredException : IOException("MCP Session 已失效")
}
