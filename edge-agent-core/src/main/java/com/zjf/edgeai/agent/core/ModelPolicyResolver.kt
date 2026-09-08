package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.ModelPolicy

/** Run 级策略覆盖偏好，但不能突破 Agent 定义中的硬性本地/云端边界。 */
object ModelPolicyResolver {
    fun resolve(agentPolicy: ModelPolicy, requestPolicy: ModelPolicy): ModelPolicy = when (requestPolicy) {
        ModelPolicy.LOCAL_ONLY -> {
            require(agentPolicy != ModelPolicy.CLOUD_ONLY) { "Run 要求 LOCAL_ONLY，但 Agent 要求 CLOUD_ONLY" }
            ModelPolicy.LOCAL_ONLY
        }
        ModelPolicy.CLOUD_ONLY -> {
            require(agentPolicy != ModelPolicy.LOCAL_ONLY) { "Run 要求 CLOUD_ONLY，但 Agent 要求 LOCAL_ONLY" }
            ModelPolicy.CLOUD_ONLY
        }
        ModelPolicy.PREFER_LOCAL -> when (agentPolicy) {
            ModelPolicy.LOCAL_ONLY, ModelPolicy.CLOUD_ONLY -> agentPolicy
            else -> ModelPolicy.PREFER_LOCAL
        }
        ModelPolicy.PREFER_CLOUD -> when (agentPolicy) {
            ModelPolicy.LOCAL_ONLY, ModelPolicy.CLOUD_ONLY -> agentPolicy
            else -> ModelPolicy.PREFER_CLOUD
        }
    }
}
