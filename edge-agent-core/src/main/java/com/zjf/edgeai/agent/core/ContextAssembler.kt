package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.MemoryRecord
import com.zjf.edgeai.agent.api.ModelMessage
import com.zjf.edgeai.agent.api.SessionTurn

data class ContextInput(
    val securityInstruction: String,
    val agentInstruction: String,
    val objective: String,
    val memories: List<MemoryRecord> = emptyList(),
    val branchMessages: List<ModelMessage> = emptyList(),
    val toolSummaries: List<String> = emptyList(),
    val compactedHistory: String? = null,
)

data class AssembledContext(
    val systemInstruction: String,
    val messages: List<ModelMessage>,
    val estimatedTokens: Int,
    val omittedItems: Int,
)

class ContextAssembler(
    private val estimateTokens: (String) -> Int = { text -> maxOf(1, text.length / 4) },
) {
    fun assemble(input: ContextInput, maxTokens: Int): AssembledContext {
        require(maxTokens > 0)
        val system = listOf(input.securityInstruction, input.agentInstruction, "当前目标：${input.objective}")
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        var used = estimateTokens(system)
        require(used <= maxTokens) { "安全规则与当前目标已超过模型上下文预算" }
        var omitted = 0
        val messages = mutableListOf<ModelMessage>()

        fun add(message: ModelMessage) {
            val payload = buildString {
                append(message.text)
                message.toolCalls.forEach { append(it.name).append(it.argumentsJson) }
                message.toolResults.forEach { append(it.name).append(it.responseJson) }
            }
            if (payload.isBlank()) return
            val cost = estimateTokens(payload)
            if (used + cost <= maxTokens) {
                messages += message
                used += cost
            } else omitted++
        }

        input.memories.sortedByDescending { it.importance }
            .forEach { add(ModelMessage("memory", it.content)) }
        input.branchMessages.forEach(::add)
        input.toolSummaries.forEach { add(ModelMessage("tool", it)) }
        input.compactedHistory?.let { add(ModelMessage("history", it)) }
        return AssembledContext(system, messages, used, omitted)
    }
}

class SessionHistoryCompactor(
    private val maxItemChars: Int = 800,
) {
    data class Compacted(
        val recentMessages: List<ModelMessage>,
        val earlierSummary: String?,
    )

    fun compact(turns: List<SessionTurn>, recentTurns: Int = 2): Compacted {
        val split = (turns.size - recentTurns).coerceAtLeast(0)
        val earlier = turns.take(split)
        val recent = turns.drop(split).flatMap { turn ->
            listOf(
                ModelMessage("user", turn.input.take(maxItemChars)),
                ModelMessage("assistant", turn.output.take(maxItemChars)),
            )
        }
        val summary = earlier.takeIf { it.isNotEmpty() }?.joinToString("\n") { turn ->
            "- 用户：${turn.input.take(maxItemChars / 2)}\n  助手：${turn.output.take(maxItemChars / 2)}"
        }
        return Compacted(recent, summary)
    }
}
