package com.zjf.edgeai.agent.api

import android.content.Context
import com.zjf.edgeai.agent.core.DefaultEdgeAgentClient
import com.zjf.edgeai.agent.core.ModelExecutionBroker
import com.zjf.edgeai.agent.core.ProviderAgentEngine
import com.zjf.edgeai.agent.core.RunScheduler
import com.zjf.edgeai.agent.core.TracingRunStore
import com.zjf.edgeai.agent.storage.room.RoomAgentStorage
import com.zjf.edgeai.agent.capabilities.ToolRegistry
import com.zjf.edgeai.agent.capabilities.A2aRemoteWorkerProvider
import com.zjf.edgeai.agent.capabilities.AndroidKeystoreSecretStore
import com.zjf.edgeai.agent.capabilities.McpToolProvider
import com.zjf.edgeai.agent.capabilities.SkillRuntime
import com.zjf.edgeai.agent.capabilities.SkillStore
import com.zjf.edgeai.agent.capabilities.sandbox.IsolatedProcessJavaScriptSandbox
import java.io.File

object EdgeAgentSdk {
    @JvmStatic
    fun create(context: Context, config: AgentSdkConfig): EdgeAgentClient {
        val defaultStorage = if (config.runStoreFactory == null) {
            RoomAgentStorage.create(
                context,
                config.databaseName,
                memoryEnabled = { config.longTermMemoryEnabled },
            )
        } else null
        val durableStore = defaultStorage?.runStore
            ?: requireNotNull(config.runStoreFactory).create(config.databaseName)
        val store = if (config.traceSinks.isEmpty()) durableStore else {
            TracingRunStore(durableStore, config.traceSinks)
        }
        val memoryService = config.memoryService ?: defaultStorage?.memoryService
        val artifactStore = config.artifactStore ?: defaultStorage?.artifactStore
        val needsBuiltInCapabilities = config.agentEngine == null
        val secretStore = config.secretStore ?: if (
            needsBuiltInCapabilities && (config.mcpServers.isNotEmpty() || config.a2aAgents.isNotEmpty())
        ) AndroidKeystoreSecretStore(context.applicationContext) else null
        val mcpProviders = if (config.toolRuntime == null && needsBuiltInCapabilities) {
            config.mcpServers.map { server ->
            McpToolProvider(server, secretStore, guardrails = config.guardrails)
            }
        } else emptyList()
        val remoteWorkers = if (needsBuiltInCapabilities) {
            config.remoteWorkerProviders + config.a2aAgents.map { remote ->
                A2aRemoteWorkerProvider(remote, secretStore, guardrails = config.guardrails)
            }
        } else emptyList()
        val skillRuntime = if (
            needsBuiltInCapabilities && config.toolRuntime == null && config.skillsEnabled
        ) {
            val skillStore = SkillStore(context.applicationContext, signatureVerifier = config.skillSignatureVerifier)
            config.skillSources.forEach { source ->
                when (source) {
                    is SkillSource.Asset -> skillStore.installFromAssets(source.directory)
                    is SkillSource.PrivateDirectory -> {
                        skillStore.installFromPrivateDirectory(File(source.absolutePath))
                    }
                }
            }
            SkillRuntime(
                skillStore,
                IsolatedProcessJavaScriptSandbox(context.applicationContext),
                guardrails = config.guardrails,
            )
        } else null
        val toolRuntime = config.toolRuntime ?: ToolRegistry(
            providers = config.toolProviders + mcpProviders,
            skillRuntime = skillRuntime,
            artifactStore = artifactStore,
        )
        val engine = config.agentEngine ?: ProviderAgentEngine(
            providers = config.modelProviders,
            broker = ModelExecutionBroker(
                localConcurrency = config.localInferenceConcurrency,
                remoteConcurrency = config.ioConcurrency,
            ),
            toolRuntime = toolRuntime,
            memoryService = memoryService,
            guardrails = config.guardrails,
            runStore = store,
            remoteWorkerProviders = remoteWorkers,
        )
        val scheduler = RunScheduler(
            store = store,
            engine = engine,
            rootAgent = config.rootAgent,
            workerAgents = config.workerAgents,
            workflows = config.workflows,
            ioConcurrency = config.ioConcurrency,
        )
        return DefaultEdgeAgentClient(store, scheduler, config.defaultBudget)
    }
}
