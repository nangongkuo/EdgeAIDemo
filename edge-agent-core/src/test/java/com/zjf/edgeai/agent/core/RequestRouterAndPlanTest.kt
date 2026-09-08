package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.AgentDefinition
import com.zjf.edgeai.agent.api.AgentId
import com.zjf.edgeai.agent.api.AgentRequest
import com.zjf.edgeai.agent.api.ExecutionPlan
import com.zjf.edgeai.agent.api.PlanNode
import com.zjf.edgeai.agent.api.PlanNodeType
import com.zjf.edgeai.agent.api.RoutingHint
import com.zjf.edgeai.agent.api.RunBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRouterAndPlanTest {
    @Test
    fun simplePromptUsesFastPath() {
        assertEquals(
            RoutingHint.SINGLE_AGENT,
            DeterministicRequestRouter().route(AgentRequest("你好")).route,
        )
    }

    @Test
    fun complexPromptUsesSupervisor() {
        assertEquals(
            RoutingHint.SUPERVISOR,
            DeterministicRequestRouter().route(AgentRequest("分别调研并对比两个方案")).route,
        )
    }

    @Test
    fun validatorRejectsCyclesAndUnknownAgents() {
        val plan = ExecutionPlan(
            nodes = listOf(
                PlanNode("a", PlanNodeType.AGENT, "A", dependsOn = setOf("b"), agentId = AgentId("missing")),
                PlanNode("b", PlanNodeType.SEQUENTIAL, "B", dependsOn = setOf("a")),
            ),
            finalNodeId = "b",
        )
        val result = ExecutionPlanValidator().validate(
            plan,
            RunBudget(),
            listOf(AgentDefinition(AgentId("root"), "root", "", "")),
        )
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.contains("依赖环") })
        assertTrue(result.errors.any { it.contains("未知 Agent") })
    }
}
