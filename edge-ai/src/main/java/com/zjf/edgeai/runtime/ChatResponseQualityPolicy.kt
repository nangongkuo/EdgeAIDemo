package com.zjf.edgeai.runtime

import java.util.Locale
import kotlin.math.max

internal data class ChatResponseQuality(
    val normalizedPrompt: String,
    val normalizedResponse: String,
    val similarity: Double,
    val rejectedAsEcho: Boolean
)

internal object ChatResponseQualityPolicy {
    const val MAX_GENERATION_ATTEMPTS = 2
    const val ECHO_SIMILARITY_THRESHOLD = 0.72
    private const val MIN_COMPARABLE_LENGTH = 6
    private const val MAX_RESPONSE_LENGTH_RATIO = 1.8
    private const val RESPONSE_LENGTH_ALLOWANCE = 12

    private val greetingPrefix = Regex(
        pattern = "^(?:(?:您好|你好|嗨|hello\\b|hi\\b)[\\p{P}\\p{S}\\s]*)+",
        option = RegexOption.IGNORE_CASE
    )
    private val trailingQuestionParticles = setOf('吗', '呢', '呀', '啊', '吧')

    fun assess(prompt: String, response: String): ChatResponseQuality {
        val normalizedPrompt = normalize(prompt)
        val normalizedResponse = normalize(response)
        val similarity = diceCoefficient(normalizedPrompt, normalizedResponse)
        val maximumComparableLength = max(
            normalizedPrompt.length + RESPONSE_LENGTH_ALLOWANCE,
            (normalizedPrompt.length * MAX_RESPONSE_LENGTH_RATIO).toInt()
        )
        val comparable = normalizedPrompt.length >= MIN_COMPARABLE_LENGTH &&
            normalizedResponse.length >= MIN_COMPARABLE_LENGTH &&
            normalizedResponse.length <= maximumComparableLength
        val containsTurn = normalizedResponse.contains(normalizedPrompt) ||
            normalizedPrompt.contains(normalizedResponse)

        return ChatResponseQuality(
            normalizedPrompt = normalizedPrompt,
            normalizedResponse = normalizedResponse,
            similarity = similarity,
            rejectedAsEcho = comparable && (containsTurn || similarity >= ECHO_SIMILARITY_THRESHOLD)
        )
    }

    private fun normalize(value: String): String {
        var normalized = greetingPrefix.replace(value.trim(), "")
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
            .replace('您', '你')
        while (normalized.lastOrNull() in trailingQuestionParticles) {
            normalized = normalized.dropLast(1)
        }
        return normalized
    }

    private fun diceCoefficient(left: String, right: String): Double {
        if (left == right) return if (left.isEmpty()) 0.0 else 1.0
        if (left.length < 2 || right.length < 2) return 0.0

        val leftBigrams = left.zipWithNext { first, second -> "$first$second" }
            .groupingBy { it }
            .eachCount()
            .toMutableMap()
        var intersection = 0
        right.zipWithNext { first, second -> "$first$second" }.forEach { bigram ->
            val remaining = leftBigrams[bigram] ?: 0
            if (remaining > 0) {
                intersection += 1
                leftBigrams[bigram] = remaining - 1
            }
        }
        return (2.0 * intersection) / ((left.length - 1) + (right.length - 1))
    }
}
