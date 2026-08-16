package com.zjf.edgeai.runtime

import java.util.Locale

internal data class BackendSelection(
    val effectiveBackend: RuntimeBackend,
    val gpuFailure: Throwable? = null
)

internal class BackendFallbackException(
    val gpuFailure: Throwable,
    val cpuFailure: Throwable
) : IllegalStateException("GPU and CPU initialization both failed", cpuFailure)

internal data class AndroidDeviceSignature(
    val fingerprint: String,
    val model: String,
    val manufacturer: String,
    val brand: String,
    val device: String,
    val hardware: String
)

internal fun emulatorGpuPreflightFailure(
    preferred: RuntimeBackend,
    device: AndroidDeviceSignature
): IllegalStateException? {
    if (preferred != RuntimeBackend.GPU || !device.isLikelyAndroidEmulator()) return null
    return IllegalStateException(
        "检测到 Android 模拟器；LiteRT-LM GPU 需要的 OpenCL 运行时不可用，已在初始化前选择 CPU"
    )
}

private fun AndroidDeviceSignature.isLikelyAndroidEmulator(): Boolean {
    val fingerprintValue = fingerprint.lowercase(Locale.ROOT)
    val modelValue = model.lowercase(Locale.ROOT)
    val manufacturerValue = manufacturer.lowercase(Locale.ROOT)
    val brandValue = brand.lowercase(Locale.ROOT)
    val deviceValue = device.lowercase(Locale.ROOT)
    val hardwareValue = hardware.lowercase(Locale.ROOT)

    return fingerprintValue.startsWith("generic") ||
        fingerprintValue.startsWith("unknown") ||
        modelValue.contains("google_sdk") ||
        modelValue.contains("emulator") ||
        modelValue.contains("android sdk built for") ||
        manufacturerValue.contains("genymotion") ||
        (brandValue.startsWith("generic") && deviceValue.startsWith("generic")) ||
        hardwareValue.contains("goldfish") ||
        hardwareValue.contains("ranchu")
}

internal fun selectRuntimeBackend(
    preferred: RuntimeBackend,
    gpuPreflightFailure: Throwable? = null,
    initialize: (RuntimeBackend) -> Unit
): BackendSelection {
    if (preferred == RuntimeBackend.CPU) {
        initialize(RuntimeBackend.CPU)
        return BackendSelection(RuntimeBackend.CPU)
    }

    if (gpuPreflightFailure != null) {
        initialize(RuntimeBackend.CPU)
        return BackendSelection(RuntimeBackend.CPU, gpuPreflightFailure)
    }

    val gpuFailure = runCatching { initialize(RuntimeBackend.GPU) }.exceptionOrNull()
    if (gpuFailure == null) return BackendSelection(RuntimeBackend.GPU)

    try {
        initialize(RuntimeBackend.CPU)
    } catch (cpuFailure: Throwable) {
        throw BackendFallbackException(gpuFailure, cpuFailure)
    }
    return BackendSelection(RuntimeBackend.CPU, gpuFailure)
}
