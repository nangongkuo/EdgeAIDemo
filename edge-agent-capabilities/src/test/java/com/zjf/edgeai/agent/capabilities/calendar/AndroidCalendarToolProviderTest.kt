package com.zjf.edgeai.agent.capabilities.calendar

import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.StepId
import com.zjf.edgeai.agent.api.ToolCall
import com.zjf.edgeai.agent.api.ToolCallId
import com.zjf.edgeai.agent.capabilities.ToolRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCalendarToolProviderTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC)
    private val arguments = CalendarCreateEventArguments(
        title = "起床",
        startEpochMillis = Instant.parse("2026-09-09T00:00:00Z").toEpochMilli(),
        endEpochMillis = Instant.parse("2026-09-09T00:30:00Z").toEpochMilli(),
        timeZone = "Asia/Shanghai",
    )

    @Test
    fun `HIGH 审批前不写入且批准后仅写一次`() = runTest {
        val gateway = FakeCalendarGateway(listOf(WritableCalendar(7, "个人", "test")))
        val registry = ToolRegistry(
            listOf(AndroidCalendarToolProvider(gateway, MemoryPreferenceStore(), clock))
        )
        val call = call()
        var approvals = 0
        val result = registry.invoke(
            call,
            SessionId("session"),
            allowlist = setOf(AndroidCalendarCapability.CREATE_EVENT),
            requestApproval = {
                approvals++
                assertEquals(0, gateway.insertCount)
                assertTrue(it.requiredAndroidPermissions.contains("android.permission.WRITE_CALENDAR"))
                ApprovalDecision.APPROVE_ONCE
            },
        )
        assertTrue(result.successful)
        assertEquals(1, approvals)
        assertEquals(1, gateway.insertCount)
    }

    @Test
    fun `拒绝审批明确不写入`() = runTest {
        val gateway = FakeCalendarGateway(listOf(WritableCalendar(7, "个人", "test")))
        val registry = ToolRegistry(
            listOf(AndroidCalendarToolProvider(gateway, MemoryPreferenceStore(), clock))
        )
        val result = registry.invoke(
            call(),
            SessionId("session"),
            requestApproval = { ApprovalDecision.DENY },
        )
        assertFalse(result.successful)
        assertEquals("TOOL_DENIED", result.failure?.code)
        assertEquals(0, gateway.insertCount)
    }

    @Test
    fun `相同幂等键重放复用原事件`() = runTest {
        val gateway = FakeCalendarGateway(listOf(WritableCalendar(7, "个人", "test")))
        val provider = AndroidCalendarToolProvider(gateway, MemoryPreferenceStore(), clock)
        val executor = requireNotNull(provider.executor(AndroidCalendarCapability.CREATE_EVENT))
        val first = executor.execute(call())
        val second = executor.execute(call())

        assertTrue(first.successful)
        assertTrue(second.successful)
        assertEquals(1, gateway.insertCount)
        assertTrue(second.contentJson.contains("\"reused\":true"))
    }

    @Test
    fun `多个可写日历且无偏好要求选择`() = runTest {
        val gateway = FakeCalendarGateway(
            listOf(
                WritableCalendar(7, "个人", "a"),
                WritableCalendar(8, "工作", "b"),
            )
        )
        val provider = AndroidCalendarToolProvider(gateway, MemoryPreferenceStore(), clock)
        val result = requireNotNull(provider.executor(AndroidCalendarCapability.CREATE_EVENT))
            .execute(call())
        assertFalse(result.successful)
        assertEquals("CALENDAR_SELECTION_REQUIRED", result.failure?.code)
        assertEquals(0, gateway.insertCount)
    }

    @Test
    fun `没有可写日历返回未创建`() = runTest {
        val provider = AndroidCalendarToolProvider(
            FakeCalendarGateway(emptyList()),
            MemoryPreferenceStore(),
            clock,
        )
        val result = requireNotNull(provider.executor(AndroidCalendarCapability.CREATE_EVENT))
            .execute(call())
        assertEquals("CALENDAR_NOT_WRITABLE", result.failure?.code)
        assertTrue(result.failure?.message.orEmpty().startsWith("未创建日程"))
    }

    private fun call() = ToolCall(
        id = ToolCallId("calendar-call"),
        runId = RunId("run"),
        stepId = StepId("step"),
        capabilityId = AndroidCalendarCapability.CREATE_EVENT,
        argumentsJson = Json {
            explicitNulls = false
            encodeDefaults = true
        }.encodeToString(arguments),
        idempotent = false,
        idempotencyKey = "stable-key",
    )

    private class MemoryPreferenceStore : CalendarPreferenceStore {
        private var id: Long? = null
        override fun selectedCalendarId(): Long? = id
        override fun saveSelectedCalendarId(calendarId: Long) { id = calendarId }
    }

    private class FakeCalendarGateway(
        private val calendars: List<WritableCalendar>,
    ) : CalendarGateway {
        private val byMarker = mutableMapOf<String, CalendarEventReceipt>()
        var insertCount = 0
            private set

        override fun writableCalendars(): List<WritableCalendar> = calendars
        override fun findByMarker(marker: String): CalendarEventReceipt? = byMarker[marker]

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
                userMessage = "已创建日程：${arguments.title}\n事件 ID：123",
            ).also { byMarker[marker] = it }
        }
    }
}
