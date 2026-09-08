package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.Guardrail
import com.zjf.edgeai.agent.api.GuardrailInput
import com.zjf.edgeai.agent.api.GuardrailStage
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.TrustLevel

class GuardrailRejectedException(message: String) : SecurityException(message)

class GuardrailPipeline(private val guardrails: List<Guardrail>) {
    suspend fun evaluate(
        runId: RunId,
        stage: GuardrailStage,
        content: String,
        trustLevel: TrustLevel,
        privacyLevel: PrivacyLevel,
        metadata: Map<String, String> = emptyMap(),
    ): String {
        if (stage == GuardrailStage.CLOUD_EGRESS && privacyLevel == PrivacyLevel.LOCAL_ONLY) {
            throw GuardrailRejectedException("LOCAL_ONLY 内容禁止发送到云端")
        }
        var current = if (stage == GuardrailStage.CLOUD_EGRESS) {
            SecretRedactor.redact(content)
        } else content
        guardrails.forEach { guardrail ->
            val decision = guardrail.evaluate(
                GuardrailInput(runId, stage, current, trustLevel, privacyLevel, metadata)
            )
            if (!decision.allowed) {
                throw GuardrailRejectedException(decision.reason ?: "内容被安全策略拒绝")
            }
            current = decision.content
        }
        return current
    }
}

/** 默认生产日志/云端外发可复用的凭证脱敏规则。 */
object SecretRedactor {
    fun redact(value: String): String = com.zjf.edgeai.agent.api.SecretSanitizer.redact(value)
}
