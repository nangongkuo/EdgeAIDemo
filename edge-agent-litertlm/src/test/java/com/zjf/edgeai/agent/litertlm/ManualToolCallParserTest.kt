package com.zjf.edgeai.agent.litertlm

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualToolCallParserTest {
    @Test
    fun parsesFencedManualToolCall() {
        val calls = EdgeLiteRtModelAdapter.parseManualToolCalls(
            """```json
                {"name":"weather","arguments":{"city":"上海"}}
                ```""".trimIndent()
        )

        assertEquals("weather", calls.single().name)
        assertEquals("{\"city\":\"上海\"}", calls.single().argumentsJson)
    }
}
