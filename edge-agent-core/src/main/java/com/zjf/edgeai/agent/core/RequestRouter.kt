package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.RoutingHint

data class RouteDecision(val route: RoutingHint, val reason: String)

fun interface RequestRouter {
    fun route(request: AgentRequest): RouteDecision
}

class DeterministicRequestRouter : RequestRouter {
    private val complexSignals = listOf(
        "并行", "分别", "对比", "综合", "验证", "多步骤", "分工", "调研",
        "parallel", "compare", "verify", "research", "multiple steps",
    )

    override fun route(request: AgentRequest): RouteDecision {
        if (request.routingHint != RoutingHint.AUTO) {
            return RouteDecision(request.routingHint, "调用方显式指定")
        }
        if (!request.workflowId.isNullOrBlank()) {
            return RouteDecision(RoutingHint.WORKFLOW, "请求指定工作流 ${request.workflowId}")
        }
        if (request.requestedHandoffAgentId != null) {
            return RouteDecision(RoutingHint.SUPERVISOR, "请求显式指定专业 Agent 接管")
        }
        val normalized = request.input.lowercase()
        return if (complexSignals.any(normalized::contains)) {
            RouteDecision(RoutingHint.SUPERVISOR, "请求包含可拆分、比较或验证信号")
        } else {
            RouteDecision(RoutingHint.SINGLE_AGENT, "普通对话或单目标任务")
        }
    }
}
