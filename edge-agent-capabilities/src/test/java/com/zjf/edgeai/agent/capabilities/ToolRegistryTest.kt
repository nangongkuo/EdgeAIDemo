package com.zjf.edgeai.agent.capabilities

import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.RiskLevel
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.StepId
import com.zjf.edgeai.agent.api.ToolCall
import com.zjf.edgeai.agent.api.ToolCallId
import com.zjf.edgeai.agent.api.ToolDescriptor
import com.zjf.edgeai.agent.api.ToolExecutor
import com.zjf.edgeai.agent.api.ToolProvider
import com.zjf.edgeai.agent.api.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ToolRegistryTest {
    private val capability = CapabilityId("test.write")
    private val provider = object : ToolProvider {
        override suspend fun listTools() = listOf(
            ToolDescriptor(
                capability,
                "write",
                "写入",
                """{"type":"object","required":["value"],"properties":{"value":{"type":"string"}},"additionalProperties":false}""",
                riskLevel = RiskLevel.HIGH,
                idempotent = false,
            )
        )
        override fun executor(capabilityId: CapabilityId) = ToolExecutor { call ->
            ToolResult(call.id, true, "{\"ok\":true}")
        }
    }

    @Test
    fun highRiskToolRequiresApprovalAndExecutesOnce() = runTest {
        val registry = ToolRegistry(listOf(provider))
        var approvals = 0
        val result = registry.invoke(
            call(),
            SessionId("s"),
            setOf(capability),
            requestApproval = {
                approvals++
                ApprovalDecision.APPROVE_ONCE
            },
        )
        assertTrue(result.successful)
        assertEquals(1, approvals)
    }

    @Test
    fun allowlistBlocksToolBeforeExecution() = runTest {
        val result = ToolRegistry(listOf(provider)).invoke(
            call(),
            SessionId("s"),
            setOf(CapabilityId("other.read")),
            requestApproval = { ApprovalDecision.APPROVE_ONCE },
        )
        assertFalse(result.successful)
        assertEquals("TOOL_DENIED", result.failure?.code)
    }

    @Test
    fun localOnlyBlocksRemoteToolBeforeApprovalOrExecution() = runTest {
        val executions = AtomicInteger()
        val approvals = AtomicInteger()
        val remoteProvider = object : ToolProvider {
            override suspend fun listTools() = listOf(
                ToolDescriptor(
                    capabilityId = capability,
                    name = "remote-write",
                    description = "远程写入",
                    inputSchemaJson = "{\"type\":\"object\"}",
                    riskLevel = RiskLevel.HIGH,
                    local = false,
                )
            )
            override fun executor(capabilityId: CapabilityId) = ToolExecutor { call ->
                executions.incrementAndGet()
                ToolResult(call.id, true, "{}")
            }
        }

        val result = ToolRegistry(listOf(remoteProvider)).invoke(
            call = call(),
            sessionId = SessionId("s"),
            allowlist = setOf(capability),
            privacyLevel = PrivacyLevel.LOCAL_ONLY,
            requestApproval = {
                approvals.incrementAndGet()
                ApprovalDecision.APPROVE_ONCE
            },
        )

        assertFalse(result.successful)
        assertTrue(result.failure?.message?.contains("LOCAL_ONLY") == true)
        assertEquals(0, approvals.get())
        assertEquals(0, executions.get())
    }

    @Test(expected = ToolSchemaException::class)
    fun schemaRejectsUnknownArguments() = runTest {
        ToolRegistry.validateJsonSchema(
            """{"type":"object","additionalProperties":false}""",
            """{"unexpected":1}""",
        )
    }

    @Test
    fun invalidArgumentsReturnRepairableFailureWithoutExecution() = runTest {
        val executions = AtomicInteger()
        val validatingProvider = provider(RiskLevel.LOW) { call ->
            executions.incrementAndGet()
            ToolResult(call.id, true, "{}")
        }
        val result = ToolRegistry(listOf(validatingProvider)).invoke(
            call().copy(argumentsJson = "{\"unexpected\":1}"),
            SessionId("s"),
            setOf(capability),
            requestApproval = { ApprovalDecision.APPROVE_ONCE },
        )

        assertFalse(result.successful)
        assertEquals("TOOL_SCHEMA_INVALID", result.failure?.code)
        assertTrue(result.failure?.recoverable == true)
        assertEquals(0, executions.get())
    }

    @Test
    fun mediumGrantIsScopedToSessionAndBlockedNeverExecutes() = runTest {
        val executions = AtomicInteger()
        val approvals = AtomicInteger()
        val medium = ToolRegistry(listOf(provider(RiskLevel.MEDIUM) { call ->
            executions.incrementAndGet()
            ToolResult(call.id, true, "{}")
        }))
        repeat(2) { index ->
            medium.invoke(
                call().copy(id = ToolCallId("medium-$index")),
                SessionId("same-session"),
                setOf(capability),
                requestApproval = {
                    approvals.incrementAndGet()
                    ApprovalDecision.APPROVE_SESSION
                },
            )
        }
        assertEquals(1, approvals.get())
        assertEquals(2, executions.get())

        val blocked = ToolRegistry(listOf(provider(RiskLevel.BLOCKED) { call ->
            executions.incrementAndGet()
            ToolResult(call.id, true, "{}")
        })).invoke(
            call().copy(id = ToolCallId("blocked")),
            SessionId("s"),
            setOf(capability),
            requestApproval = { ApprovalDecision.APPROVE_ONCE },
        )
        assertFalse(blocked.successful)
        assertEquals(2, executions.get())
    }

    private fun provider(
        risk: RiskLevel,
        executor: suspend (ToolCall) -> ToolResult,
    ) = object : ToolProvider {
        override suspend fun listTools() = listOf(
            ToolDescriptor(
                capabilityId = capability,
                name = "write",
                description = "写入",
                inputSchemaJson = """{"type":"object","required":["value"],"properties":{"value":{"type":"string"}},"additionalProperties":false}""",
                riskLevel = risk,
                idempotent = false,
            )
        )
        override fun executor(capabilityId: CapabilityId) = ToolExecutor(executor)
    }

    private fun call() = ToolCall(
        ToolCallId("call"),
        RunId("run"),
        StepId("step"),
        capabilityId = capability,
        argumentsJson = "{\"value\":\"x\"}",
        idempotent = false,
    )
}
