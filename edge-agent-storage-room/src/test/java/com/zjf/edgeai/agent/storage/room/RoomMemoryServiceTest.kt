package com.zjf.edgeai.agent.storage.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zjf.edgeai.agent.api.MemoryCandidate
import com.zjf.edgeai.agent.api.MemoryEmbeddingProvider
import com.zjf.edgeai.agent.api.MemoryQuery
import com.zjf.edgeai.agent.api.MemorySensitivity
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.SessionId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomMemoryServiceTest {
    private lateinit var database: AgentDatabase
    private val clock = AtomicLong(1_000)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `记忆支持会话隔离去重过期删除和敏感信息排除`() = runBlocking {
        val service = RoomMemoryService(database, now = clock::get)
        val sessionA = SessionId("session-a")
        val sessionB = SessionId("session-b")
        val first = service.commit(candidate("用户喜欢深色主题", "run-1", sessionA))!!
        clock.incrementAndGet()
        val merged = service.commit(candidate("  用户喜欢深色主题  ", "run-2", sessionA))!!
        service.commit(candidate("用户喜欢深色主题", "run-3", sessionB))
        service.commit(candidate("即将过期", "run-4", sessionA, expiresAt = 1_001))

        assertEquals(first.id, merged.id)
        assertEquals(2, merged.sourceRunIds.size)
        assertEquals(1, service.search(MemoryQuery("深色主题", sessionA)).size)
        assertEquals(1, service.search(MemoryQuery("深色主题", sessionB)).size)
        clock.set(2_000)
        assertTrue(service.search(MemoryQuery("即将过期", sessionA)).isEmpty())
        assertNull(service.commit(candidate("api_key=very-secret-value", "run-5", sessionA)))
        assertNull(
            service.commit(
                candidate("禁止保存", "run-6", sessionA).copy(sensitivity = MemorySensitivity.FORBIDDEN)
            )
        )
        assertTrue(service.delete(first.id))
        assertTrue(service.search(MemoryQuery("深色主题", sessionA)).isEmpty())
    }

    @Test
    fun `可选向量提供方能增强无关键词命中的语义排序`() = runBlocking {
        val embeddings = MemoryEmbeddingProvider { text ->
            when {
                "猫" in text || "宠物" in text -> floatArrayOf(1f, 0f)
                else -> floatArrayOf(0f, 1f)
            }
        }
        val service = RoomMemoryService(database, now = clock::get, embeddingProvider = embeddings)
        service.commit(candidate("家里养了一只猫", "run-cat", null))
        service.commit(candidate("常用交通方式是地铁", "run-metro", null))

        val results = service.search(MemoryQuery("宠物偏好", limit = 2))
        assertEquals("家里养了一只猫", results.first().content)
    }

    @Test
    fun `禁用长期记忆后不写也不读`() = runBlocking {
        val service = RoomMemoryService(database, enabled = { false })
        assertNull(service.commit(candidate("不会保存", "run", null)))
        assertTrue(service.search(MemoryQuery("保存")).isEmpty())
    }

    private fun candidate(
        content: String,
        runId: String,
        sessionId: SessionId?,
        expiresAt: Long? = null,
    ) = MemoryCandidate(
        content = content,
        sourceRunId = RunId(runId),
        sessionId = sessionId,
        expiresAtEpochMillis = expiresAt,
    )
}
