package com.zjf.edgeai.agent.capabilities

import com.zjf.edgeai.agent.api.A2aAgentConfig
import com.zjf.edgeai.agent.api.AgentId
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.McpServerConfig
import com.zjf.edgeai.agent.api.PrivacyLevel
import com.zjf.edgeai.agent.api.RunBudget
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.WorkerId
import com.zjf.edgeai.agent.api.WorkerRun
import java.util.concurrent.atomic.AtomicLong
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpAndA2aContractTest {
    @Test
    fun mcpDiscoveryAtomicallyReplacesChangedSchema() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(jsonResponse(INITIALIZE).addHeader("Mcp-Session-Id", "session-1"))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(jsonResponse(toolsResponse("first", "string")))
            server.enqueue(jsonResponse(toolsResponse("second", "integer")))
            server.start()
            val clock = AtomicLong(1_000)
            val provider = McpToolProvider(
                McpServerConfig("server", server.url("/mcp").toString()),
                now = clock::get,
                cacheTtlMillis = 10,
            )

            val first = provider.listTools().single()
            clock.addAndGet(11)
            val second = provider.listTools().single()

            assertEquals("first", first.name)
            assertEquals("second", second.name)
            assertNull(provider.executor(first.capabilityId))
            assertNotNull(provider.executor(second.capabilityId))
            assertTrue(second.inputSchemaJson.contains("integer"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun expiredMcpSessionReconnectsOnceBeforeDiscovery() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(jsonResponse(INITIALIZE).addHeader("Mcp-Session-Id", "session-1"))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(jsonResponse(INITIALIZE).addHeader("Mcp-Session-Id", "session-2"))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(jsonResponse(toolsResponse("after-reconnect", "string")))
            server.start()
            val provider = McpToolProvider(
                McpServerConfig("server", server.url("/mcp").toString())
            )

            assertEquals("after-reconnect", provider.listTools().single().name)
            assertEquals(6, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun a2aLocalOnlyIsDeniedWithoutNetworkAndFailureIsRecoverable() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()
            val provider = A2aRemoteWorkerProvider(
                A2aAgentConfig("remote", server.url("/a2a").toString(), "远程 Agent")
            )

            val denied = provider.execute(worker(PrivacyLevel.LOCAL_ONLY))
            assertEquals("A2A_LOCAL_ONLY_DENIED", denied.failure?.code)
            assertEquals(0, server.requestCount)

            val unavailable = provider.execute(worker(PrivacyLevel.PRIVATE))
            assertFalse(unavailable.successful)
            assertTrue(unavailable.untrusted)
            assertEquals("A2A_UNAVAILABLE", unavailable.failure?.code)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun a2aUsesJsonRpcMessageSendAndParsesTaskResult() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(
                jsonResponse(
                    """{
                      "jsonrpc":"2.0","id":"worker","result":{
                        "kind":"task","id":"remote-task",
                        "status":{"state":"completed","message":{"parts":[
                          {"kind":"text","text":"远程结论"}
                        ]}},
                        "artifacts":[{"parts":[{"kind":"text","text":"证据 A"}]}]
                      }
                    }""".trimIndent()
                )
            )
            server.start()
            val provider = A2aRemoteWorkerProvider(
                A2aAgentConfig("remote", server.url("/a2a").toString(), "远程 Agent")
            )

            val result = provider.execute(worker(PrivacyLevel.PRIVATE))
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

            assertTrue(result.successful)
            assertTrue(result.untrusted)
            assertTrue(result.summary.contains("远程结论"))
            assertEquals(listOf("证据 A"), result.evidence)
            assertEquals("2.0", request["jsonrpc"]!!.jsonPrimitive.content)
            assertEquals("message/send", request["method"]!!.jsonPrimitive.content)
        } finally {
            server.shutdown()
        }
    }

    private fun worker(privacy: PrivacyLevel) = WorkerRun(
        workerId = WorkerId("worker"),
        parentRunId = RunId("run"),
        agentId = AgentId("remote"),
        objective = "查询",
        input = "输入",
        capabilityAllowlist = setOf(CapabilityId("lookup")),
        budget = RunBudget(),
        privacyLevel = privacy,
        depth = 1,
    )

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun toolsResponse(name: String, type: String) = """
        {"jsonrpc":"2.0","id":"1","result":{"tools":[{
          "name":"$name","description":"测试","inputSchema":{"type":"object","properties":{"value":{"type":"$type"}}}
        }]}}
    """.trimIndent()

    private companion object {
        const val INITIALIZE =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"
    }
}
