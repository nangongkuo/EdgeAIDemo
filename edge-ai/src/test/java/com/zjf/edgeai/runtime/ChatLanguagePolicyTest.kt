package com.zjf.edgeai.runtime

import org.junit.Assert.assertEquals
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
    }

    @Test
    fun policyCoversMixedInputChineseOutputAndLiteralPreservation() {
        val instruction = ChatLanguagePolicy.SYSTEM_INSTRUCTION

        assertTrue(instruction.contains("中文、English 和中英混合"))
        assertTrue(instruction.contains("简体中文回答"))
        assertTrue(instruction.contains("代码、命令、URL、文件名、产品名、缩写、引号内内容"))
        assertTrue(instruction.contains("明确指定目标语言"))
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

}
