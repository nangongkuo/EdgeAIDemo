package com.zjf.edgeai.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResponseQualityPolicyTest {
    @Test
    fun screenshotCapabilityParaphrasesAreRejectedAsEchoes() {
        assertTrue(
            ChatResponseQualityPolicy.assess(
                prompt = "你能在这台手机上做什么",
                response = "您好，您想在这台手机上做什么呢？"
            ).rejectedAsEcho
        )
        assertTrue(
            ChatResponseQualityPolicy.assess(
                prompt = "我想先看看你的能力再决定做什么，你告诉我你能做什么",
                response = "您好，您好，您想先看看你的能力再决定做什么，你告诉我你能做什么？"
            ).rejectedAsEcho
        )
    }

    @Test
    fun directExplanationIsNotRejectedForSharingQuestionTerms() {
        val result = ChatResponseQualityPolicy.assess(
            prompt = "你能在这台手机上做什么",
            response = "我可以在本应用中完全离线地进行文字问答、解释概念和整理文字，不能控制手机或访问网络。"
        )

        assertFalse(result.rejectedAsEcho)
    }

    @Test
    fun shortGreetingIsNotTreatedAsAnEcho() {
        assertFalse(
            ChatResponseQualityPolicy.assess(
                prompt = "hi",
                response = "你好，我可以为你提供离线文字问答。"
            ).rejectedAsEcho
        )
    }

    @Test
    fun literalPreservationWithAnExplanationIsNotRejected() {
        assertFalse(
            ChatResponseQualityPolicy.assess(
                prompt = "请原样输出 adb devices 和 https://developer.android.com",
                response = "可以使用 adb devices 查看设备；https://developer.android.com 是 Android 官方开发文档地址。"
            ).rejectedAsEcho
        )
    }

    @Test
    fun retryCountIsBounded() {
        assertTrue(ChatResponseQualityPolicy.MAX_GENERATION_ATTEMPTS == 2)
    }
}
