package com.zjf.edgeai.agent.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentEventSerializationTest {
    private val json = Json { classDiscriminator = "eventType"; encodeDefaults = true }

    @Test
    fun terminalEventRoundTripsWithoutImplementationTypes() {
        val event: AgentEvent = AgentEvent.Terminal(
            runId = RunId("run-1"),
            sequence = 7,
            timestampEpochMillis = 42,
            state = RunState.COMPLETED,
            output = "完成",
        )

        val encoded = json.encodeToString(AgentEvent.serializer(), event)
        val decoded = json.decodeFromString(AgentEvent.serializer(), encoded)

        assertEquals(event, decoded)
        assertEquals(EDGE_AGENT_PROTOCOL_VERSION, decoded.protocolVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun terminalEventRejectsNonTerminalState() {
        AgentEvent.Terminal(RunId("run-1"), 1, 1, RunState.RUNNING)
    }
}
