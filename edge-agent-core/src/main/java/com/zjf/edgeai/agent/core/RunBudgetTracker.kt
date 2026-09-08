package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.RunBudget
import java.util.concurrent.atomic.AtomicInteger

class BudgetExceededException(message: String) : IllegalStateException(message)

class RunBudgetTracker(private val budget: RunBudget) {
    private val modelTokens = AtomicInteger()
    private val toolCalls = AtomicInteger()

    fun consumeModelText(text: String) {
        val estimated = maxOf(1, text.length / 4)
        val total = modelTokens.addAndGet(estimated)
        if (total > budget.maxModelTokens) {
            throw BudgetExceededException("模型 Token 预算已耗尽：$total/${budget.maxModelTokens}")
        }
    }

    fun consumeToolCall() {
        val total = toolCalls.incrementAndGet()
        if (total > budget.maxToolCalls) {
            throw BudgetExceededException("工具调用预算已耗尽：$total/${budget.maxToolCalls}")
        }
    }
}
