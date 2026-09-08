package com.zjf.edgeai.agent.storage.room

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(entity: SessionEntity)

    @Query("UPDATE sessions SET updatedAtEpochMillis = :timestamp WHERE sessionId = :sessionId")
    suspend fun touchSession(sessionId: String, timestamp: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(entity: RunEntity)

    @Query("SELECT * FROM runs WHERE runId = :runId")
    suspend fun run(runId: String): RunEntity?

    @Update
    suspend fun updateRun(entity: RunEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(entity: EventEntity)

    @Query("SELECT * FROM events WHERE runId = :runId ORDER BY sequence ASC")
    suspend fun events(runId: String): List<EventEntity>

    @Query("SELECT * FROM events WHERE runId = :runId ORDER BY sequence ASC")
    fun observeEvents(runId: String): Flow<List<EventEntity>>

    @Query(
        "SELECT runId FROM runs WHERE state NOT IN " +
            "('CREATED', 'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED') ORDER BY updatedAtEpochMillis ASC"
    )
    suspend fun recoverableRunIds(): List<String>

    @Query(
        "SELECT * FROM runs WHERE sessionId = :sessionId AND state IN ('COMPLETED', 'PARTIAL') " +
            "ORDER BY updatedAtEpochMillis DESC LIMIT :limit"
    )
    suspend fun sessionRuns(sessionId: String, limit: Int): List<RunEntity>
}

@Dao
interface ExecutionGraphDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgentNode(entity: AgentNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkerRun(entity: WorkerRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStep(entity: StepEntity)

    @Query("SELECT * FROM agent_nodes WHERE runId = :runId ORDER BY nodeId")
    suspend fun agentNodes(runId: String): List<AgentNodeEntity>

    @Query("SELECT * FROM worker_runs WHERE runId = :runId ORDER BY workerId")
    suspend fun workerRuns(runId: String): List<WorkerRunEntity>

    @Query("SELECT * FROM steps WHERE runId = :runId ORDER BY stepId")
    suspend fun steps(runId: String): List<StepEntity>
}

@Dao
interface RecoveryDao {
    @Query("SELECT * FROM tool_calls WHERE runId = :runId AND state IN ('RUNNING', 'UNKNOWN')")
    suspend fun uncertainToolCalls(runId: String): List<ToolCallEntity>

    @Query("SELECT * FROM checkpoints WHERE runId = :runId ORDER BY createdAtEpochMillis DESC LIMIT 1")
    suspend fun latestCheckpoint(runId: String): CheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(entity: CheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertToolCall(entity: ToolCallEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApproval(entity: ApprovalEntity)

    @Query("SELECT * FROM tool_calls WHERE runId = :runId ORDER BY callId")
    suspend fun toolCalls(runId: String): List<ToolCallEntity>

    @Query("SELECT * FROM approvals WHERE runId = :runId ORDER BY approvalId")
    suspend fun approvals(runId: String): List<ApprovalEntity>

    @Query("SELECT * FROM checkpoints WHERE runId = :runId ORDER BY createdAtEpochMillis")
    suspend fun checkpoints(runId: String): List<CheckpointEntity>
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFts(entity: MemoryFtsEntity)

    @Transaction
    suspend fun upsertMemory(entity: MemoryEntity) {
        upsert(entity)
        upsertFts(MemoryFtsEntity(entity.memoryId, entity.content))
    }

    @Query(
        "SELECT * FROM memories WHERE normalizedHash = :hash AND " +
            "((:sessionId IS NULL AND sessionId IS NULL) OR sessionId = :sessionId) LIMIT 1"
    )
    suspend fun byNormalizedHash(hash: String, sessionId: String?): MemoryEntity?

    @Query(
        "SELECT * FROM memories WHERE (:sessionId IS NULL OR sessionId = :sessionId OR sessionId IS NULL) " +
            "AND (expiresAtEpochMillis IS NULL OR expiresAtEpochMillis > :now) " +
            "AND content LIKE '%' || :query || '%' ORDER BY importance DESC, createdAtEpochMillis DESC LIMIT :limit"
    )
    suspend fun search(query: String, sessionId: String?, now: Long, limit: Int): List<MemoryEntity>

    @Query(
        "SELECT * FROM memories WHERE (:sessionId IS NULL OR sessionId = :sessionId OR sessionId IS NULL) " +
            "AND (expiresAtEpochMillis IS NULL OR expiresAtEpochMillis > :now) " +
            "ORDER BY importance DESC, updatedAtEpochMillis DESC LIMIT :limit"
    )
    suspend fun candidates(sessionId: String?, now: Long, limit: Int): List<MemoryEntity>

    @Query(
        "SELECT memories.* FROM memories JOIN memory_fts ON memory_fts.memoryId = memories.memoryId " +
            "WHERE memory_fts MATCH :matchQuery AND " +
            "(expiresAtEpochMillis IS NULL OR expiresAtEpochMillis > :now) " +
            "ORDER BY importance DESC, createdAtEpochMillis DESC LIMIT :limit"
    )
    suspend fun searchFts(matchQuery: String, now: Long, limit: Int): List<MemoryEntity>

    @Query("DELETE FROM memories WHERE memoryId = :memoryId")
    suspend fun delete(memoryId: String): Int

    @Query("DELETE FROM memory_fts WHERE memoryId = :memoryId")
    suspend fun deleteFts(memoryId: String)

    @Transaction
    suspend fun deleteMemory(memoryId: String): Boolean {
        deleteFts(memoryId)
        return delete(memoryId) > 0
    }

    @Query("DELETE FROM memories")
    suspend fun clearMemories()

    @Query("DELETE FROM memory_fts")
    suspend fun clearFts()

    @Transaction
    suspend fun clearAll() {
        clearFts()
        clearMemories()
    }

    @Query("DELETE FROM memories WHERE expiresAtEpochMillis IS NOT NULL AND expiresAtEpochMillis <= :now")
    suspend fun deleteExpired(now: Long)
}

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ArtifactEntity)

    @Query("SELECT * FROM artifacts WHERE artifactId = :artifactId")
    suspend fun artifact(artifactId: String): ArtifactEntity?

    @Query("DELETE FROM artifacts WHERE artifactId = :artifactId")
    suspend fun delete(artifactId: String): Int
}

@Database(
    entities = [
        RunEntity::class,
        SessionEntity::class,
        AgentNodeEntity::class,
        WorkerRunEntity::class,
        StepEntity::class,
        EventEntity::class,
        ToolCallEntity::class,
        ApprovalEntity::class,
        CheckpointEntity::class,
        ArtifactEntity::class,
        MemoryEntity::class,
        MemoryFtsEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao
    abstract fun executionGraphDao(): ExecutionGraphDao
    abstract fun recoveryDao(): RecoveryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun artifactDao(): ArtifactDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE runs ADD COLUMN retentionClass TEXT NOT NULL DEFAULT 'DEFAULT'"
                )
                db.execSQL(
                    "ALTER TABLE memories ADD COLUMN updatedAtEpochMillis INTEGER NOT NULL " +
                        "DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE memories SET updatedAtEpochMillis = createdAtEpochMillis " +
                        "WHERE updatedAtEpochMillis = 0"
                )
            }
        }
    }
}
