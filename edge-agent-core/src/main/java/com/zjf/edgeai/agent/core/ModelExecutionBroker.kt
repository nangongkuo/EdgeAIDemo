package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.ModelProvider
import com.zjf.edgeai.agent.api.ModelRequest
import com.zjf.edgeai.agent.api.ModelResponse
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ModelLease(
    val providerId: String,
    val modelId: String,
    val local: Boolean,
    val acquiredAtNanos: Long,
)

/** FIFO permit pool，避免长任务或 Worker 持续插队。 */
private class FairPermitPool(private val capacity: Int) {
    private val mutex = Mutex()
    private var available = capacity
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun acquire() {
        val waiter = CompletableDeferred<Unit>()
        val acquiredImmediately = mutex.withLock {
            if (available > 0 && waiters.isEmpty()) {
                available--
                true
            } else {
                waiters.addLast(waiter)
                false
            }
        }
        if (!acquiredImmediately) {
            try {
                waiter.await()
            } catch (failure: Throwable) {
                val removedBeforeGrant = mutex.withLock { waiters.remove(waiter) }
                if (!removedBeforeGrant && waiter.isCompleted) {
                    withContext(NonCancellable) { release() }
                }
                throw failure
            }
        }
    }

    suspend fun release() {
        while (true) {
            val next = mutex.withLock {
                var candidate: CompletableDeferred<Unit>? = null
                while (waiters.isNotEmpty() && candidate == null) {
                    waiters.removeFirst().takeUnless { it.isCancelled }?.let { candidate = it }
                }
                if (candidate == null) {
                    available++
                    check(available <= capacity) { "Model permit 重复释放" }
                }
                candidate
            }
            if (next == null || next.complete(Unit)) return
        }
    }
}

class ModelExecutionBroker(
    localConcurrency: Int = 1,
    private val remoteConcurrency: Int = 3,
) {
    init {
        require(localConcurrency == 1) { "端侧 Agent SDK 1.0 仅支持单路本地推理" }
        require(remoteConcurrency > 0)
    }

    private val localPermits = FairPermitPool(localConcurrency)
    private val remotePermits = FairPermitPool(remoteConcurrency)
    private data class EngineKey(val providerId: String, val modelId: String)
    private val enginePermits = ConcurrentHashMap<EngineKey, FairPermitPool>()
    private val activeLeases = ConcurrentHashMap<EngineKey, AtomicInteger>()
    private val residencyMutex = Mutex()
    private val residentLru = linkedMapOf<EngineKey, Long>()
    @Volatile private var residentLimit = 2

    fun generate(provider: ModelProvider, request: ModelRequest): Flow<ModelResponse> = flow {
        val modelId = request.modelId ?: provider.models.keys.firstOrNull()
            ?: error("Provider ${provider.id} 没有模型")
        val capabilities = provider.models[modelId] ?: error("Provider 不支持 ${modelId.value}")
        val global = if (capabilities.local) localPermits else remotePermits
        val engineKey = EngineKey(provider.id, modelId.value)
        val engine = enginePermits.getOrPut(engineKey) {
            FairPermitPool(if (capabilities.local) 1 else remoteConcurrency)
        }
        global.acquire()
        try {
            engine.acquire()
            activeLeases.getOrPut(engineKey) { AtomicInteger() }.incrementAndGet()
            try {
                val lease = ModelLease(
                    provider.id,
                    modelId.value,
                    capabilities.local,
                    System.nanoTime(),
                )
                if (lease.local) touchResident(engineKey)
                provider.generate(request).collect { emit(it) }
            } finally {
                activeLeases[engineKey]?.decrementAndGet()
                engine.release()
            }
        } finally {
            global.release()
        }
    }

    suspend fun setLowMemoryDevice(lowMemory: Boolean) {
        residentLimit = if (lowMemory) 1 else 2
    }

    suspend fun evictIdle(
        maxResidentProviders: Int = residentLimit,
        evictModel: suspend (providerId: String, modelId: String) -> Unit,
    ) {
        require(maxResidentProviders >= 0)
        val victims = residencyMutex.withLock {
            val count = (residentLru.size - maxResidentProviders).coerceAtLeast(0)
            residentLru.entries.asSequence()
                .filter { (key, _) -> activeLeases[key]?.get() == 0 }
                .sortedBy { it.value }
                .take(count)
                .map { it.key }
                .toList().also { keys ->
                keys.forEach(residentLru::remove)
            }
        }
        victims.forEach { key -> evictModel(key.providerId, key.modelId) }
    }

    private suspend fun touchResident(engineKey: EngineKey) {
        residencyMutex.withLock {
            residentLru.remove(engineKey)
            residentLru[engineKey] = System.nanoTime()
        }
    }
}
