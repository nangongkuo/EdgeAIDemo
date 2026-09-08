package com.zjf.edgeai.agent.api

import kotlinx.serialization.Serializable

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class EdgeTool(val name: String = "")

@Serializable
enum class RiskLevel { LOW, MEDIUM, HIGH, BLOCKED }

@Serializable
enum class ToolCallState { PROPOSED, WAITING_APPROVAL, RUNNING, SUCCEEDED, FAILED, DENIED, UNKNOWN }

@Serializable
enum class ApprovalState { PENDING, APPROVED, DENIED, EXPIRED }

@Serializable
enum class ApprovalDecision { APPROVE_ONCE, APPROVE_SESSION, DENY }

@Serializable
data class ToolDescriptor(
    val capabilityId: CapabilityId,
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val outputSchemaJson: String? = null,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val idempotent: Boolean = true,
    val providerId: String = "local",
    val version: String = "1",
    /** false 表示调用会离开设备；LOCAL_ONLY Run 会在审批前直接拒绝。 */
    val local: Boolean = true,
    /** 宿主在提交批准决定前按需申请的 Android 运行时权限。 */
    val requiredAndroidPermissions: Set<String> = emptySet(),
)

@Serializable
data class ToolCall(
    val id: ToolCallId,
    val runId: RunId,
    val stepId: StepId,
    val workerId: WorkerId? = null,
    val capabilityId: CapabilityId,
    val argumentsJson: String,
    val idempotent: Boolean = true,
    val idempotencyKey: String = "${runId.value}:${stepId.value}:${id.value}",
    val displayPreview: String? = null,
)

@Serializable
data class ToolResult(
    val callId: ToolCallId,
    val successful: Boolean,
    val contentJson: String,
    val untrusted: Boolean = false,
    val artifactRefs: List<ArtifactRef> = emptyList(),
    val failure: AgentFailure? = null,
)

@Serializable
data class ApprovalRequest(
    val id: ApprovalId,
    val runId: RunId,
    val toolCallId: ToolCallId,
    val capabilityId: CapabilityId,
    val riskLevel: RiskLevel,
    val reason: String,
    val argumentsJson: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null,
    val requiredAndroidPermissions: Set<String> = emptySet(),
)

@Serializable
data class PreparedToolInvocation(
    val capabilityId: CapabilityId,
    val argumentsJson: String,
    val preview: String,
    val stepId: StepId = StepId("deterministic-action"),
)

data class ActionResolutionContext(
    val request: AgentRequest,
    val continuationResponses: Map<String, String> = emptyMap(),
    val sessionHistory: List<SessionTurn> = emptyList(),
)

sealed interface ActionResolution {
    data class Prepared(val invocation: PreparedToolInvocation) : ActionResolution
    data class NeedsInput(
        val requestId: String,
        val prompt: String,
        val choices: List<String> = emptyList(),
    ) : ActionResolution
    data class Answer(val output: String) : ActionResolution
}

fun interface ActionResolver {
    suspend fun resolve(context: ActionResolutionContext): ActionResolution?
}

fun interface ToolExecutor {
    suspend fun execute(call: ToolCall): ToolResult
}

interface ToolProvider {
    suspend fun listTools(): List<ToolDescriptor>
    fun executor(capabilityId: CapabilityId): ToolExecutor?
}

/** Agent 层调用工具的唯一入口。实现必须在执行器之前完成校验、策略和审批。 */
interface ToolRuntime : AutoCloseable {
    suspend fun descriptors(allowlist: Set<CapabilityId> = emptySet()): List<ToolDescriptor>

    suspend fun invoke(
        call: ToolCall,
        sessionId: SessionId,
        allowlist: Set<CapabilityId> = emptySet(),
        privacyLevel: PrivacyLevel = PrivacyLevel.PRIVATE,
        requestApproval: suspend (ApprovalRequest) -> ApprovalDecision,
        onExecutionStarted: suspend () -> Unit = {},
    ): ToolResult

    suspend fun cancel(runId: RunId) {}
}

@Serializable
enum class SkillBackend { INSTRUCTION, DECLARATIVE_WORKFLOW, JAVASCRIPT_SANDBOX }

@Serializable
data class SkillManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val entryPoint: String = "SKILL.md",
    val backend: SkillBackend = SkillBackend.INSTRUCTION,
    val capabilities: Set<CapabilityId> = emptySet(),
    val resources: List<String> = emptyList(),
    val sha256: String,
    val signature: String? = null,
    val enabled: Boolean = true,
)

sealed interface SkillSource {
    data class Asset(val directory: String) : SkillSource
    data class PrivateDirectory(val absolutePath: String) : SkillSource
}

fun interface SkillSignatureVerifier {
    fun verify(manifest: SkillManifest, digestSha256: String, signature: String): Boolean
}

@Serializable
data class McpServerConfig(
    val id: String,
    val endpoint: String,
    val transport: String = "streamable-http",
    val headersSecretId: String? = null,
    val connectTimeoutMillis: Long = 10_000,
    val callTimeoutMillis: Long = 30_000,
)

@Serializable
data class A2aAgentConfig(
    val id: String,
    val endpoint: String,
    val description: String,
    val authenticationSecretId: String? = null,
    val timeoutMillis: Long = 60_000,
)
