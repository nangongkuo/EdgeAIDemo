package com.zjf.edgeai.agent.core

import com.zjf.edgeai.agent.api.ModelCapabilities
import com.zjf.edgeai.agent.api.ModelId
import com.zjf.edgeai.agent.api.ModelProvider
import com.zjf.edgeai.agent.api.ModelRequest
import com.zjf.edgeai.agent.api.ModelResponse
import com.zjf.edgeai.agent.api.RunId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelExecutionBrokerTest {
    @Test
    fun localGenerationNeverOverlaps() = runTest {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val provider = object : ModelProvider {
            override val id = "local"
            override val models = mapOf(ModelId("m") to ModelCapabilities(local = true))
            override fun generate(request: ModelRequest) = flow {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                delay(20)
                emit(ModelResponse.Completed("ok"))
                active.decrementAndGet()
            }
            override fun close() = Unit
        }
        val broker = ModelExecutionBroker(localConcurrency = 1)
        List(4) { index ->
            async {
                broker.generate(
                    provider,
                    ModelRequest(RunId("run-$index"), ModelId("m"), "", emptyList()),
                ).toList()
            }
        }.awaitAll()

        assertEquals(1, maximum.get())
    }

    @Test
    fun localWaitersAcquireInFifoOrder() = runTest {
        val release = Channel<Unit>(Channel.UNLIMITED)
        val starts = mutableListOf<String>()
        val provider = provider { request ->
            flow {
                starts += request.runId.value
                release.receive()
                emit(ModelResponse.Completed("ok"))
            }
        }
        val broker = ModelExecutionBroker()
        val jobs = (0..2).map { index ->
            async {
                broker.generate(
                    provider,
                    ModelRequest(RunId("run-$index"), ModelId("m"), "", emptyList()),
                ).toList()
            }.also { runCurrent() }
        }

        repeat(3) { release.send(Unit); runCurrent() }
        jobs.awaitAll()

        assertEquals(listOf("run-0", "run-1", "run-2"), starts)
    }

    @Test
    fun residencyEvictionNeverClosesActiveLease() = runTest {
        val release = Channel<Unit>(1)
        val provider = provider {
            flow {
                release.receive()
                emit(ModelResponse.Completed("ok"))
            }
        }
        val broker = ModelExecutionBroker()
        val generation = async {
            broker.generate(
                provider,
                ModelRequest(RunId("run"), ModelId("m"), "", emptyList()),
            ).toList()
        }
        runCurrent()
        val evicted = mutableListOf<Pair<String, String>>()

        broker.evictIdle(0) { providerId, modelId -> evicted += providerId to modelId }
        assertEquals(emptyList<Pair<String, String>>(), evicted)

        release.send(Unit)
        generation.await()
        broker.evictIdle(0) { providerId, modelId -> evicted += providerId to modelId }
        assertEquals(listOf("local" to "m"), evicted)
    }

    private fun provider(
        generation: (ModelRequest) -> kotlinx.coroutines.flow.Flow<ModelResponse>,
    ) = object : ModelProvider {
        override val id = "local"
        override val models = mapOf(ModelId("m") to ModelCapabilities(local = true))
        override fun generate(request: ModelRequest) = generation(request)
        override fun close() = Unit
    }
}
