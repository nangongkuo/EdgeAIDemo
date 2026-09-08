package com.zjf.edgeai.agent.adk

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.LoopAgent
import com.google.adk.kt.agents.ParallelAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.types.Content
import com.zjf.edgeai.agent.api.AgentDefinition
import com.zjf.edgeai.agent.api.AgentEngine
import com.zjf.edgeai.agent.api.AgentEngineEvent
import com.zjf.edgeai.agent.api.AgentEngineRequest
import com.zjf.edgeai.agent.api.AgentFailure
import com.zjf.edgeai.agent.api.ModelProvider
import com.zjf.edgeai.agent.api.ToolRuntime
import com.zjf.edgeai.agent.api.UserContinuation
import com.zjf.edgeai.agent.core.ModelExecutionBroker
import com.zjf.edgeai.agent.core.ModelRouter
import com.zjf.edgeai.agent.core.ModelPolicyResolver
import com.zjf.edgeai.agent.core.ProviderAgentEngine
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ADK 0.8.0 内部适配器。无工具的单 Agent 快速路径交给 ADK Runner；需要项目级审批、
 * 恢复或 Supervisor 持久语义的路径委托给同契约 ProviderAgentEngine。
 */
class AdkAgentEngine(
    providers: List<ModelProvider>,
    private val toolRuntime: ToolRuntime? = null,
    private val broker: ModelExecutionBroker = ModelExecutionBroker(),
) : AgentEngine {
    private val modelRouter = ModelRouter(providers)
    private val durableDelegate = ProviderAgentEngine(providers, broker, toolRuntime)
    private val activeRunners = ConcurrentHashMap<com.zjf.edgeai.agent.api.RunId, Runner>()

    override fun execute(request: AgentEngineRequest): Flow<AgentEngineEvent> = flow {
        if (request is AgentEngineRequest.Supervisor || request is AgentEngineRequest.Workflow || hasTools(request)) {
            durableDelegate.execute(request).collect { emit(it) }
            return@flow
        }
        val single = request as AgentEngineRequest.Single
        try {
            val selection = modelRouter.select(
                policy = ModelPolicyResolver.resolve(
                    single.agent.modelPolicy,
                    single.request.modelPolicy,
                ),
                privacyLevel = single.request.privacyLevel,
                preferredModel = single.agent.preferredModelId,
            )
            val model = AdkModelBridge(
                name = selection.modelId.value,
                runId = single.runId,
                selection = selection,
                provider = modelRouter.provider(selection.providerId),
                broker = broker,
            )
            val adkAgent = LlmAgent.builder()
                .name(single.agent.name.toAdkName())
                .description(single.agent.description)
                .instruction(single.agent.instructions)
                .model(model)
                .maxSteps(single.request.budget.maxStepsPerAgent)
                .disallowTransferToParent(true)
                .disallowTransferToPeers(true)
                .build()
            val runner = InMemoryRunner.builder()
                .agent(adkAgent)
                .appName(APP_NAME)
                .build()
            activeRunners[single.runId] = runner
            var finalText = ""
            runner.runAsync(
                userId = "local-user",
                sessionId = single.request.sessionId.value,
                newMessage = Content.fromText("user", single.request.input),
                stateDelta = emptyMap(),
                runConfig = RunConfig.builder()
                    .maxLlmCalls(single.request.budget.maxStepsPerAgent)
                    .build(),
            ).collect { event ->
                if (event.author != adkAgent.name) return@collect
                if (event.customMetadata?.get("edgeAgentReset") == true) {
                    emit(AgentEngineEvent.Reset)
                    finalText = ""
                    return@collect
                }
                val text = event.content?.parts?.joinToString("") { it.text.orEmpty() }.orEmpty()
                if (event.partial && text.isNotEmpty()) emit(AgentEngineEvent.Token(text))
                if (event.isFinalResponse) finalText = text
                if (event.errorCode != null || event.errorMessage != null) {
                    emit(
                        AgentEngineEvent.Failed(
                            AgentFailure(
                                event.errorCode ?: "ADK_ERROR",
                                event.errorMessage ?: "ADK 执行失败",
                                recoverable = true,
                            )
                        )
                    )
                    return@collect
                }
            }
            emit(AgentEngineEvent.Completed(finalText))
        } finally {
            activeRunners.remove(single.runId)?.close()
        }
    }

    private suspend fun hasTools(request: AgentEngineRequest): Boolean {
        if (toolRuntime == null || request !is AgentEngineRequest.Single) return false
        return toolRuntime.descriptors(request.agent.capabilityAllowlist).isNotEmpty()
    }

    override suspend fun continueRun(
        runId: com.zjf.edgeai.agent.api.RunId,
        continuation: UserContinuation,
    ) = durableDelegate.continueRun(runId, continuation)

    override suspend fun cancel(runId: com.zjf.edgeai.agent.api.RunId) {
        activeRunners.remove(runId)?.close()
        durableDelegate.cancel(runId)
    }

    override fun close() {
        activeRunners.values.forEach { it.close() }
        activeRunners.clear()
        durableDelegate.close()
    }

    private fun String.toAdkName(): String = lowercase()
        .replace(Regex("[^a-z0-9_]"), "_")
        .trim('_')
        .ifBlank { "edge_agent" }

    private companion object { const val APP_NAME = "edge-agent-sdk" }
}

/** 编译期覆盖 ADK 原生工作流和 Agent-as-Tool 结构，避免 0.8.0 升级时静默破坏。 */
object AdkTopologyFactory {
    fun sequential(name: String, agents: List<LlmAgent>): SequentialAgent = SequentialAgent.builder()
        .name(name)
        .subAgents(agents)
        .build()

    fun parallel(name: String, agents: List<LlmAgent>): ParallelAgent = ParallelAgent.builder()
        .name(name)
        .subAgents(agents)
        .build()

    fun loop(name: String, agents: List<LlmAgent>, maxIterations: Int): LoopAgent = LoopAgent.builder()
        .name(name)
        .subAgents(agents)
        .maxIterations(maxIterations)
        .build()

    fun asTool(agent: LlmAgent): AgentTool = AgentTool.builder()
        .agent(agent)
        .skipSummarization(false)
        .build()
}
