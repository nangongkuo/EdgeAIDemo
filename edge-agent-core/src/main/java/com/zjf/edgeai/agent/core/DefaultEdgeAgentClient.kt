package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.EdgeAgentClient
import com.zjf.edgeai.agent.api.RunHandle
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunBudget
import com.zjf.edgeai.agent.api.RunSnapshot
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.RunStore
import com.zjf.edgeai.agent.api.StepId
import com.zjf.edgeai.agent.api.UserContinuation
import kotlinx.coroutines.flow.Flow

class DefaultEdgeAgentClient(
    private val store: RunStore,
    private val scheduler: RunScheduler,
    private val defaultBudget: RunBudget = RunBudget(),
    private val now: () -> Long = System::currentTimeMillis,
) : EdgeAgentClient {
    @Volatile private var closed = false

    override suspend fun submit(request: AgentRequest): RunHandle {
        check(!closed) { "EdgeAgentClient 已关闭" }
        val runId = RunId.create()
        val timestamp = now()
        val effectiveRequest = if (request.budget == RunBudget()) {
            request.copy(budget = defaultBudget)
        } else request
        store.create(runId, effectiveRequest, timestamp)
        store.append(runId) { sequence, eventTimestamp ->
            AgentEvent.RunStateChanged(
                runId, sequence, eventTimestamp, RunState.CREATED, RunState.QUEUED,
            )
        }
        scheduler.enqueue(runId)
        return RunHandle(runId, observe(runId))
    }

    override fun observe(runId: RunId): Flow<AgentEvent> = store.observe(runId)

    override suspend fun snapshot(runId: RunId): RunSnapshot = store.snapshot(runId)

    override suspend fun continueRun(runId: RunId, continuation: UserContinuation) =
        scheduler.continueRun(runId, continuation)

    override suspend fun cancel(runId: RunId) = scheduler.cancel(runId)

    override suspend fun retry(runId: RunId, fromStepId: StepId?) {
        val snapshot = store.snapshot(runId)
        check(snapshot.state.terminal || snapshot.state == RunState.PAUSED ||
            snapshot.state == RunState.WAITING_USER_INPUT
        ) { "仅失败、取消、部分完成、暂停或待输入任务可以重试" }
        store.append(runId) { sequence, timestamp ->
            AgentEvent.RunStateChanged(
                runId, sequence, timestamp, snapshot.state, RunState.QUEUED,
                fromStepId?.let { "从 ${it.value} 重试" } ?: "重新执行",
            )
        }
        scheduler.enqueue(runId, fromStepId)
    }

    override fun close() {
        if (closed) return
        closed = true
        scheduler.close()
        store.close()
    }
}
