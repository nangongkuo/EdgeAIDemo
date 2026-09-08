@file:Suppress("DEPRECATION")

package com.zjf.edgeai.agent.litertlm

import android.content.Context
import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.ModelPolicy
import com.zjf.edgeai.agent.api.RoutingHint
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.runtime.EdgeAiRuntime
import com.zjf.edgeai.runtime.GenerationEvent
import com.zjf.edgeai.runtime.RuntimeBackend
import com.zjf.edgeai.runtime.RuntimeDiagnostics
import com.zjf.edgeai.runtime.RuntimeState
import com.zjf.edgeai.runtime.model.ModelDescriptor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/**
 * 旧 [EdgeAiRuntime] 契约的 1.x 兼容实现。
 *
 * 生成请求强制进入新的单 Agent 快速路径；调用方仍只观察旧的 GenerationEvent，
 * 不需要依赖任何 Agent SDK 类型。
 */
@Deprecated(
    message = "请迁移到 EdgeAgentClient；该实现仅用于平滑迁移",
)
class EdgeAgentRuntime internal constructor(
    private val controller: AgentModelController,
) : EdgeAiRuntime {
    constructor(context: Context) : this(LiteRtAgentController(context.applicationContext))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeRun = AtomicReference<RunId?>(null)
    private var sessionId = SessionId.create()

    override val state: StateFlow<RuntimeState> = controller.state
    override val diagnostics: StateFlow<RuntimeDiagnostics> = controller.diagnostics

    override suspend fun initialize(model: ModelDescriptor, preferred: RuntimeBackend) {
        controller.initialize(model, preferred)
    }

    override fun generate(prompt: String): Flow<GenerationEvent> = flow {
        if (prompt.isBlank()) {
            emit(GenerationEvent.Failed("请输入问题"))
            return@flow
        }
        val client = controller.client.value
        if (client == null) {
            emit(GenerationEvent.Failed("请先加载模型"))
            return@flow
        }

        val handle = client.submit(
            AgentRequest(
                input = prompt,
                sessionId = sessionId,
                routingHint = RoutingHint.SINGLE_AGENT,
                modelPolicy = ModelPolicy.LOCAL_ONLY,
            )
        )
        activeRun.set(handle.runId)
        var emittedText = false
        try {
            handle.events.transformWhile { event ->
                emit(event)
                event !is AgentEvent.Terminal
            }.collect { event ->
                when (event) {
                    is AgentEvent.ModelToken -> {
                        emittedText = true
                        emit(GenerationEvent.Delta(event.text))
                    }
                    is AgentEvent.ModelReset -> {
                        emittedText = false
                        emit(GenerationEvent.Reset)
                    }
                    is AgentEvent.Terminal -> when (event.state) {
                        RunState.COMPLETED -> {
                            val output = event.output
                            if (!emittedText && !output.isNullOrEmpty()) {
                                emit(GenerationEvent.Delta(output))
                            }
                            emit(GenerationEvent.Completed(diagnostics.value))
                        }
                        else -> emit(
                            GenerationEvent.Failed(
                                event.failure?.message ?: "Agent 任务${event.state.name.lowercase()}"
                            )
                        )
                    }
                    else -> Unit
                }
            }
        } finally {
            activeRun.updateAndGet { current -> if (current == handle.runId) null else current }
        }
    }

    override fun cancel() {
        val runId = activeRun.get() ?: return
        controller.client.value?.let { client -> scope.launch { client.cancel(runId) } }
    }

    override suspend fun resetConversation() {
        activeRun.get()?.let { controller.client.value?.cancel(it) }
        activeRun.set(null)
        sessionId = SessionId.create()
    }

    override suspend fun unload() {
        resetConversation()
        controller.unload()
    }

    override fun close() {
        cancel()
        controller.close()
        scope.cancel()
    }
}
