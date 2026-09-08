package com.zjf.edgeai

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.zjf.edgeai.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class CalendarActionUiDeviceTest {
    @Test
    fun approvedRequestWithCalendarPermissionsQueriesRealProvider() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.READ_CALENDAR,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.WRITE_CALENDAR,
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openChatAndSubmitCalendarRequest(scenario)
            waitForApproval(scenario)
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR),
            )

            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.chatApprove).performClick())
            }
            waitForActivity(scenario) { activity ->
                activity.window.decorView.containsText("设备上没有可写日历")
            }
        }
    }

    @Test
    fun calendarRequestShowsInlineApprovalAndSurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openChatAndSubmitCalendarRequest(scenario)
            waitForApproval(scenario)

            scenario.recreate()
            waitForApproval(scenario)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitForApproval(scenario)

            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.chatDeny).performClick())
            }
            waitForActivity(scenario) { activity ->
                activity.window.decorView.containsText("未创建日程")
            }
        }
    }

    private fun openChatAndSubmitCalendarRequest(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            activity.findViewById<ViewPager2>(R.id.viewPager).currentItem = CHAT_PAGE_INDEX
        }
        waitForActivity(scenario) { activity ->
            activity.findViewById<TextView>(R.id.chatStatus)
                ?.text
                ?.contains("模型已就绪") == true
        }
        scenario.onActivity { activity ->
            activity.findViewById<TextView>(R.id.promptInput).text =
                "帮我定个明天早上八点起床的日程"
            assertTrue(activity.findViewById<View>(R.id.sendButton).performClick())
        }
    }

    private fun waitForApproval(scenario: ActivityScenario<MainActivity>) {
        waitForActivity(scenario) { activity ->
            val panel = activity.findViewById<View>(R.id.chatApprovalPanel)
            val text = activity.findViewById<TextView>(R.id.chatApprovalText)
            panel?.visibility == View.VISIBLE &&
                text?.text?.contains("android.calendar.create_event") == true
        }
    }

    private fun waitForActivity(
        scenario: ActivityScenario<MainActivity>,
        condition: (MainActivity) -> Boolean,
    ) {
        var lastFailure: Throwable? = null
        repeat(DEVICE_WAIT_ATTEMPTS) {
            var matched = false
            try {
                scenario.onActivity { activity -> matched = condition(activity) }
                if (matched) return
            } catch (failure: Throwable) {
                lastFailure = failure
            }
            SystemClock.sleep(DEVICE_WAIT_INTERVAL_MILLIS)
        }
        throw AssertionError("等待界面状态超时", lastFailure)
    }

    private fun View.containsText(expected: String): Boolean = when (this) {
        is TextView -> text?.contains(expected) == true
        is ViewGroup -> (0 until childCount).any { getChildAt(it).containsText(expected) }
        else -> false
    }

    private companion object {
        const val CHAT_PAGE_INDEX = 1
        const val DEVICE_WAIT_ATTEMPTS = 150
        const val DEVICE_WAIT_INTERVAL_MILLIS = 200L
    }
}
