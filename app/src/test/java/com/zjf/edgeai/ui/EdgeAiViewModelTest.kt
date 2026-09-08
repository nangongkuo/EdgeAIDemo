package com.zjf.edgeai.ui

import android.net.Uri
import com.zjf.edgeai.agent.api.AgentId
import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.agent.api.ApprovalId
import com.zjf.edgeai.agent.api.ApprovalRequest
import com.zjf.edgeai.agent.api.ApprovalSnapshot
import com.zjf.edgeai.agent.api.ApprovalState
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.EdgeAgentClient
import com.zjf.edgeai.agent.api.ModelPolicy
import com.zjf.edgeai.agent.api.PendingInputSnapshot
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.RiskLevel
import com.zjf.edgeai.agent.api.RunHandle
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunSnapshot
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.StepId
import com.zjf.edgeai.agent.api.ToolCallId
import com.zjf.edgeai.agent.api.UserContinuation
import com.zjf.edgeai.agent.api.WorkerId
import com.zjf.edgeai.agent.api.WorkerSnapshot
import com.zjf.edgeai.agent.litertlm.AgentModelController
import com.zjf.edgeai.runtime.RuntimeBackend
import com.zjf.edgeai.runtime.RuntimeDiagnostics
import com.zjf.edgeai.runtime.RuntimeState
import com.zjf.edgeai.runtime.model.ModelDescriptor
import com.zjf.edgeai.runtime.model.ModelImportEvent
import com.zjf.edgeai.runtime.model.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EdgeAiViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun agentEventsUpdateOneAssistantMessage() = runTest {
        val client = FakeClient().apply {
            events = flowOf(
                token(1, "设备端 "),
                token(2, "Agent"),
                terminal(3, RunState.COMPLETED, "设备端 Agent"),
            )
        }
        val viewModel = EdgeAiViewModel(FakeModelStore(testModel), FakeController(client))

        viewModel.sendPrompt("你好")
        advanceUntilIdle()

        assertEquals("你好", client.requests.single().input)
        assertEquals("设备端 Agent", viewModel.uiState.value.messages.last().text)
        assertFalse(viewModel.uiState.value.messages.last().streaming)
        assertEquals(RunState.COMPLETED, viewModel.uiState.value.agentRunState)
    }

    @Test
    fun modelResetRemovesRejectedAttempt() = runTest {
        val client = FakeClient().apply {
            events = flowOf(
                token(1, "问题复述"),
                AgentEvent.ModelReset(runId, 2, 2),
                token(3, "正确回答"),
                terminal(4, RunState.COMPLETED, "正确回答"),
            )
        }
        val viewModel = EdgeAiViewModel(FakeModelStore(testModel), FakeController(client))

        viewModel.sendPrompt("问题")
        advanceUntilIdle()

        assertEquals("正确回答", viewModel.uiState.value.messages.last().text)
    }

    @Test
    fun terminalFailureIsRendered() = runTest {
        val client = FakeClient().apply {
            events = flowOf(
                AgentEvent.Terminal(
                    runId,
                    1,
                    1,
                    RunState.FAILED,
                    failure = com.zjf.edgeai.agent.api.AgentFailure("FAIL", "native failure"),
                )
            )
        }
        val viewModel = EdgeAiViewModel(FakeModelStore(testModel), FakeController(client))

        viewModel.sendPrompt("hello")
        advanceUntilIdle()

        assertEquals("native failure", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.messages.last().text.contains("native failure"))
    }

    @Test
    fun whitespaceDoesNotSubmitRun() = runTest {
        val client = FakeClient()
        val viewModel = EdgeAiViewModel(FakeModelStore(testModel), FakeController(client))
        viewModel.sendPrompt(" \n\t ")
        advanceUntilIdle()
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun clearConversationClearsReplayState() = runTest {
        val client = FakeClient().apply { events = flowOf(token(1, "answer")) }
        val viewModel = EdgeAiViewModel(FakeModelStore(testModel), FakeController(client))
        viewModel.sendPrompt("hello")
        advanceUntilIdle()
        viewModel.clearConversation()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertTrue(viewModel.uiState.value.timeline.isEmpty())
    }

    @Test
    fun startupInstallsBundledModelAndInitializesController() = runTest {
        val store = FakeModelStore(
            null,
            flowOf(ModelImportEvent.Completed(testModel)),
        )
        val controller = FakeController(FakeClient(), RuntimeState.Unloaded)
        EdgeAiViewModel(store, controller)
        advanceUntilIdle()
        assertEquals(testModel, controller.initializedModel)
        assertEquals(RuntimeBackend.GPU, controller.initializedBackend)
    }

    @Test
    fun supervisorWorkerAndRecoveryStatesAreRendered() = runTest {
        val worker = WorkerSnapshot(
            workerId = WorkerId("worker-1"),
            agentId = AgentId("researcher"),
            objective = "检索并核验",
            state = RunState.COMPLETED,
            depth = 1,
            outputSummary = "已核验",
        )
        val client = FakeClient().apply {
            events = flowOf(
                AgentEvent.RunStateChanged(runId, 1, 1, RunState.RUNNING, RunState.RECOVERING),
                AgentEvent.WorkerStateChanged(runId, 2, 2, worker),
                terminal(3, RunState.COMPLETED, "汇总完成"),
            )
        }
        val viewModel = EdgeAiViewModel(FakeModelStore(testModel), FakeController(client))

        viewModel.sendPrompt("复杂任务")
        advanceUntilIdle()

        assertEquals(worker, viewModel.uiState.value.workers.single())
        assertTrue(viewModel.uiState.value.timeline.any { it.contains("RECOVERING") })
        assertTrue(viewModel.uiState.value.timeline.any { it.contains("Worker researcher") })
    }

    @Test
    fun approvalIsRenderedAndContinued() = runTest {
        val approvalId = ApprovalId("approval-1")
        val capabilityId = CapabilityId("device.send_message")
        val approval = ApprovalSnapshot(
            approvalId = approvalId,
            toolCallId = ToolCallId("call-1"),
            capabilityId = capabilityId,
            riskLevel = RiskLevel.HIGH,
            reason = "将向外部发送消息",
            argumentsJson = "{\"recipient\":\"张三\"}",
            state = ApprovalState.PENDING,
        )
        val request = ApprovalRequest(
            id = approvalId,
            runId = RunId("run"),
            toolCallId = ToolCallId("call-1"),
            capabilityId = capabilityId,
            riskLevel = RiskLevel.HIGH,
            reason = approval.reason,
            argumentsJson = approval.argumentsJson,
            createdAtEpochMillis = 1,
        )
        val client = FakeClient().apply {
            snapshotValue = snapshot(RunState.WAITING_APPROVAL, approvals = listOf(approval))
            events = flowOf(
                AgentEvent.RunStateChanged(runId, 1, 1, RunState.RUNNING, RunState.WAITING_APPROVAL),
                AgentEvent.ApprovalLifecycle(runId, 2, 2, request),
            )
        }
        val viewModel = EdgeAiViewModel(FakeModelStore(testModel), FakeController(client))

        viewModel.sendPrompt("发送消息")
        advanceUntilIdle()
        assertEquals(approval, viewModel.uiState.value.approvals.single())

        viewModel.decideApproval(approvalId, ApprovalDecision.APPROVE_ONCE)
        advanceUntilIdle()
        assertEquals(
            UserContinuation.Approval(approvalId, ApprovalDecision.APPROVE_ONCE),
            client.continuations.single().second,
        )
    }

    @Test
    fun humanInputIsRenderedAndContinued() = runTest {
        val pending = PendingInputSnapshot("input-1", "请选择格式", listOf("Markdown", "JSON"))
        val client = FakeClient().apply {
            snapshotValue = snapshot(RunState.WAITING_USER_INPUT, inputs = listOf(pending))
            events = flowOf(
                AgentEvent.UserInputRequired(runId, 1, 1, pending.requestId, pending.prompt, pending.choices),
            )
        }
        val viewModel = EdgeAiViewModel(FakeModelStore(testModel), FakeController(client))

        viewModel.sendPrompt("生成报告")
        advanceUntilIdle()
        assertEquals(pending, viewModel.uiState.value.pendingInputs.single())

        viewModel.continueInput(pending.requestId, "Markdown")
        advanceUntilIdle()
        assertEquals(
            UserContinuation.Input(pending.requestId, "Markdown"),
            client.continuations.single().second,
        )
    }

    @Test
    fun foregroundHostFollowsRunLifecycle() = runTest {
        val client = FakeClient().apply {
            events = flowOf(terminal(1, RunState.COMPLETED, "完成"))
        }
        val foreground = FakeForegroundHost()
        val viewModel = EdgeAiViewModel(FakeModelStore(testModel), FakeController(client), foreground)
        viewModel.setForegroundExecutionEnabled(true)

        viewModel.sendPrompt("后台执行")
        advanceUntilIdle()

        assertEquals(listOf(client.runId), foreground.started)
        assertTrue(foreground.stopCount >= 1)
    }

    @Test
    fun cancellationPropagatesToClientAndStopsForegroundHost() = runTest {
        val client = FakeClient().apply {
            events = kotlinx.coroutines.flow.flow {
                emit(AgentEvent.RunStateChanged(runId, 1, 1, RunState.QUEUED, RunState.RUNNING))
                awaitCancellation()
            }
        }
        val foreground = FakeForegroundHost()
        val viewModel = EdgeAiViewModel(FakeModelStore(testModel), FakeController(client), foreground)
        viewModel.setForegroundExecutionEnabled(true)
        viewModel.sendPrompt("长任务")

        viewModel.cancelActiveRun()
        advanceUntilIdle()

        assertEquals(listOf(client.runId), client.cancelledRuns)
        assertTrue(foreground.stopCount >= 1)
        assertFalse(viewModel.uiState.value.messages.last().streaming)
    }

    @Test
    fun offlineModeSubmitsStrictLocalPolicy() = runTest {
        val client = FakeClient().apply { events = flowOf(terminal(1, RunState.COMPLETED, "离线完成")) }
        val viewModel = EdgeAiViewModel(FakeModelStore(testModel), FakeController(client))

        viewModel.sendPrompt("不联网")
        advanceUntilIdle()

        assertEquals(ModelPolicy.LOCAL_ONLY, client.requests.single().modelPolicy)
        assertEquals(PrivacyLevel.LOCAL_ONLY, client.requests.single().privacyLevel)
    }

    private class FakeClient : EdgeAgentClient {
        val runId = RunId("run")
        val requests = mutableListOf<AgentRequest>()
        val continuations = mutableListOf<Pair<RunId, UserContinuation>>()
        val cancelledRuns = mutableListOf<RunId>()
        var events: Flow<AgentEvent> = emptyFlow()
        var snapshotValue: RunSnapshot? = null
        override suspend fun submit(request: AgentRequest): RunHandle {
            requests += request
            return RunHandle(runId, events)
        }
        override fun observe(runId: RunId) = events
        override suspend fun snapshot(runId: RunId) = snapshotValue ?: snapshot(RunState.RUNNING)
        override suspend fun continueRun(runId: RunId, continuation: UserContinuation) {
            continuations += runId to continuation
        }
        override suspend fun cancel(runId: RunId) {
            cancelledRuns += runId
        }
        override suspend fun retry(runId: RunId, fromStepId: StepId?) = Unit
        override fun close() = Unit
        fun token(sequence: Long, value: String) = AgentEvent.ModelToken(runId, sequence, sequence, value)
        fun terminal(sequence: Long, state: RunState, output: String) =
            AgentEvent.Terminal(runId, sequence, sequence, state, output)
        fun snapshot(
            state: RunState,
            approvals: List<ApprovalSnapshot> = emptyList(),
            inputs: List<PendingInputSnapshot> = emptyList(),
        ) = RunSnapshot(
            runId = runId,
            sessionId = requests.lastOrNull()?.sessionId ?: com.zjf.edgeai.agent.api.SessionId("s"),
            state = state,
            pendingApprovals = approvals,
            pendingInputs = inputs,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
    }

    private class FakeForegroundHost : AgentForegroundHost {
        val started = mutableListOf<RunId>()
        var stopCount = 0
        override fun start(runId: RunId) { started += runId }
        override fun stop() { stopCount += 1 }
    }

    private class FakeController(
        fakeClient: EdgeAgentClient,
        initialState: RuntimeState = RuntimeState.Ready(RuntimeBackend.GPU, RuntimeBackend.GPU),
    ) : AgentModelController {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<RuntimeState> = mutableState
        override val diagnostics: StateFlow<RuntimeDiagnostics> = MutableStateFlow(
            RuntimeDiagnostics("test", "test", "test", 31, listOf("arm64-v8a"))
        )
        override val client: StateFlow<EdgeAgentClient?> = MutableStateFlow(fakeClient)
        var initializedModel: ModelDescriptor? = null
        var initializedBackend: RuntimeBackend? = null
        override suspend fun initialize(model: ModelDescriptor, preferred: RuntimeBackend) {
            initializedModel = model
            initializedBackend = preferred
            mutableState.value = RuntimeState.Ready(preferred, preferred)
        }
        override suspend fun unload() { mutableState.value = RuntimeState.Unloaded }
        override fun close() = Unit
    }

    private class FakeModelStore(
        selected: ModelDescriptor?,
        private val bundledEvents: Flow<ModelImportEvent> = emptyFlow(),
    ) : ModelStore {
        override val selectedModel: StateFlow<ModelDescriptor?> = MutableStateFlow(selected)
        override fun installBundledModel() = bundledEvents
        override fun importModel(uri: Uri): Flow<ModelImportEvent> = emptyFlow()
        override suspend fun deleteSelectedModel() = Unit
    }

    private fun token(sequence: Long, value: String) =
        AgentEvent.ModelToken(RunId("run"), sequence, sequence, value)

    private fun terminal(sequence: Long, state: RunState, output: String) =
        AgentEvent.Terminal(RunId("run"), sequence, sequence, state, output)

    private companion object {
        val testModel = ModelDescriptor(
            "gemma.litertlm",
            "/tmp/gemma.litertlm",
            1024,
            "abc123",
            1,
        )
    }
}
