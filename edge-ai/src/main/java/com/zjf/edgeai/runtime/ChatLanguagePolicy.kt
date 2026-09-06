package com.zjf.edgeai.runtime

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * Conversation-wide language behavior for the bundled offline chat model.
 *
 * The policy is deliberately static: the product default is Simplified Chinese, so script
 * detection would add ambiguity for mixed text, code, abbreviations, and proper names.
 */
internal object ChatLanguagePolicy {
    const val MAX_OUTPUT_TOKENS = 512
    const val TOP_K = 10
    const val TOP_P = 0.95
    const val TEMPERATURE = 0.2
    const val SEED = 0

    private val urlPattern = Regex("""\bhttps?://[^\s`'\"<>，。！？、）】]+""")
    private val inlineCodePattern = Regex("`([^`\n]+)`")
    private val fileNamePattern = Regex("""\b[\w.-]+\.(?:apk|java|json|kt|kts|litertlm|md|xml)\b""")
    private val abbreviationPattern = Regex("""\b[A-Z][A-Z0-9_]{1,}\b""")
    private val commandPattern = Regex(
        """\b(?:adb|git|gradle|npm|python|java|kotlin)(?:\s+[A-Za-z0-9_./:=\-]+){0,4}"""
    )
    const val SYSTEM_INSTRUCTION = """
        你是运行在 Android 设备上的完全离线助手。

        语言规则：
        1. 你必须理解中文、English 和中英混合的用户输入。
        2. 除非用户明确指定目标语言或要求翻译，请始终使用简体中文回答。
        3. 对代码、命令、URL、文件名、产品名、缩写、引号内内容以及用户要求保留的英文术语，必须原样保留字符；不要翻译、改写或纠正这些文字。
        4. 对中英混合的问题，用简体中文解释，并保留必要的英文技术术语。
        5. 用户明确要求翻译到其他语言或指定回答语言时，遵守该明确要求。
    """

    fun createConversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(SYSTEM_INSTRUCTION.trimIndent()),
        samplerConfig = SamplerConfig(
            topK = TOP_K,
            topP = TOP_P,
            temperature = TEMPERATURE,
            seed = SEED
        ),
        maxOutputToken = MAX_OUTPUT_TOKENS
    )

    /**
     * LiteRT-LM owns user/assistant role formatting. The visible user prompt must stay the
     * complete current turn: wrapping it in a second instruction makes the bundled 1B model
     * paraphrase the nested question instead of answering it.
     */
    fun userTurnPayload(rawPrompt: String): String = rawPrompt

    /** Returns a UI-safe suffix only for recognized literals omitted by the model output. */
    fun literalPreservationSuffix(rawPrompt: String, generatedText: String): String {
        val missing = protectedLiterals(rawPrompt).filterNot(generatedText::contains)
        return missing.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "\n\n原文保留：\n- ", separator = "\n- ")
            .orEmpty()
    }

    private fun protectedLiterals(rawPrompt: String): List<String> = buildList {
        urlPattern.findAll(rawPrompt).mapTo(this) { it.value }
        inlineCodePattern.findAll(rawPrompt).mapTo(this) { it.groupValues[1] }
        fileNamePattern.findAll(rawPrompt).mapTo(this) { it.value }
        abbreviationPattern.findAll(rawPrompt).mapTo(this) { it.value }
        commandPattern.findAll(rawPrompt).mapTo(this) { it.value }
    }.filter(String::isNotBlank).distinct()
}
