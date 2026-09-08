package com.zjf.edgeai.agent.capabilities.sandbox

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.mozilla.javascript.ContextFactory

data class SandboxLimits(
    val timeoutMillis: Long = 2_000,
    val maxInstructions: Int = 1_000_000,
    val maxScriptChars: Int = 64_000,
    val maxInputChars: Int = 256_000,
    val maxOutputChars: Int = 256_000,
    val maxHeapDeltaBytes: Long = 16L * 1024 * 1024,
) {
    init {
        require(timeoutMillis in 1..30_000)
        require(maxInstructions in 1..10_000_000)
        require(maxScriptChars in 1..256_000)
        require(maxInputChars in 1..1_000_000)
        require(maxOutputChars in 1..1_000_000)
        require(maxHeapDeltaBytes in 1..64L * 1024 * 1024)
    }
}

interface JavaScriptSandbox {
    suspend fun execute(
        script: String,
        inputJson: String = "{}",
        injectedCapabilities: Map<String, String> = emptyMap(),
        limits: SandboxLimits = SandboxLimits(),
    ): String
}

/**
 * Rhino 只在 Android isolatedProcess 中以解释模式运行。该进程没有宿主 UID、文件与网络权限；
 * safe standard objects 和 ClassShutter 阻断 Java 反射。能力只能以 Agent 层已授权的 JSON 值注入。
 */
class IsolatedProcessJavaScriptSandbox(
    context: Context,
    private val json: Json = Json,
) : JavaScriptSandbox {
    private val appContext = context.applicationContext

    override suspend fun execute(
        script: String,
        inputJson: String,
        injectedCapabilities: Map<String, String>,
        limits: SandboxLimits,
    ): String {
        require(script.length <= limits.maxScriptChars) { "脚本超过大小限制" }
        require(inputJson.length <= limits.maxInputChars) { "脚本输入超过大小限制" }
        json.parseToJsonElement(inputJson)
        val capabilities = buildJsonObject {
            injectedCapabilities.toSortedMap().forEach { (name, value) ->
                require(name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}"))) { "非法注入能力名" }
                put(name, json.parseToJsonElement(value))
            }
        }.toString()
        val bound = connect()
        return try {
            withTimeout(limits.timeoutMillis) {
                withContext(Dispatchers.IO) {
                    bound.execute(
                        script,
                        inputJson,
                        capabilities,
                        limits.maxInstructions,
                        limits.maxOutputChars,
                        limits.maxHeapDeltaBytes,
                    )
                }
            }
        } finally {
            runCatching { appContext.unbindService(bound.connection) }
        }
    }

    private suspend fun connect(): BoundSandbox {
        val deferred = CompletableDeferred<BoundSandbox>()
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                deferred.complete(BoundSandbox(service, connection))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (!deferred.isCompleted) deferred.completeExceptionally(IllegalStateException("沙箱进程断开"))
            }

            override fun onBindingDied(name: ComponentName) {
                if (!deferred.isCompleted) deferred.completeExceptionally(IllegalStateException("沙箱进程终止"))
            }
        }
        val connected = appContext.bindIsolatedService(
            Intent(appContext, JavaScriptSandboxService::class.java),
            Context.BIND_AUTO_CREATE,
            "edge-agent-js-${UUID.randomUUID()}",
            appContext.mainExecutor,
            connection,
        )
        check(connected) { "无法绑定 JavaScript 隔离进程" }
        return runCatching { deferred.await() }.getOrElse {
            runCatching { appContext.unbindService(connection) }
            throw it
        }
    }

    private data class BoundSandbox(val binder: IBinder, val connection: ServiceConnection) {
        fun execute(
            script: String,
            inputJson: String,
            capabilitiesJson: String,
            maxInstructions: Int,
            maxOutputChars: Int,
            maxHeapDeltaBytes: Long,
        ): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(script)
                data.writeString(inputJson)
                data.writeString(capabilitiesJson)
                data.writeInt(maxInstructions)
                data.writeInt(maxOutputChars)
                data.writeLong(maxHeapDeltaBytes)
                check(binder.transact(TRANSACTION_EXECUTE, data, reply, 0)) { "沙箱 IPC 失败" }
                reply.readException()
                requireNotNull(reply.readString()) { "沙箱没有返回结果" }
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private companion object {
        const val DESCRIPTOR = "com.zjf.edgeai.agent.capabilities.JavaScriptSandbox"
        const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
    }
}

class JavaScriptSandboxService : Service() {
    private val binder = object : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_EXECUTE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            return try {
                val result = executeScript(
                    script = requireNotNull(data.readString()),
                    inputJson = requireNotNull(data.readString()),
                    capabilitiesJson = requireNotNull(data.readString()),
                    maxInstructions = data.readInt(),
                    maxOutputChars = data.readInt(),
                    maxHeapDeltaBytes = data.readLong(),
                )
                reply?.writeNoException()
                reply?.writeString(result)
                true
            } catch (failure: Throwable) {
                reply?.writeException(IllegalStateException(failure.message ?: "脚本执行失败"))
                true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun executeScript(
        script: String,
        inputJson: String,
        capabilitiesJson: String,
        maxInstructions: Int,
        maxOutputChars: Int,
        maxHeapDeltaBytes: Long,
    ): String = RhinoScriptEvaluator.execute(
        script,
        inputJson,
        capabilitiesJson,
        maxInstructions,
        maxOutputChars,
        maxHeapDeltaBytes,
    )

    private companion object {
        const val DESCRIPTOR = "com.zjf.edgeai.agent.capabilities.JavaScriptSandbox"
        const val TRANSACTION_EXECUTE = IBinder.FIRST_CALL_TRANSACTION
    }
}

internal object RhinoScriptEvaluator {
    fun execute(
        script: String,
        inputJson: String,
        capabilitiesJson: String,
        maxInstructions: Int,
        maxOutputChars: Int,
        maxHeapDeltaBytes: Long,
    ): String {
        require(maxHeapDeltaBytes in 1..MAX_HEAP_DELTA_BYTES) { "脚本内存预算无效" }
        val before = usedHeap()
        val factory = BoundedContextFactory(maxInstructions)
        val result = factory.call { context ->
            context.optimizationLevel = -1
            context.setClassShutter { false }
            val scope = context.initSafeStandardObjects(null, true)
            val bootstrap = """
                const input = Object.freeze(JSON.parse("${escape(inputJson)}"));
                const capabilities = Object.freeze(JSON.parse("${escape(capabilitiesJson)}"));
                JSON.stringify((function() { "use strict"; $script })());
            """.trimIndent()
            val result = context.evaluateString(scope, bootstrap, "skill.js", 1, null).toString()
            require(result.length <= maxOutputChars) { "脚本输出超过限制" }
            result
        }
        require(usedHeap() - before <= maxHeapDeltaBytes) { "脚本超过内存增量限制" }
        return result
    }

    private class BoundedContextFactory(private val maxInstructions: Int) : ContextFactory() {
        private var observed = 0

        override fun makeContext(): org.mozilla.javascript.Context = super.makeContext().apply {
            instructionObserverThreshold = 10_000
        }

        override fun observeInstructionCount(context: org.mozilla.javascript.Context, instructionCount: Int) {
            observed += instructionCount
            if (observed > maxInstructions) throw SecurityException("脚本超过指令限制")
        }
    }

    private fun escape(value: String): String = Json.encodeToString(
        JsonPrimitive.serializer(),
        JsonPrimitive(value),
    ).removeSurrounding("\"")

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private const val MAX_HEAP_DELTA_BYTES = 64L * 1024 * 1024
}
