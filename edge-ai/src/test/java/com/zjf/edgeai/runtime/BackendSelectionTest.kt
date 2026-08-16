package com.zjf.edgeai.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BackendSelectionTest {
    @Test
    fun gpuSuccessDoesNotTryCpu() {
        val attempts = mutableListOf<RuntimeBackend>()

        val result = selectRuntimeBackend(RuntimeBackend.GPU) { attempts += it }

        assertEquals(listOf(RuntimeBackend.GPU), attempts)
        assertEquals(RuntimeBackend.GPU, result.effectiveBackend)
        assertNull(result.gpuFailure)
    }

    @Test
    fun gpuFailureFallsBackToCpuAndKeepsReason() {
        val attempts = mutableListOf<RuntimeBackend>()
        val gpuFailure = IllegalStateException("OpenCL unavailable")

        val result = selectRuntimeBackend(RuntimeBackend.GPU) { backend ->
            attempts += backend
            if (backend == RuntimeBackend.GPU) throw gpuFailure
        }

        assertEquals(listOf(RuntimeBackend.GPU, RuntimeBackend.CPU), attempts)
        assertEquals(RuntimeBackend.CPU, result.effectiveBackend)
        assertSame(gpuFailure, result.gpuFailure)
    }

    @Test
    fun cpuPreferenceNeverTriesGpu() {
        val attempts = mutableListOf<RuntimeBackend>()

        val result = selectRuntimeBackend(RuntimeBackend.CPU) { attempts += it }

        assertEquals(listOf(RuntimeBackend.CPU), attempts)
        assertEquals(RuntimeBackend.CPU, result.effectiveBackend)
    }

    @Test
    fun androidStudioEmulatorPreflightSelectsCpuWithoutTryingGpu() {
        val attempts = mutableListOf<RuntimeBackend>()
        val reason = emulatorGpuPreflightFailure(
            preferred = RuntimeBackend.GPU,
            device = AndroidDeviceSignature(
                fingerprint = "generic/sdk_gphone64_arm64/emu64a",
                model = "sdk_gphone64_arm64",
                manufacturer = "Google",
                brand = "google",
                device = "emu64a",
                hardware = "ranchu"
            )
        )

        val result = selectRuntimeBackend(RuntimeBackend.GPU, reason) { attempts += it }

        assertNotNull(reason)
        assertEquals(listOf(RuntimeBackend.CPU), attempts)
        assertEquals(RuntimeBackend.CPU, result.effectiveBackend)
        assertSame(reason, result.gpuFailure)
    }

    @Test
    fun physicalDeviceKeepsGpuFirstBehavior() {
        val attempts = mutableListOf<RuntimeBackend>()
        val reason = emulatorGpuPreflightFailure(
            preferred = RuntimeBackend.GPU,
            device = AndroidDeviceSignature(
                fingerprint = "Xiaomi/mayfly/mayfly:15/AP3A.240905.015.A2/OS2.0.5.0.VLBCNXM:user/release-keys",
                model = "2206123SC",
                manufacturer = "Xiaomi",
                brand = "Xiaomi",
                device = "mayfly",
                hardware = "qcom"
            )
        )

        val result = selectRuntimeBackend(RuntimeBackend.GPU, reason) { attempts += it }

        assertNull(reason)
        assertEquals(listOf(RuntimeBackend.GPU), attempts)
        assertEquals(RuntimeBackend.GPU, result.effectiveBackend)
    }

    @Test
    fun bothFailuresAreRetained() {
        val gpuFailure = IllegalStateException("gpu")
        val cpuFailure = IllegalArgumentException("cpu")

        val failure = try {
            selectRuntimeBackend(RuntimeBackend.GPU) { backend ->
                if (backend == RuntimeBackend.GPU) throw gpuFailure else throw cpuFailure
            }
            null
        } catch (caught: BackendFallbackException) {
            caught
        }

        assertNotNull(failure)
        assertSame(gpuFailure, failure?.gpuFailure)
        assertSame(cpuFailure, failure?.cpuFailure)
    }
}
