package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentDefinition
import com.zjf.edgeai.agent.api.ExecutionPlan
import com.zjf.edgeai.agent.api.PlanNodeType
import com.zjf.edgeai.agent.api.RunBudget

data class PlanValidationResult(val valid: Boolean, val errors: List<String>)

class ExecutionPlanValidator {
    fun validate(
        plan: ExecutionPlan,
        budget: RunBudget,
        agents: Collection<AgentDefinition>,
    ): PlanValidationResult {
        val errors = mutableListOf<String>()
        val ids = plan.nodes.map { it.id }
        val idSet = ids.toSet()
        if (ids.size != idSet.size) errors += "计划节点 ID 必须唯一"
        if (plan.finalNodeId !in idSet) errors += "finalNodeId 不存在"
        if (plan.nodes.size > budget.maxStepsPerAgent * maxOf(1, budget.maxWorkers)) {
            errors += "计划节点数超过预算"
        }
        val knownAgents = agents.associateBy { it.id }
        plan.nodes.forEach { node ->
            if (node.id.isBlank()) errors += "节点 ID 不能为空"
            val unknownDeps = node.dependsOn - idSet
            if (unknownDeps.isNotEmpty()) errors += "节点 ${node.id} 引用未知依赖 $unknownDeps"
            if (node.id in node.dependsOn) errors += "节点 ${node.id} 不能依赖自身"
            if (node.type == PlanNodeType.AGENT && node.agentId !in knownAgents) {
                errors += "节点 ${node.id} 引用未知 Agent"
            }
            if (node.type == PlanNodeType.TOOL && node.capabilityId == null) {
                errors += "工具节点 ${node.id} 缺少 capabilityId"
            }
            if (node.type == PlanNodeType.LOOP && node.maxIterations !in 1..budget.maxStepsPerAgent) {
                errors += "循环节点 ${node.id} 超出迭代预算"
            }
            if (node.children.any { it !in idSet }) errors += "节点 ${node.id} 引用未知子节点"
        }
        if (hasCycle(plan.nodes.associate { it.id to it.dependsOn })) errors += "计划不能包含依赖环"
        val agentNodes = plan.nodes.count {
            it.type == PlanNodeType.AGENT && it.id != plan.finalNodeId
        }
        if (agentNodes > budget.maxWorkers) errors += "Worker 数超过预算"
        return PlanValidationResult(errors.isEmpty(), errors.distinct())
    }

    private fun hasCycle(graph: Map<String, Set<String>>): Boolean {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return true
            if (!visited.add(id)) return false
            visiting += id
            val cycle = graph[id].orEmpty().any(::visit)
            visiting -= id
            return cycle
        }
        return graph.keys.any(::visit)
    }
}
