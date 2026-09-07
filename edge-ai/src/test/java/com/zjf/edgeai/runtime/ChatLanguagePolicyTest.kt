package com.zjf.edgeai.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLanguagePolicyTest {
    @Test
    fun chineseFirstLowVarianceDefaultsArePinned() {
        assertEquals(10, ChatLanguagePolicy.TOP_K)
        assertEquals(0.95, ChatLanguagePolicy.TOP_P, 0.0)
        assertEquals(0.2, ChatLanguagePolicy.TEMPERATURE, 0.0)
        assertEquals(0, ChatLanguagePolicy.SEED)
        assertEquals(512, ChatLanguagePolicy.MAX_OUTPUT_TOKENS)
        assertEquals(1.1f, ChatLanguagePolicy.REPETITION_PENALTY)
        assertEquals(64, ChatLanguagePolicy.REPETITION_WINDOW_SIZE)
        assertEquals(3, ChatLanguagePolicy.NO_REPEAT_NGRAM_SIZE)
        assertFalse(ChatLanguagePolicy.THINKING_ENABLED)
    }

    @Test
    fun policyCoversMixedInputChineseOutputAndLiteralPreservation() {
        val instruction = ChatLanguagePolicy.SYSTEM_INSTRUCTION

        assertTrue(instruction.contains("中文、English 和中英混合"))
        assertTrue(instruction.contains("简体中文回答"))
        assertTrue(instruction.contains("代码、命令、URL、文件名、产品名、缩写、引号内内容"))
        assertTrue(instruction.contains("明确指定目标语言"))
        assertTrue(instruction.contains("不要复述、改写或反问"))
        assertTrue(instruction.contains("不能操作手机、不能访问网络、不能读取其他应用"))
    }

    @Test
    fun userTurnPayloadIsTheExactUnwrappedMixedLanguagePrompt() {
        val rawPrompt = "  请 explain JVM 的 GC，并保留 adb devices  "
        val payload = ChatLanguagePolicy.userTurnPayload(rawPrompt)

        assertEquals(rawPrompt, payload)
        assertTrue(payload.none { it == '<' || it == '>' })
    }

    @Test
    fun literalPreservationSuffixAddsOnlyMissingRecognizedLiterals() {
        val prompt = "请原样输出 adb devices、https://developer.android.com 和 config.json"
        val generated = "请使用 adb devices 查看设备。"

        assertEquals(
            "\n\n原文保留：\n- https://developer.android.com\n- config.json",
            ChatLanguagePolicy.literalPreservationSuffix(prompt, generated)
        )
    }

    @Test
    fun directAnswerExampleAndGenerationControlsArePinned() {
        assertEquals(1, ChatLanguagePolicy.DIRECT_ANSWER_EXAMPLES.size)
        val (user, model) = ChatLanguagePolicy.DIRECT_ANSWER_EXAMPLES.single()
        assertEquals("你能在这台手机上做什么？", user)
        assertTrue(model.contains("离线"))
        assertTrue(model.contains("不能控制手机"))
        assertFalse(ChatLanguagePolicy.THINKING_ENABLED)
        assertEquals(1.1f, ChatLanguagePolicy.REPETITION_PENALTY)
        assertEquals(64, ChatLanguagePolicy.REPETITION_WINDOW_SIZE)
        assertEquals(3, ChatLanguagePolicy.NO_REPEAT_NGRAM_SIZE)
    }
}
