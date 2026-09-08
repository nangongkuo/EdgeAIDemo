package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.ModelCapabilities
import com.zjf.edgeai.agent.api.ModelId
import com.zjf.edgeai.agent.api.ModelPolicy
import com.zjf.edgeai.agent.api.ModelProvider
import com.zjf.edgeai.agent.api.ModelSelection
import com.zjf.edgeai.agent.api.PrivacyLevel

class ModelRoutingException(message: String) : IllegalStateException(message)

class ModelRouter(
    providers: List<ModelProvider>,
) {
    private val providers = providers.associateBy { it.id }

    suspend fun select(
        policy: ModelPolicy,
        privacyLevel: PrivacyLevel,
        required: (ModelCapabilities) -> Boolean = { true },
        preferredModel: ModelId? = null,
        excludedProviders: Set<String> = emptySet(),
        excludedSelections: Set<Pair<String, ModelId>> = emptySet(),
    ): ModelSelection {
        val candidates = providers.values
            .filterNot { it.id in excludedProviders }
            .filter { it.health() }
            .flatMap { provider -> provider.models.map { (id, caps) -> Triple(provider, id, caps) } }
            .filter { (provider, id, _) -> provider.id to id !in excludedSelections }
            .filter { (_, _, caps) -> required(caps) }
            .filter { (_, _, caps) -> privacyLevel != PrivacyLevel.LOCAL_ONLY || caps.local }

        val policyCandidates = when (policy) {
            ModelPolicy.LOCAL_ONLY -> candidates.filter { it.third.local }
            ModelPolicy.CLOUD_ONLY -> candidates.filterNot { it.third.local }
            ModelPolicy.PREFER_LOCAL, ModelPolicy.PREFER_CLOUD -> candidates
        }
        val selected = policyCandidates.sortedWith(
            compareByDescending<Triple<ModelProvider, ModelId, ModelCapabilities>> {
                it.second == preferredModel
            }.thenByDescending {
                when (policy) {
                    ModelPolicy.LOCAL_ONLY -> it.third.local
                    ModelPolicy.CLOUD_ONLY -> !it.third.local
                    ModelPolicy.PREFER_LOCAL -> it.third.local
                    ModelPolicy.PREFER_CLOUD -> !it.third.local
                }
            }.thenBy { it.first.id }.thenBy { it.second.value }
        ).firstOrNull()
            ?: throw ModelRoutingException("没有满足策略 $policy 和隐私级别 $privacyLevel 的模型")
        return ModelSelection(
            providerId = selected.first.id,
            modelId = selected.second,
            capabilities = selected.third,
            fallback = excludedSelections.isNotEmpty(),
            reason = if (excludedSelections.isEmpty()) {
                "按 $policy、$privacyLevel、首选模型和能力约束选择"
            } else {
                "前序模型失败后按 $policy、$privacyLevel 和能力约束回退"
            },
        )
    }

    fun provider(id: String): ModelProvider =
        providers[id] ?: throw ModelRoutingException("未知模型 Provider: $id")

    fun allProviders(): Collection<ModelProvider> = providers.values
}
