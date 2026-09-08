package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentEngine
import com.zjf.edgeai.agent.api.AgentEngineEvent
import com.zjf.edgeai.agent.api.AgentEngineRequest
import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentFailure
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunSnapshot
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.RunStore
import com.zjf.edgeai.agent.api.RoutingHint
import com.zjf.edgeai.agent.api.StepId
import com.zjf.edgeai.agent.api.ToolCallState
import com.zjf.edgeai.agent.api.UserContinuation
import com.zjf.edgeai.agent.api.WorkerSnapshot
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** 进程内承载的逻辑 Run 调度器。Room 中的 Run/Event 才是可恢复任务真相源。 */
class RunScheduler(
    private val store: RunStore,
    private val engine: AgentEngine,
    private val rootAgent: com.zjf.edgeai.agent.api.AgentDefinition,
    private val workerAgents: List<com.zjf.edgeai.agent.api.AgentDefinition>,
    workflows: List<com.zjf.edgeai.agent.api.WorkflowDefinition> = emptyList(),
    private val router: RequestRouter = DeterministicRequestRouter(),
    ioConcurrency: Int = 3,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Closeable {
    private sealed interface Command {
        data class Enqueue(val runId: RunId, val retryFrom: StepId? = null) : Command
        data class Cancel(
            val runId: RunId,
            val reply: CompletableDeferred<Result<Unit>>,
        ) : Command
        data class Continue(
            val runId: RunId,
            val continuation: UserContinuation,
            val reply: CompletableDeferred<Result<Unit>>,
        ) : Command
        data object Recover : Command
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val active = ConcurrentHashMap<RunId, Job>()
    private val terminalLocks = ConcurrentHashMap<RunId, Mutex>()
    private val permits = Semaphore(ioConcurrency)
    private val workflows = workflows.associateBy { it.id }

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is Command.Enqueue -> launchRun(command.runId, command.retryFrom)
                    is Command.Cancel -> command.reply.complete(runCatching { cancelNow(command.runId) })
                    is Command.Continue -> command.reply.complete(
                        runCatching { continueNow(command.runId, command.continuation) }
                    )
                    Command.Recover -> recoverNow()
                }
            }
        }
        commands.trySend(Command.Recover)
    }

    fun enqueue(runId: RunId, retryFrom: StepId? = null) {
        check(commands.trySend(Command.Enqueue(runId, retryFrom)).isSuccess) { "调度器已关闭" }
    }

    suspend fun cancel(runId: RunId) {
        val reply = CompletableDeferred<Result<Unit>>()
        commands.send(Command.Cancel(runId, reply))
        reply.await().getOrThrow()
    }

    suspend fun continueRun(runId: RunId, continuation: UserContinuation) {
        val reply = CompletableDeferred<Result<Unit>>()
        commands.send(Command.Continue(runId, continuation, reply))
        reply.await().getOrThrow()
    }

    private fun launchRun(runId: RunId, retryFrom: StepId?) {
        if (active[runId]?.isActive == true) return
        active[runId] = scope.launch {
            permits.withPermit { execute(runId, retryFrom) }
        }.also { job -> job.invokeOnCompletion { active.remove(runId, job) } }
    }

    private suspend fun execute(runId: RunId, retryFrom: StepId?) {
        try {
            val request = store.request(runId)
            transition(runId, RunState.PLANNING, retryFrom?.let { "从检查点 ${it.value} 重试" })
            val route = router.route(request)
            store.append(runId) { sequence, timestamp ->
                AgentEvent.RouteSelected(runId, sequence, timestamp, route.route, route.reason)
            }
            transition(runId, RunState.RUNNING)
            val engineRequest = when (route.route) {
                RoutingHint.SUPERVISOR -> AgentEngineRequest.Supervisor(
                    runId = runId,
                    request = request,
                    supervisor = rootAgent,
                    workers = workerAgents,
                )
                RoutingHint.WORKFLOW -> {
                    val workflowId = requireNotNull(request.workflowId) { "WORKFLOW 路由缺少 workflowId" }
                    AgentEngineRequest.Workflow(
                        runId = runId,
                        request = request,
                        rootAgent = rootAgent,
                        definition = workflows[workflowId]
                            ?: throw IllegalArgumentException("未知工作流: $workflowId"),
                    )
                }
                RoutingHint.SINGLE_AGENT, RoutingHint.AUTO -> AgentEngineRequest.Single(
                    runId = runId,
                    request = request,
                    agent = rootAgent,
                )
            }
            engine.execute(engineRequest).collect { event -> mapEngineEvent(runId, event) }
            val snapshot = store.snapshot(runId)
            if (!snapshot.state.terminal && snapshot.state != RunState.WAITING_APPROVAL &&
                snapshot.state != RunState.WAITING_USER_INPUT
            ) {
                terminal(
                    runId,
                    RunState.FAILED,
                    failure = AgentFailure("ENGINE_NO_TERMINAL", "Agent 引擎结束但未返回终态", true),
                )
            }
        } catch (cancelled: CancellationException) {
            terminal(runId, RunState.CANCELLED)
            throw cancelled
        } catch (failure: Throwable) {
            terminal(
                runId,
                RunState.FAILED,
                failure = AgentFailure(
                    code = "RUN_FAILED",
                    message = failure.message ?: failure::class.simpleName.orEmpty(),
                    recoverable = true,
                ),
            )
        }
    }

    private suspend fun mapEngineEvent(runId: RunId, event: AgentEngineEvent) {
        when (event) {
            AgentEngineEvent.Reset -> store.append(runId) { sequence, timestamp ->
                AgentEvent.ModelReset(runId, sequence, timestamp)
            }
            is AgentEngineEvent.Token -> store.append(runId) { sequence, timestamp ->
                AgentEvent.ModelToken(runId, sequence, timestamp, event.text, thinking = event.thinking)
            }
            is AgentEngineEvent.Plan -> store.append(runId) { sequence, timestamp ->
                AgentEvent.PlanCreated(runId, sequence, timestamp, event.plan)
            }
            is AgentEngineEvent.WorkerStarted -> store.append(runId) { sequence, timestamp ->
                AgentEvent.WorkerStateChanged(
                    runId,
                    sequence,
                    timestamp,
                    WorkerSnapshot(
                        workerId = event.worker.workerId,
                        agentId = event.worker.agentId,
                        parentWorkerId = event.worker.parentWorkerId,
                        objective = event.worker.objective,
                        state = RunState.RUNNING,
                        depth = event.worker.depth,
                    ),
                )
            }
            is AgentEngineEvent.WorkerCompleted -> {
                val existing = store.snapshot(runId).workers.firstOrNull {
                    it.workerId == event.result.workerId
                } ?: error("Worker 完成事件缺少开始事件")
                store.append(runId) { sequence, timestamp ->
                    AgentEvent.WorkerStateChanged(
                        runId,
                        sequence,
                        timestamp,
                        existing.copy(
                            state = if (event.result.successful) RunState.COMPLETED else RunState.FAILED,
                            outputSummary = event.result.summary,
                        ),
                    )
                }
                event.result.artifacts.forEach { artifact ->
                    store.append(runId) { sequence, timestamp ->
                        AgentEvent.ArtifactCommitted(runId, sequence, timestamp, artifact)
                    }
                }
            }
            is AgentEngineEvent.Handoff -> store.append(runId) { sequence, timestamp ->
                AgentEvent.HandoffLifecycle(
                    runId = runId,
                    sequence = sequence,
                    timestampEpochMillis = timestamp,
                    fromAgentId = event.fromAgentId,
                    toAgentId = event.toAgentId,
                    state = event.state,
                    reason = event.reason,
                )
            }
            is AgentEngineEvent.ToolRequested -> store.append(runId) { sequence, timestamp ->
                AgentEvent.ToolLifecycle(
                    runId, sequence, timestamp, event.call, ToolCallState.PROPOSED,
                )
            }
            is AgentEngineEvent.ToolStarted -> store.append(runId) { sequence, timestamp ->
                AgentEvent.ToolLifecycle(
                    runId, sequence, timestamp, event.call, ToolCallState.RUNNING,
                )
            }
            is AgentEngineEvent.ToolCompleted -> store.append(runId) { sequence, timestamp ->
                AgentEvent.ToolLifecycle(
                    runId, sequence, timestamp, event.call, event.state, event.result,
                )
            }.also {
                event.result.artifactRefs.forEach { artifact ->
                    store.append(runId) { sequence, timestamp ->
                        AgentEvent.ArtifactCommitted(runId, sequence, timestamp, artifact)
                    }
                }
            }
            is AgentEngineEvent.ApprovalRequired -> {
                store.append(runId) { sequence, timestamp ->
                    AgentEvent.ApprovalLifecycle(runId, sequence, timestamp, event.request)
                }
                transition(runId, RunState.WAITING_APPROVAL, "等待高风险能力授权")
            }
            is AgentEngineEvent.InputRequired -> {
                store.append(runId) { sequence, timestamp ->
                    AgentEvent.UserInputRequired(
                        runId,
                        sequence,
                        timestamp,
                        event.requestId,
                        event.prompt,
                        event.choices,
                    )
                }
                transition(runId, RunState.WAITING_USER_INPUT, "等待用户补充信息")
            }
            is AgentEngineEvent.MemoryChanged -> store.append(runId) { sequence, timestamp ->
                AgentEvent.MemoryLifecycle(
                    runId,
                    sequence,
                    timestamp,
                    event.memoryId,
                    event.action,
                )
            }
            is AgentEngineEvent.Completed -> terminal(runId, RunState.COMPLETED, event.output)
            is AgentEngineEvent.Failed -> terminal(runId, RunState.FAILED, failure = event.failure)
        }
    }

    private suspend fun continueNow(runId: RunId, continuation: UserContinuation) {
        val snapshot = store.snapshot(runId)
        check(!snapshot.state.terminal) { "终态 Run 不能继续" }
        if (continuation is UserContinuation.Approval) {
            val lifecycle = store.events(runId)
                .filterIsInstance<AgentEvent.ApprovalLifecycle>()
                .lastOrNull { it.request.id == continuation.approvalId }
                ?: throw NoSuchElementException("没有待处理审批")
            check(lifecycle.decision == null) { "审批已经处理" }
            val request = lifecycle.request
            store.append(runId) { sequence, timestamp ->
                AgentEvent.ApprovalLifecycle(
                    runId, sequence, timestamp, request, continuation.decision,
                )
            }
        } else if (continuation is UserContinuation.Input) {
            val pending = store.events(runId)
                .filterIsInstance<AgentEvent.UserInputRequired>()
                .lastOrNull { it.requestId == continuation.requestId }
                ?: throw NoSuchElementException("没有待处理用户输入")
            check(pending.response == null) { "用户输入已经处理" }
            store.append(runId) { sequence, timestamp ->
                pending.copy(
                    sequence = sequence,
                    timestampEpochMillis = timestamp,
                    response = continuation.value,
                )
            }
        }
        transition(runId, RunState.RUNNING, "收到用户继续信息")
        val recoveryInput = continuation as? UserContinuation.Input
        if (recoveryInput?.requestId?.startsWith("recovery:") == true) {
            when (recoveryInput.value.lowercase()) {
                "retry", "重试", "确认重试" -> launchRun(runId, null)
                else -> terminal(
                    runId,
                    RunState.PARTIAL,
                    failure = AgentFailure(
                        "SIDE_EFFECT_UNCONFIRMED",
                        "非幂等外部副作用状态未确认，任务以部分完成结束",
                        false,
                    ),
                )
            }
            return
        }
        engine.continueRun(runId, continuation)
        if (active[runId]?.isActive != true) launchRun(runId, null)
    }

    private suspend fun cancelNow(runId: RunId) {
        engine.cancel(runId)
        active.remove(runId)?.cancel()
        store.snapshot(runId).workers
            .filterNot { it.state.terminal }
            .forEach { worker ->
                store.append(runId) { sequence, timestamp ->
                    AgentEvent.WorkerStateChanged(
                        runId,
                        sequence,
                        timestamp,
                        worker.copy(state = RunState.CANCELLED),
                    )
                }
            }
        terminal(runId, RunState.CANCELLED)
    }

    private suspend fun recoverNow() {
        store.recoverableRuns().forEach { runId ->
            val originalState = store.snapshot(runId).state
            if (originalState == RunState.WAITING_APPROVAL || originalState == RunState.WAITING_USER_INPUT) {
                return@forEach
            }
            val uncertainWrite = store.events(runId)
                .filterIsInstance<AgentEvent.ToolLifecycle>()
                .lastOrNull { it.state == ToolCallState.RUNNING || it.state == ToolCallState.UNKNOWN }
                ?.call
                ?.takeIf { !it.idempotent }
            transition(runId, RunState.RECOVERING, "进程恢复")
            if (uncertainWrite != null) {
                val requestId = "recovery:${uncertainWrite.id.value}"
                store.append(runId) { sequence, timestamp ->
                    AgentEvent.UserInputRequired(
                        runId,
                        sequence,
                        timestamp,
                        requestId,
                        "非幂等工具 ${uncertainWrite.capabilityId.value} 在进程退出时状态未知。确认重试可能造成重复副作用。",
                        listOf("retry", "mark_partial"),
                    )
                }
                transition(
                    runId,
                    RunState.WAITING_USER_INPUT,
                    "非幂等工具 ${uncertainWrite.capabilityId.value} 状态未知，禁止自动重放",
                )
            } else {
                launchRun(runId, null)
            }
        }
    }

    private suspend fun transition(runId: RunId, target: RunState, reason: String? = null) {
        val current = store.snapshot(runId).state
        if (current == target || current.terminal) return
        store.append(runId) { sequence, timestamp ->
            AgentEvent.RunStateChanged(runId, sequence, timestamp, current, target, reason)
        }
    }

    private suspend fun terminal(
        runId: RunId,
        state: RunState,
        output: String? = null,
        failure: AgentFailure? = null,
    ) {
        val lock = terminalLocks.getOrPut(runId) { Mutex() }
        try {
            lock.withLock {
                val snapshot = runCatching { store.snapshot(runId) }.getOrNull() ?: return@withLock
                if (snapshot.state.terminal) return@withLock
                store.append(runId) { sequence, timestamp ->
                    AgentEvent.Terminal(runId, sequence, timestamp, state, output, failure)
                }
            }
        } finally {
            terminalLocks.remove(runId, lock)
        }
    }

    override fun close() {
        commands.close()
        active.values.forEach(Job::cancel)
        active.clear()
        terminalLocks.clear()
        scope.cancel()
        engine.close()
    }
}
