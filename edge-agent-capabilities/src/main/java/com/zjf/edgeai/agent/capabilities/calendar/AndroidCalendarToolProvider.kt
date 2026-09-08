package com.zjf.edgeai.agent.capabilities.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.zjf.edgeai.agent.api.ActionResolution
import com.zjf.edgeai.agent.api.ActionResolutionContext
import com.zjf.edgeai.agent.api.ActionResolver
import com.zjf.edgeai.agent.api.AgentFailure
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.PreparedToolInvocation
import com.zjf.edgeai.agent.api.RiskLevel
import com.zjf.edgeai.agent.api.StepId
import com.zjf.edgeai.agent.api.ToolCall
import com.zjf.edgeai.agent.api.ToolDescriptor
import com.zjf.edgeai.agent.api.ToolExecutor
import com.zjf.edgeai.agent.api.ToolProvider
import com.zjf.edgeai.agent.api.ToolResult
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AndroidCalendarCapability {
    val CREATE_EVENT = CapabilityId("android.calendar.create_event")
    const val MISSING_DATE_TIME_REQUEST = "action:calendar:date-time"
}

@Serializable
data class CalendarCreateEventArguments(
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val timeZone: String,
    val reminderMinutes: Int = 0,
    val calendarId: Long? = null,
)

@Serializable
data class CalendarEventReceipt(
    val eventId: Long,
    val eventUri: String,
    val calendarId: Long,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val timeZone: String,
    val reminderMinutes: Int,
    val reused: Boolean,
    val userMessage: String,
)

data class WritableCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
)

interface CalendarGateway {
    fun writableCalendars(): List<WritableCalendar>
    fun findByMarker(marker: String): CalendarEventReceipt?
    fun insertEvent(
        arguments: CalendarCreateEventArguments,
        marker: String,
    ): CalendarEventReceipt
}

interface CalendarPreferenceStore {
    fun selectedCalendarId(): Long?
    fun saveSelectedCalendarId(calendarId: Long)
}

class AndroidCalendarPreferenceStore(context: Context) : CalendarPreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "edge-agent-calendar",
        Context.MODE_PRIVATE,
    )

    override fun selectedCalendarId(): Long? = preferences
        .getLong(KEY_CALENDAR_ID, NO_CALENDAR)
        .takeUnless { it == NO_CALENDAR }

    override fun saveSelectedCalendarId(calendarId: Long) {
        preferences.edit().putLong(KEY_CALENDAR_ID, calendarId).apply()
    }

    private companion object {
        const val KEY_CALENDAR_ID = "selected_calendar_id"
        const val NO_CALENDAR = Long.MIN_VALUE
    }
}

class AndroidCalendarGateway(context: Context) : CalendarGateway {
    private val resolver = context.applicationContext.contentResolver

    override fun writableCalendars(): List<WritableCalendar> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?"
        val arguments = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        return resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            arguments,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
            )
            val accountColumn = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        WritableCalendar(
                            id = cursor.getLong(idColumn),
                            displayName = cursor.getString(nameColumn).orEmpty().ifBlank { "未命名日历" },
                            accountName = cursor.getString(accountColumn).orEmpty(),
                        )
                    )
                }
            }
        }.orEmpty()
    }

    override fun findByMarker(marker: String): CalendarEventReceipt? {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
        )
        return resolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events.CUSTOM_APP_URI}=?",
            arrayOf(marker),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val eventId = cursor.getLong(0)
            val reminderMinutes = resolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID}=? AND ${CalendarContract.Reminders.METHOD}=?",
                arrayOf(eventId.toString(), CalendarContract.Reminders.METHOD_ALERT.toString()),
                null,
            )?.use { reminder -> if (reminder.moveToFirst()) reminder.getInt(0) else 0 } ?: 0
            val start = cursor.getLong(3)
            val end = cursor.getLong(4)
            val title = cursor.getString(2).orEmpty()
            CalendarEventReceipt(
                eventId = eventId,
                eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId).toString(),
                calendarId = cursor.getLong(1),
                title = title,
                startEpochMillis = start,
                endEpochMillis = end,
                timeZone = cursor.getString(5).orEmpty(),
                reminderMinutes = reminderMinutes,
                reused = true,
                userMessage = receiptMessage(
                    title,
                    start,
                    end,
                    reminderMinutes,
                    cursor.getString(5).orEmpty(),
                ) + "\n事件 ID：$eventId",
            )
        }
    }

    override fun insertEvent(
        arguments: CalendarCreateEventArguments,
        marker: String,
    ): CalendarEventReceipt {
        val calendarId = requireNotNull(arguments.calendarId)
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                .withValue(CalendarContract.Events.TITLE, arguments.title)
                .withValue(CalendarContract.Events.DTSTART, arguments.startEpochMillis)
                .withValue(CalendarContract.Events.DTEND, arguments.endEpochMillis)
                .withValue(CalendarContract.Events.EVENT_TIMEZONE, arguments.timeZone)
                .withValue(CalendarContract.Events.HAS_ALARM, 1)
                .withValue(CalendarContract.Events.CUSTOM_APP_URI, marker)
                .build(),
            ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
                .withValue(CalendarContract.Reminders.MINUTES, arguments.reminderMinutes)
                .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                .build(),
        )
        val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
        val eventUri = requireNotNull(results.firstOrNull()?.uri) {
            "Calendar Provider 未返回事件 URI"
        }
        val eventId = ContentUris.parseId(eventUri)
        return CalendarEventReceipt(
            eventId = eventId,
            eventUri = eventUri.toString(),
            calendarId = calendarId,
            title = arguments.title,
            startEpochMillis = arguments.startEpochMillis,
            endEpochMillis = arguments.endEpochMillis,
            timeZone = arguments.timeZone,
            reminderMinutes = arguments.reminderMinutes,
            reused = false,
            userMessage = receiptMessage(
                arguments.title,
                arguments.startEpochMillis,
                arguments.endEpochMillis,
                arguments.reminderMinutes,
                arguments.timeZone,
            ) + "\n事件 ID：$eventId",
        )
    }

    private companion object {
        fun receiptMessage(
            title: String,
            start: Long,
            end: Long,
            reminder: Int,
            timeZone: String,
        ): String {
            val zone = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.systemDefault())
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            val startText = formatter.format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(start), zone))
            val endText = DateTimeFormatter.ofPattern("HH:mm").format(
                ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(end), zone)
            )
            val reminderText = if (reminder == 0) "开始时提醒" else "提前 ${reminder} 分钟提醒"
            return "已创建日程：$title\n$startText–$endText\n$reminderText"
        }
    }
}

class AndroidCalendarToolProvider(
    private val gateway: CalendarGateway,
    private val preferenceStore: CalendarPreferenceStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    },
) : ToolProvider {
    constructor(context: Context) : this(
        AndroidCalendarGateway(context),
        AndroidCalendarPreferenceStore(context),
    )

    override suspend fun listTools(): List<ToolDescriptor> = listOf(DESCRIPTOR)

    override fun executor(capabilityId: CapabilityId): ToolExecutor? =
        if (capabilityId == AndroidCalendarCapability.CREATE_EVENT) {
            ToolExecutor(::execute)
        } else null

    private suspend fun execute(call: ToolCall): ToolResult {
        val arguments = runCatching {
            json.decodeFromString<CalendarCreateEventArguments>(call.argumentsJson)
        }.getOrElse { failure ->
            return failure(call, "CALENDAR_ARGUMENT_INVALID", "未创建日程：${failure.message}", true)
        }
        if (arguments.title.isBlank() || arguments.endEpochMillis <= arguments.startEpochMillis) {
            return failure(call, "CALENDAR_ARGUMENT_INVALID", "未创建日程：标题或起止时间无效", true)
        }
        if (runCatching { ZoneId.of(arguments.timeZone) }.isFailure) {
            return failure(call, "CALENDAR_ARGUMENT_INVALID", "未创建日程：时区无效", true)
        }
        if (arguments.reminderMinutes !in 0..40_320) {
            return failure(call, "CALENDAR_ARGUMENT_INVALID", "未创建日程：提醒时间无效", true)
        }
        if (arguments.startEpochMillis <= clock.millis()) {
            return failure(
                call,
                "CALENDAR_TIME_EXPIRED",
                "未创建日程：审批中的开始时间已经过去，请重新指定时间",
                true,
            )
        }
        val marker = markerFor(call.idempotencyKey)
        val existing = runCatching { gateway.findByMarker(marker) }.getOrElse { failure ->
            if (failure is SecurityException) {
                return failure(
                    call,
                    "CALENDAR_PERMISSION_REQUIRED",
                    "未创建日程：缺少系统日历读写权限",
                    false,
                )
            }
            return failure(
                call,
                "CALENDAR_EXTERNAL_STATE_UNKNOWN",
                "无法确认日历中是否已有该事件：${failure.message.orEmpty()}",
                true,
            )
        }
        if (existing != null) return success(call, existing.copy(reused = true))

        val calendars = runCatching { gateway.writableCalendars() }.getOrElse { failure ->
            val code = if (failure is SecurityException) {
                "CALENDAR_PERMISSION_REQUIRED"
            } else "CALENDAR_PROVIDER_FAILED"
            return failure(call, code, "未创建日程：${failure.message.orEmpty()}", false)
        }
        if (calendars.isEmpty()) {
            return failure(call, "CALENDAR_NOT_WRITABLE", "未创建日程：设备上没有可写日历", false)
        }
        val selectedId = arguments.calendarId
            ?: preferenceStore.selectedCalendarId()?.takeIf { preferred ->
                calendars.any { it.id == preferred }
            }
            ?: calendars.singleOrNull()?.id
        if (selectedId == null) {
            val choices = calendars.joinToString("\n") { calendar ->
                "${calendar.id}|${calendar.displayName}（${calendar.accountName}）"
            }
            return ToolResult(
                callId = call.id,
                successful = false,
                contentJson = "{}",
                failure = AgentFailure(
                    "CALENDAR_SELECTION_REQUIRED",
                    "写入前需要选择目标日历",
                    true,
                    details = mapOf(
                        "inputPrompt" to "发现多个可写日历，请输入列表中的日历 ID 或选择项",
                        "choices" to choices,
                    ),
                ),
            )
        }
        if (calendars.none { it.id == selectedId }) {
            return failure(call, "CALENDAR_NOT_WRITABLE", "未创建日程：所选日历不可写", true)
        }
        val normalized = arguments.copy(calendarId = selectedId)
        val receipt = runCatching { gateway.insertEvent(normalized, marker) }.getOrElse { failure ->
            if (failure is SecurityException) {
                return failure(
                    call,
                    "CALENDAR_PERMISSION_REQUIRED",
                    "未创建日程：缺少系统日历读写权限",
                    false,
                )
            }
            return failure(
                call,
                "CALENDAR_EXTERNAL_STATE_UNKNOWN",
                "无法确认日历写入结果：${failure.message.orEmpty()}",
                true,
            )
        }
        preferenceStore.saveSelectedCalendarId(selectedId)
        return success(call, receipt)
    }

    private fun success(call: ToolCall, receipt: CalendarEventReceipt) = ToolResult(
        callId = call.id,
        successful = true,
        contentJson = json.encodeToString(receipt),
    )

    private fun failure(
        call: ToolCall,
        code: String,
        message: String,
        recoverable: Boolean,
    ) = ToolResult(
        callId = call.id,
        successful = false,
        contentJson = "{}",
        failure = AgentFailure(code, message, recoverable),
    )

    private fun markerFor(idempotencyKey: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(idempotencyKey.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return "edgeai://calendar-event/$hash"
    }

    companion object {
        val DESCRIPTOR = ToolDescriptor(
            capabilityId = AndroidCalendarCapability.CREATE_EVENT,
            name = "android_calendar_create_event",
            description = "在 Android 系统日历中创建事件和提醒，并返回真实事件 ID",
            inputSchemaJson = """{
                "type":"object",
                "required":["title","startEpochMillis","endEpochMillis","timeZone","reminderMinutes"],
                "properties":{
                    "title":{"type":"string"},
                    "startEpochMillis":{"type":"integer"},
                    "endEpochMillis":{"type":"integer"},
                    "timeZone":{"type":"string"},
                    "reminderMinutes":{"type":"integer"},
                    "calendarId":{"type":"integer"}
                },
                "additionalProperties":false
            }""".trimIndent(),
            outputSchemaJson = """{
                "type":"object",
                "required":["eventId","eventUri","calendarId","title","startEpochMillis","endEpochMillis","timeZone","reminderMinutes","reused","userMessage"],
                "properties":{
                    "eventId":{"type":"integer"},
                    "eventUri":{"type":"string"},
                    "calendarId":{"type":"integer"},
                    "title":{"type":"string"},
                    "startEpochMillis":{"type":"integer"},
                    "endEpochMillis":{"type":"integer"},
                    "timeZone":{"type":"string"},
                    "reminderMinutes":{"type":"integer"},
                    "reused":{"type":"boolean"},
                    "userMessage":{"type":"string"}
                },
                "additionalProperties":false
            }""".trimIndent(),
            riskLevel = RiskLevel.HIGH,
            idempotent = false,
            providerId = "android-calendar",
            version = "1",
            local = true,
            requiredAndroidPermissions = setOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            ),
        )
    }
}

class CalendarActionResolver(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val json: Json = Json { explicitNulls = false; encodeDefaults = true },
) : ActionResolver {
    override suspend fun resolve(context: ActionResolutionContext): ActionResolution? {
        val input = context.request.input.trim()
        if (STATUS_PATTERN.matches(input)) {
            val receipt = context.sessionHistory.asReversed()
                .firstOrNull { it.output.startsWith("已创建日程：") }
                ?.output
            return ActionResolution.Answer(receipt ?: "还没有创建日程。")
        }
        if (!isCreateIntent(input)) return null
        val continuation = context.continuationResponses[
            AndroidCalendarCapability.MISSING_DATE_TIME_REQUEST
        ].orEmpty()
        val combined = listOf(input, continuation).filter(String::isNotBlank).joinToString(" ")
        val date = parseDate(combined)
        val time = parseTime(combined)
        if (date == null || time == null) {
            val missing = buildList {
                if (date == null) add("日期")
                if (time == null) add("时间")
            }.joinToString("和")
            return ActionResolution.NeedsInput(
                requestId = AndroidCalendarCapability.MISSING_DATE_TIME_REQUEST,
                prompt = "创建日程还缺少$missing，请补充，例如“明天早上八点起床”。",
            )
        }
        val start = ZonedDateTime.of(date, time, zoneId)
        if (!start.toInstant().isAfter(clock.instant())) {
            return ActionResolution.NeedsInput(
                requestId = AndroidCalendarCapability.MISSING_DATE_TIME_REQUEST,
                prompt = "这个时间已经过去，请重新指定未来的日期和时间。",
            )
        }
        val end = start.plusMinutes(DEFAULT_DURATION_MINUTES)
        val title = extractTitle(combined)
        val arguments = CalendarCreateEventArguments(
            title = title,
            startEpochMillis = start.toInstant().toEpochMilli(),
            endEpochMillis = end.toInstant().toEpochMilli(),
            timeZone = zoneId.id,
            reminderMinutes = DEFAULT_REMINDER_MINUTES,
        )
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val preview = "创建日程：$title\n${formatter.format(start)}–${DateTimeFormatter.ofPattern("HH:mm").format(end)}" +
            "\n设备时区：${zoneId.id}\n开始时提醒\n目标日历：待权限通过后确认"
        return ActionResolution.Prepared(
            PreparedToolInvocation(
                capabilityId = AndroidCalendarCapability.CREATE_EVENT,
                argumentsJson = json.encodeToString(arguments),
                preview = preview,
                stepId = StepId("calendar-create-event"),
            )
        )
    }

    internal fun parseDate(text: String): LocalDate? {
        val today = LocalDate.now(clock.withZone(zoneId))
        return when {
            "后天" in text -> today.plusDays(2)
            "明天" in text || "明早" in text || "明晚" in text -> today.plusDays(1)
            "今天" in text || "今早" in text || "今晚" in text -> today
            else -> {
                runCatching {
                    FULL_DATE_PATTERN.find(text)?.let { match ->
                        LocalDate.of(
                            match.groupValues[1].toInt(),
                            match.groupValues[2].toInt(),
                            match.groupValues[3].toInt(),
                        )
                    } ?: MONTH_DAY_PATTERN.find(text)?.let { match ->
                        val month = match.groupValues[1].toInt()
                        val day = match.groupValues[2].toInt()
                        val candidate = LocalDate.of(today.year, month, day)
                        if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
                    }
                }.getOrNull()
            }
        }
    }

    internal fun parseTime(text: String): LocalTime? {
        val match = COLON_TIME_PATTERN.find(text) ?: POINT_TIME_PATTERN.find(text) ?: return null
        val period = match.groupValues[1]
        var hour = chineseNumber(match.groupValues[2]) ?: return null
        val minute = match.groupValues.getOrNull(3)?.takeIf(String::isNotBlank)
            ?.let(::chineseNumber) ?: 0
        when (period) {
            "下午", "傍晚", "晚上", "今晚", "明晚" -> if (hour in 1..11) hour += 12
            "中午" -> if (hour in 1..10) hour += 12
            "凌晨" -> if (hour == 12) hour = 0
        }
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    private fun isCreateIntent(text: String): Boolean {
        if (SUGGESTION_PATTERN.containsMatchIn(text)) return false
        return CALENDAR_NOUN_PATTERN.containsMatchIn(text) && CREATE_VERB_PATTERN.containsMatchIn(text)
    }

    private fun extractTitle(text: String): String {
        QUOTED_TITLE_PATTERN.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
            ?.let { return it.take(MAX_TITLE_LENGTH) }
        var candidate = text
            .replace(FULL_DATE_PATTERN, " ")
            .replace(MONTH_DAY_PATTERN, " ")
            .replace(RELATIVE_DATE_PATTERN, " ")
            .replace(COLON_TIME_PATTERN, " ")
            .replace(POINT_TIME_PATTERN, " ")
            .replace(Regex("帮我|麻烦|请|提醒我|创建|新建|添加|加入|安排|设置|预定|定个|定|一个|个"), " ")
            .replace(Regex("到|进|在|于|的?(?:日程|日历|事件|会议)"), " ")
            .replace(Regex("[，。！？,.!?：:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (candidate.endsWith("的")) candidate = candidate.dropLast(1).trim()
        return candidate.ifBlank { "日程" }.take(MAX_TITLE_LENGTH)
    }

    private fun chineseNumber(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        val values = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
            '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (raw == "十") return 10
        if ('十' in raw) {
            val parts = raw.split('十')
            val tens = parts.first().singleOrNull()?.let(values::get) ?: 1
            val ones = parts.getOrNull(1)?.singleOrNull()?.let(values::get) ?: 0
            return tens * 10 + ones
        }
        return raw.singleOrNull()?.let(values::get)
    }

    private companion object {
        const val DEFAULT_DURATION_MINUTES = 30L
        const val DEFAULT_REMINDER_MINUTES = 0
        const val MAX_TITLE_LENGTH = 80
        val STATUS_PATTERN = Regex("^(?:定|创建|添加|安排)好了?吗[？?]?$|^定了吗[？?]?$")
        val CALENDAR_NOUN_PATTERN = Regex("日程|日历|事件|会议")
        val CREATE_VERB_PATTERN = Regex("创建|新建|添加|加入|安排|设置|预定|帮我定|定个")
        val SUGGESTION_PATTERN = Regex("规划|建议|怎么安排|给.*方案|作息建议")
        val RELATIVE_DATE_PATTERN = Regex("后天|明天|明早|明晚|今天|今早|今晚")
        val FULL_DATE_PATTERN = Regex("(\\d{4})[年/-](\\d{1,2})[月/-](\\d{1,2})日?")
        val MONTH_DAY_PATTERN = Regex("(?<![\\d年])(\\d{1,2})月(\\d{1,2})日?")
        val PERIOD = "(凌晨|早上|上午|中午|下午|傍晚|晚上|今晚|明晚)?"
        val NUMBER = "([零〇一二两三四五六七八九十\\d]{1,3})"
        val COLON_TIME_PATTERN = Regex("$PERIOD\\s*(\\d{1,2})[:：](\\d{1,2})")
        val POINT_TIME_PATTERN = Regex("$PERIOD\\s*$NUMBER\\s*(?:点|时)(?:\\s*$NUMBER\\s*分?)?")
        val QUOTED_TITLE_PATTERN = Regex("[“\"]([^”\"]+)[”\"]")
    }
}
