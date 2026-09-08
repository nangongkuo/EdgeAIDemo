package com.zjf.edgeai.agent.storage.room

import android.content.Context
import androidx.room.Room
import com.zjf.edgeai.agent.api.ArtifactStore
import com.zjf.edgeai.agent.api.MemoryService
import com.zjf.edgeai.agent.api.RunStore

class RoomAgentStorage private constructor(
    private val database: AgentDatabase,
    val runStore: RunStore,
    val memoryService: MemoryService,
    val artifactStore: ArtifactStore,
) : AutoCloseable {
    override fun close() = database.close()

    companion object {
        fun create(context: Context, databaseName: String, memoryEnabled: () -> Boolean = { true }): RoomAgentStorage {
            val appContext = context.applicationContext
            val database = Room.databaseBuilder(appContext, AgentDatabase::class.java, databaseName)
                .addMigrations(AgentDatabase.MIGRATION_1_2)
                .build()
            return RoomAgentStorage(
                database,
                RoomRunStore(database),
                RoomMemoryService(database, memoryEnabled),
                RoomArtifactStore(appContext, database),
            )
        }
    }
}
