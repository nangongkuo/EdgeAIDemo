package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentDefinition
import com.zjf.edgeai.agent.api.AgentEvent
import com.zjf.edgeai.agent.api.AgentId
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.agent.api.ModelCapabilities
import com.zjf.edgeai.agent.api.ModelId
import com.zjf.edgeai.agent.api.ModelProvider
import com.zjf.edgeai.agent.api.ModelRequest
import com.zjf.edgeai.agent.api.ModelResponse
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.RunState
import com.zjf.edgeai.agent.api.UserContinuation
import com.zjf.edgeai.agent.api.ToolCallState
import com.zjf.edgeai.agent.capabilities.ToolRegistry
import com.zjf.edgeai.agent.capabilities.calendar.AndroidCalendarCapability
import com.zjf.edgeai.agent.capabilities.calendar.AndroidCalendarToolProvider
import com.zjf.edgeai.agent.capabilities.calendar.CalendarActionResolver
import com.zjf.edgeai.agent.capabilities.calendar.CalendarCreateEventArguments
import com.zjf.edgeai.agent.capabilities.calendar.CalendarEventReceipt
import com.zjf.edgeai.agent.capabilities.calendar.CalendarGateway
import com.zjf.edgeai.agent.capabilities.calendar.CalendarPreferenceStore
import com.zjf.edgeai.agent.capabilities.calendar.WritableCalendar
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CalendarActionIntegrationTest {
    @Test
    fun localOnlyCalendarActionCompletesApprovalAndReceiptWithoutModelCall() = runTest {
        val zone = ZoneId.of("Asia/Shanghai")
        val clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), zone)
        val gateway = RecordingCalendarGateway()
        val registry = ToolRegistry(
            listOf(
                AndroidCalendarToolProvider(
                    gateway,
                    object : CalendarPreferenceStore {
                        override fun selectedCalendarId(): Long? = null
                        override fun saveSelectedCalendarId(calendarId: Long) = Unit
                    },
                    clock,
                )
            )
        )
        val provider = NeverCalledProvider()
        val engine = ProviderAgentEngine(listOf(provider), toolRuntime = registry)
        val store = InMemoryRunStore { clock.millis() }
        val root = AgentDefinition(
            AgentId("root"),
            "root",
            "root",
            "未获得成功 ToolResult 前不得声称完成",
            preferredModelId = MODEL_ID,
            capabilityAllowlist = setOf(AndroidCalendarCapability.CREATE_EVENT),
        )
        val scheduler = RunScheduler(
            store,
            engine,
            root,
            emptyList(),
            actionResolvers = listOf(CalendarActionResolver(clock, zone)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val runId = RunId("calendar-run")
        try {
            store.create(
                runId,
                AgentRequest(
                    "帮我定个明天早上八点起床的日程",
                    privacyLevel = PrivacyLevel.LOCAL_ONLY,
                ),
                clock.millis(),
            )
            store.append(runId) { sequence, timestamp ->
                AgentEvent.RunStateChanged(
                    runId,
                    sequence,
                    timestamp,
                    RunState.CREATED,
                    RunState.QUEUED,
                )
            }
            scheduler.enqueue(runId)
            runCurrent()

            val approval = store.snapshot(runId).pendingApprovals.single()
            assertEquals(RunState.WAITING_APPROVAL, store.snapshot(runId).state)
            assertEquals(0, gateway.insertCount)
            scheduler.continueRun(
                runId,
                UserContinuation.Approval(approval.approvalId, ApprovalDecision.APPROVE_ONCE),
            )
            advanceUntilIdle()

            val snapshot = store.snapshot(runId)
            assertEquals(RunState.COMPLETED, snapshot.state)
            assertTrue(snapshot.finalOutput.orEmpty().contains("已创建日程：起床"))
            assertTrue(snapshot.finalOutput.orEmpty().contains("事件 ID：123"))
            assertEquals(1, gateway.insertCount)
            assertEquals(0, provider.calls)
            assertEquals(
                listOf(
                    ToolCallState.PROPOSED,
                    ToolCallState.WAITING_APPROVAL,
                    ToolCallState.RUNNING,
                    ToolCallState.SUCCEEDED,
                ),
                store.events(runId).filterIsInstance<AgentEvent.ToolLifecycle>().map { it.state },
            )
        } finally {
            scheduler.close()
        }
    }

    private class NeverCalledProvider : ModelProvider {
        var calls = 0
        override val id = "local"
        override val models = mapOf(
            MODEL_ID to ModelCapabilities(toolCalling = false, local = true)
        )
        override fun generate(request: ModelRequest): Flow<ModelResponse> = flow {
            calls++
            error("确定性日历动作不应调用模型")
        }
        override fun close() = Unit
    }

    private class RecordingCalendarGateway : CalendarGateway {
        var insertCount = 0
        private val events = mutableMapOf<String, CalendarEventReceipt>()
        override fun writableCalendars() = listOf(WritableCalendar(9, "测试日历", "local"))
        override fun findByMarker(marker: String) = events[marker]
        override fun insertEvent(
            arguments: CalendarCreateEventArguments,
            marker: String,
        ): CalendarEventReceipt {
            insertCount++
            return CalendarEventReceipt(
                eventId = 123,
                eventUri = "content://com.android.calendar/events/123",
                calendarId = requireNotNull(arguments.calendarId),
                title = arguments.title,
                startEpochMillis = arguments.startEpochMillis,
                endEpochMillis = arguments.endEpochMillis,
                timeZone = arguments.timeZone,
                reminderMinutes = arguments.reminderMinutes,
                reused = false,
                userMessage = "已创建日程：起床\n2026-09-09 08:00–08:30\n开始时提醒\n事件 ID：123",
            ).also { events[marker] = it }
        }
    }

    private companion object {
        val MODEL_ID = ModelId("gemma3-local")
    }
}
