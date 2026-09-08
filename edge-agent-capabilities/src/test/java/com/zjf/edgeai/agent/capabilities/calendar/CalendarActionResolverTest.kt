package com.zjf.edgeai.agent.capabilities.calendar

import com.zjf.edgeai.agent.api.ActionResolution
import com.zjf.edgeai.agent.api.ActionResolutionContext
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.SessionTurn
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarActionResolverTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-12-31T00:00:00Z"), zone)
    private val resolver = CalendarActionResolver(clock, zone)

    @Test
    fun `明天八点起床解析为跨年绝对时间和默认值`() = runTest {
        val resolution = resolver.resolve(
            ActionResolutionContext(
                AgentRequest(
                    input = "帮我定个明天早上八点起床的日程",
                    sessionId = SessionId("session"),
                )
            )
        ) as ActionResolution.Prepared

        val arguments = Json.decodeFromString<CalendarCreateEventArguments>(
            resolution.invocation.argumentsJson
        )
        assertEquals("起床", arguments.title)
        assertEquals("2027-01-01T08:00+08:00[Asia/Shanghai]", instantText(arguments.startEpochMillis))
        assertEquals("2027-01-01T08:30+08:00[Asia/Shanghai]", instantText(arguments.endEpochMillis))
        assertEquals(0, arguments.reminderMinutes)
        assertEquals("Asia/Shanghai", arguments.timeZone)
    }

    @Test
    fun `今天下午和后天时间按时段解析`() {
        assertEquals("2026-12-31", resolver.parseDate("今天下午三点").toString())
        assertEquals("15:00", resolver.parseTime("今天下午三点").toString())
        assertEquals("2027-01-02", resolver.parseDate("后天上午10点20分").toString())
        assertEquals("10:20", resolver.parseTime("后天上午10点20分").toString())
    }

    @Test
    fun `规划建议不触发写工具`() = runTest {
        assertNull(
            resolver.resolve(
                ActionResolutionContext(AgentRequest("帮我规划明天的日程"))
            )
        )
    }

    @Test
    fun `缺少日期时间进入用户输入门并可继续解析`() = runTest {
        val missing = resolver.resolve(
            ActionResolutionContext(AgentRequest("帮我定个日程"))
        ) as ActionResolution.NeedsInput
        assertEquals(AndroidCalendarCapability.MISSING_DATE_TIME_REQUEST, missing.requestId)

        val prepared = resolver.resolve(
            ActionResolutionContext(
                request = AgentRequest("帮我定个日程"),
                continuationResponses = mapOf(missing.requestId to "明天早上八点起床"),
            )
        )
        assertTrue(prepared is ActionResolution.Prepared)
    }

    @Test
    fun `定了吗只依据同一会话成功回执回答`() = runTest {
        val withoutReceipt = resolver.resolve(
            ActionResolutionContext(AgentRequest("定了吗"))
        ) as ActionResolution.Answer
        assertEquals("还没有创建日程。", withoutReceipt.output)

        val receipt = "已创建日程：起床\n2027-01-01 08:00–08:30\n开始时提醒\n事件 ID：123"
        val withReceipt = resolver.resolve(
            ActionResolutionContext(
                request = AgentRequest("定了吗"),
                sessionHistory = listOf(
                    SessionTurn(RunId("old"), "创建日程", receipt, clock.millis())
                ),
            )
        ) as ActionResolution.Answer
        assertEquals(receipt, withReceipt.output)
    }

    private fun instantText(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toString()
}
