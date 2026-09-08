package com.zjf.edgeai.agent.capabilities

import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.agent.api.ApprovalRequest
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.Guardrail
import com.zjf.edgeai.agent.api.GuardrailInput
import com.zjf.edgeai.agent.api.GuardrailStage
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.SkillBackend
import com.zjf.edgeai.agent.api.StepId
import com.zjf.edgeai.agent.api.ToolCall
import com.zjf.edgeai.agent.api.ToolCallId
import com.zjf.edgeai.agent.api.ToolResult
import com.zjf.edgeai.agent.api.ToolRuntime
import com.zjf.edgeai.agent.api.TrustLevel
import com.zjf.edgeai.agent.capabilities.sandbox.JavaScriptSandbox
import kotlinx.serialization.json.Json

data class SkillExecutionResult(
    val skillId: String,
    val backend: SkillBackend,
    val content: String,
    val toolResults: List<ToolResult> = emptyList(),
)

/** Skill 的受控执行入口；所有声明式工具步骤仍经过统一 ToolRuntime 策略与审批。 */
class SkillRuntime(
    private val store: SkillStore,
    private val javaScriptSandbox: JavaScriptSandbox,
    private val guardrails: List<Guardrail> = emptyList(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun list(): List<SkillSummary> = store.list()

    suspend fun execute(
        skillId: String,
        runId: RunId,
        sessionId: SessionId,
        toolRuntime: ToolRuntime,
        privacyLevel: PrivacyLevel = PrivacyLevel.PRIVATE,
        inputJson: String = "{}",
        injectedCapabilities: Map<CapabilityId, String> = emptyMap(),
        requestApproval: suspend (ApprovalRequest) -> ApprovalDecision,
        onToolStarted: suspend (ToolCall) -> Unit = {},
    ): SkillExecutionResult {
        val skill = store.load(skillId)
        var safeInstructions = skill.instructions
        guardrails.forEach { guardrail ->
            val decision = guardrail.evaluate(
                GuardrailInput(
                    runId,
                    GuardrailStage.SKILL_CONTENT,
                    safeInstructions,
                    TrustLevel.TRUSTED_LOCAL,
                    privacyLevel,
                    mapOf("skillId" to skillId, "version" to skill.manifest.version),
                )
            )
            require(decision.allowed) { decision.reason ?: "Skill 内容被安全策略拒绝" }
            safeInstructions = decision.content
        }
        val declared = skill.manifest.capabilities
        require(injectedCapabilities.keys.all { it in declared }) {
            "Skill 请求了 Manifest 未声明的注入能力"
        }
        return when (skill.manifest.backend) {
            SkillBackend.INSTRUCTION -> SkillExecutionResult(
                skillId,
                skill.manifest.backend,
                safeInstructions,
            )
            SkillBackend.DECLARATIVE_WORKFLOW -> {
                val workflow = json.decodeFromString(
                    DeclarativeSkillWorkflow.serializer(),
                    safeInstructions,
                )
                require(workflow.steps.all { it.capabilityId in declared }) {
                    "Skill 工作流请求了 Manifest 未声明能力"
                }
                require(workflow.steps.none { it.capabilityId.value.startsWith("skill.") }) {
                    "Skill 1.0 禁止递归或嵌套调用其他 Skill"
                }
                val results = workflow.steps.mapIndexed { index, step ->
                    val call = ToolCall(
                        id = ToolCallId("$skillId:${runId.value}:$index"),
                        runId = runId,
                        stepId = StepId("skill:$skillId:$index"),
                        capabilityId = step.capabilityId,
                        argumentsJson = step.argumentsJson,
                    )
                    toolRuntime.invoke(
                        call = call,
                        sessionId = sessionId,
                        privacyLevel = privacyLevel,
                        allowlist = declared,
                        requestApproval = requestApproval,
                        onExecutionStarted = { onToolStarted(call) },
                    )
                }
                SkillExecutionResult(
                    skillId,
                    skill.manifest.backend,
                    results.joinToString("\n") { it.contentJson },
                    results,
                )
            }
            SkillBackend.JAVASCRIPT_SANDBOX -> {
                val output = javaScriptSandbox.execute(
                    script = safeInstructions,
                    inputJson = inputJson,
                    injectedCapabilities = injectedCapabilities.mapKeys { it.key.value },
                )
                SkillExecutionResult(skillId, skill.manifest.backend, output)
            }
        }
    }
}
