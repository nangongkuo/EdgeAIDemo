package com.zjf.edgeai.agent.capabilities.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhinoScriptEvaluatorTest {
    @Test
    fun exposesOnlyJsonInputAndInjectedCapabilities() {
        val result = RhinoScriptEvaluator.execute(
            script = "return {sum: input.a + capabilities.math.b};",
            inputJson = "{\"a\":2}",
            capabilitiesJson = "{\"math\":{\"b\":3}}",
            maxInstructions = 100_000,
            maxOutputChars = 1_000,
            maxHeapDeltaBytes = 16L * 1024 * 1024,
        )
        assertEquals("{\"sum\":5}", result)
    }

    @Test
    fun instructionAndOutputLimitsFailStructurally() {
        val instructionFailure = runCatching {
            RhinoScriptEvaluator.execute(
                "while (true) {}",
                "{}",
                "{}",
                20_000,
                1_000,
                16L * 1024 * 1024,
            )
        }.exceptionOrNull()
        assertTrue(instructionFailure?.message.orEmpty().contains("指令限制"))

        val outputFailure = runCatching {
            RhinoScriptEvaluator.execute(
                "return '0123456789';",
                "{}",
                "{}",
                100_000,
                5,
                16L * 1024 * 1024,
            )
        }.exceptionOrNull()
        assertTrue(outputFailure?.message.orEmpty().contains("输出超过限制"))
    }

    @Test
    fun javaRuntimeIsNotAmbientCapability() {
        val failure = runCatching {
            RhinoScriptEvaluator.execute(
                "return Packages.java.lang.System.getenv();",
                "{}",
                "{}",
                100_000,
                1_000,
                16L * 1024 * 1024,
            )
        }.exceptionOrNull()
        assertTrue(failure != null)
    }
}
