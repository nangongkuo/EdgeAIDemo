package com.zjf.edgeai.agent.capabilities

import com.zjf.edgeai.agent.api.ApprovalDecision
import com.zjf.edgeai.agent.api.ApprovalRequest
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.SessionId
import com.zjf.edgeai.agent.api.SkillBackend
import com.zjf.edgeai.agent.api.SkillManifest
import com.zjf.edgeai.agent.api.SkillSignatureVerifier
import com.zjf.edgeai.agent.api.ToolCall
import com.zjf.edgeai.agent.api.ToolCallId
import com.zjf.edgeai.agent.api.ToolDescriptor
import com.zjf.edgeai.agent.api.ToolResult
import com.zjf.edgeai.agent.api.ToolRuntime
import com.zjf.edgeai.agent.capabilities.sandbox.JavaScriptSandbox
import com.zjf.edgeai.agent.capabilities.sandbox.SandboxLimits
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillRuntimeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val json = Json { encodeDefaults = true }
    private val capability = CapabilityId("local.lookup")

    @Test
    fun installedSkillIsDiscoveredAndExecutedThroughUnifiedToolRegistry() = runTest {
        val store = store()
        store.installFromPrivateDirectory(
            skillPackage("explain", SkillBackend.INSTRUCTION, "按证据解释结果")
        )
        val registry = ToolRegistry(
            providers = emptyList(),
            skillRuntime = SkillRuntime(store, EchoSandbox()),
        )
        val descriptor = registry.descriptors().single()

        val result = registry.invoke(
            call = ToolCall(
                ToolCallId("skill-call"),
                RunId("run"),
                com.zjf.edgeai.agent.api.StepId("skill-step"),
                capabilityId = descriptor.capabilityId,
                argumentsJson = "{}",
                idempotent = descriptor.idempotent,
            ),
            sessionId = SessionId("session"),
            requestApproval = { ApprovalDecision.APPROVE_ONCE },
        )

        assertEquals(CapabilityId("skill.explain"), descriptor.capabilityId)
        assertTrue(result.successful)
        assertTrue(result.contentJson.contains("按证据解释结果"))
    }

    @Test
    fun metadataLoadsBeforeIntegrityCheckedInstructions() {
        val source = skillPackage(
            id = "lazy",
            backend = SkillBackend.INSTRUCTION,
            content = "原始指令",
        )
        val store = store()
        store.installFromPrivateDirectory(source)

        assertEquals("lazy", store.list().single().id)
        File(temporaryFolder.root, "installed/lazy/SKILL.md").writeText("已篡改")

        val failure = runCatching { store.load("lazy") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("SHA-256"))
    }

    @Test
    fun signedSkillRequiresAndUsesVerifier() {
        val source = skillPackage(
            id = "signed",
            backend = SkillBackend.INSTRUCTION,
            content = "安全指令",
            signature = "trusted-signature",
        )
        val withoutVerifier = runCatching {
            store().installFromPrivateDirectory(source)
        }.exceptionOrNull()
        assertTrue(withoutVerifier?.message.orEmpty().contains("签名验证器"))

        val verifiedStore = SkillStore(
            File(temporaryFolder.root, "verified"),
            temporaryFolder.root,
            SkillSignatureVerifier { _, _, signature -> signature == "trusted-signature" },
        )
        assertEquals("signed", verifiedStore.installFromPrivateDirectory(source).id)
    }

    @Test
    fun declarativeWorkflowCannotEscapeManifestAllowlist() = runTest {
        val undeclared = CapabilityId("remote.delete")
        val workflow = json.encodeToString(
            DeclarativeSkillWorkflow.serializer(),
            DeclarativeSkillWorkflow(listOf(DeclarativeSkillStep(undeclared, "{}"))),
        )
        val store = store()
        store.installFromPrivateDirectory(
            skillPackage("workflow", SkillBackend.DECLARATIVE_WORKFLOW, workflow)
        )
        val toolRuntime = RecordingToolRuntime()
        val runtime = SkillRuntime(store, EchoSandbox())

        val failure = runCatching {
            runtime.execute(
                "workflow",
                RunId("run"),
                SessionId("session"),
                toolRuntime = toolRuntime,
                requestApproval = { ApprovalDecision.APPROVE_ONCE },
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("未声明能力"))
        assertEquals(0, toolRuntime.calls.size)
    }

    @Test
    fun declaredWorkflowAndJavaScriptBackendsAreControlled() = runTest {
        val workflow = json.encodeToString(
            DeclarativeSkillWorkflow.serializer(),
            DeclarativeSkillWorkflow(listOf(DeclarativeSkillStep(capability, "{\"q\":\"x\"}"))),
        )
        val store = store()
        store.installFromPrivateDirectory(
            skillPackage(
                "workflow-ok",
                SkillBackend.DECLARATIVE_WORKFLOW,
                workflow,
                setOf(capability),
            )
        )
        store.installFromPrivateDirectory(
            skillPackage(
                "script-ok",
                SkillBackend.JAVASCRIPT_SANDBOX,
                "return input;",
                setOf(capability),
            )
        )
        val tools = RecordingToolRuntime()
        val runtime = SkillRuntime(store, EchoSandbox())

        val workflowResult = runtime.execute(
            "workflow-ok",
            RunId("run"),
            SessionId("session"),
            toolRuntime = tools,
            requestApproval = { ApprovalDecision.APPROVE_ONCE },
        )
        assertEquals(1, workflowResult.toolResults.size)
        assertEquals(setOf(capability), tools.lastAllowlist)

        val scriptResult = runtime.execute(
            "script-ok",
            RunId("run"),
            SessionId("session"),
            toolRuntime = tools,
            inputJson = "{\"value\":1}",
            injectedCapabilities = mapOf(capability to "{\"allowed\":true}"),
            requestApproval = { ApprovalDecision.APPROVE_ONCE },
        )
        assertEquals("{\"value\":1}", scriptResult.content)
    }

    private fun store() = SkillStore(
        File(temporaryFolder.root, "installed"),
        temporaryFolder.root,
    )

    private fun skillPackage(
        id: String,
        backend: SkillBackend,
        content: String,
        capabilities: Set<CapabilityId> = emptySet(),
        signature: String? = null,
    ): File {
        val directory = File(temporaryFolder.root, "source-$id").apply { mkdirs() }
        File(directory, "SKILL.md").writeText(content)
        val sha = digest(directory, listOf("SKILL.md"))
        val manifest = SkillManifest(
            id = id,
            version = "1.0.0",
            name = id,
            description = "测试 Skill",
            backend = backend,
            capabilities = capabilities,
            sha256 = sha,
            signature = signature,
        )
        File(directory, "manifest.json").writeText(
            json.encodeToString(SkillManifest.serializer(), manifest)
        )
        return directory
    }

    private fun digest(directory: File, paths: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paths.sorted().forEach { path ->
            digest.update(path.encodeToByteArray())
            digest.update(File(directory, path).readBytes())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inner class RecordingToolRuntime : ToolRuntime {
        val calls = mutableListOf<ToolCall>()
        var lastAllowlist = emptySet<CapabilityId>()
        override suspend fun descriptors(allowlist: Set<CapabilityId>) = listOf(
            ToolDescriptor(capability, "lookup", "查询", "{\"type\":\"object\"}")
        )
        override suspend fun invoke(
            call: ToolCall,
            sessionId: SessionId,
            allowlist: Set<CapabilityId>,
            privacyLevel: PrivacyLevel,
            requestApproval: suspend (ApprovalRequest) -> ApprovalDecision,
            onExecutionStarted: suspend () -> Unit,
        ): ToolResult {
            calls += call
            lastAllowlist = allowlist
            onExecutionStarted()
            return ToolResult(call.id, true, "{\"ok\":true}")
        }
        override fun close() = Unit
    }

    private class EchoSandbox : JavaScriptSandbox {
        override suspend fun execute(
            script: String,
            inputJson: String,
            injectedCapabilities: Map<String, String>,
            limits: SandboxLimits,
        ) = inputJson
    }
}
