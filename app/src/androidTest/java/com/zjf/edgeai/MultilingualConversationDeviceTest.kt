package com.zjf.edgeai

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.zjf.edgeai.runtime.GenerationEvent
import com.zjf.edgeai.runtime.LiteRtLmRuntime
import com.zjf.edgeai.runtime.RuntimeBackend
import com.zjf.edgeai.runtime.model.LocalModelStore
import com.zjf.edgeai.runtime.model.ModelDescriptor
import com.zjf.edgeai.runtime.model.ModelImportEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class MultilingualConversationDeviceTest {
    @Test
    fun bundledModelAnswersReportedMultiTurnScenarioWithoutSemanticEcho() = runBlocking<Unit> {
        assumeTrue("需要 arm64-v8a 设备", Build.SUPPORTED_ABIS.contains("arm64-v8a"))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = LiteRtLmRuntime(context)
        val backend = if (isEmulator()) RuntimeBackend.CPU else RuntimeBackend.GPU

        try {
            runtime.initialize(loadBundledModel(context), backend)

            assertCompletedResponse(runtime, "hi")
            val firstCapabilityAnswer = assertCompletedResponse(runtime, "你能在这台手机上做什么")
            assertDirectCapabilityAnswer(firstCapabilityAnswer)

            val secondCapabilityAnswer = assertCompletedResponse(
                runtime,
                "我想先看看你的能力再决定做什么，你告诉我你能做什么"
            )
            assertDirectCapabilityAnswer(secondCapabilityAnswer)
        } finally {
            runtime.close()
        }
        Unit
    }

    @Test
    fun bundledModelAnswersChineseQuestionsWithoutRepeatingTheUserTurn() = runBlocking<Unit> {
        assumeTrue("需要 arm64-v8a 真机", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        assumeFalse("模拟器不作为端侧推理验收设备", isEmulator())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = LiteRtLmRuntime(context)

        try {
            runtime.initialize(loadBundledModel(context), RuntimeBackend.GPU)

            val capabilityPrompt = "请用一句话说明你能做什么，不要复述这句话。"
            val capabilityAnswer = assertCompletedResponse(runtime, capabilityPrompt)
            assertTrue("能力回答必须包含中文；实际回复：$capabilityAnswer", capabilityAnswer.hasCjk())
            assertFalse(
                "能力回答不得复述用户问题；实际回复：$capabilityAnswer",
                capabilityAnswer.normalizedForEchoCheck() == capabilityPrompt.normalizedForEchoCheck()
            )

            assertCompletedResponse(runtime, "Explain Android APK in one short sentence.")
            assertCompletedResponse(runtime, "请 explain JVM 的 GC，并保留 JVM 和 GC。")
        } finally {
            runtime.close()
        }
        Unit
    }

    @Test
    fun bundledModelHandlesChineseEnglishAndMixedPromptsOffline() = runBlocking<Unit> {
        assumeTrue("需要 arm64-v8a 真机", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        assumeFalse("模拟器不作为端侧推理验收设备", isEmulator())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = loadBundledModel(context)
        val runtime = LiteRtLmRuntime(context)

        try {
            runtime.initialize(model, RuntimeBackend.GPU)

            assertChineseResponse(runtime, "请用一句简体中文介绍 Android 端侧 AI。")
            assertChineseResponse(runtime, "Explain what an Android APK is in one short sentence.")

            val mixed = assertChineseResponse(
                runtime,
                "请 explain JVM 的 GC，并保留 JVM、GC 和 Java 这些术语。"
            )
            assertTrue("混合输入中的 JVM 必须保留", mixed.contains("JVM"))
            assertTrue("混合输入中的 GC 必须保留", mixed.contains("GC"))

            val literal = assertChineseResponse(
                runtime,
                "请原样输出 adb devices 和 https://developer.android.com，然后用一句简体中文说明它们的用途。"
            )
            assertTrue("命令必须原样保留", literal.contains("adb devices"))
            assertTrue("URL 必须原样保留", literal.contains("https://developer.android.com"))

            assertChineseResponse(runtime, "请用一句简体中文总结刚才关于 Android APK 的说明。")

            runtime.resetConversation()
            assertChineseResponse(runtime, "Explain Edge AI in one short sentence after reset.")
        } finally {
            runtime.close()
        }
        Unit
    }

    private suspend fun loadBundledModel(context: android.content.Context): ModelDescriptor {
        val store = LocalModelStore(context)
        store.selectedModel.value?.let { return it }

        var installed: ModelDescriptor? = null
        store.installBundledModel().collect { event ->
            if (event is ModelImportEvent.Completed) installed = event.model
        }
        return requireNotNull(installed) { "未能安装内置 Gemma 模型" }
    }

    private suspend fun assertChineseResponse(runtime: LiteRtLmRuntime, prompt: String): String =
        assertCompletedResponse(runtime, prompt).also { text ->
            assertTrue(
                "默认回复必须包含简体中文；实际回复：$text",
                text.hasCjk()
            )
        }

    private suspend fun assertCompletedResponse(runtime: LiteRtLmRuntime, prompt: String): String =
        withTimeout(RESPONSE_TIMEOUT_MILLIS) {
            val response = StringBuilder()
            var completed = false
            runtime.generate(prompt).collect { event ->
                when (event) {
                    is GenerationEvent.Delta -> response.append(event.text)
                    GenerationEvent.Reset -> response.clear()
                    is GenerationEvent.Completed -> completed = true
                    is GenerationEvent.Failed -> throw AssertionError("生成失败：${event.message}")
                }
            }
            val text = response.toString()
            Log.i(TAG, "prompt=$prompt response=$text")
            assertTrue("流式回复必须完成", completed)
            assertTrue("回复不能为空", text.isNotBlank())
            text
        }

    private fun assertDirectCapabilityAnswer(response: String) {
        assertTrue(
            "能力回答必须直接说明能力或限制；实际回复：$response",
            listOf("我能", "可以", "能够", "离线", "文字", "问答", "不能").any(response::contains)
        )
        assertFalse(
            "能力回答不得延续截图中的礼貌反问；实际回复：$response",
            response.contains("想在这台手机上做什么") ||
                response.contains("先看看你的能力再决定做什么")
        )
    }

    private fun String.hasCjk(): Boolean = any { it in '\u4E00'..'\u9FFF' }

    private fun String.normalizedForEchoCheck(): String =
        trim().trimEnd('。', '！', '？', '.', '!', '?').replace(Regex("\\s+"), "")

    private fun isEmulator(): Boolean = listOf(
        Build.FINGERPRINT,
        Build.MODEL,
        Build.MANUFACTURER,
        Build.BRAND,
        Build.DEVICE,
        Build.HARDWARE
    ).joinToString(" ").lowercase().let { signature ->
        signature.contains("generic") ||
            signature.contains("ranchu") ||
            signature.contains("goldfish") ||
            signature.contains("emulator")
    }

    private companion object {
        const val TAG = "MultilingualDeviceTest"
        const val RESPONSE_TIMEOUT_MILLIS = 180_000L
    }
}
