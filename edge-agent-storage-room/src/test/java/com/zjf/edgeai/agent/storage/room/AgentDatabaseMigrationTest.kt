package com.zjf.edgeai.agent.storage.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentDatabaseMigrationTest {
    private val databaseName = "agent-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    @Throws(IOException::class)
    fun `版本一升级后保留任务且补齐默认保留策略`() {
        helper.createDatabase(databaseName, 1).apply {
            execSQL(
                "INSERT INTO runs " +
                    "(runId, sessionId, requestJson, snapshotJson, state, eventSequence, " +
                    "createdAtEpochMillis, updatedAtEpochMillis) " +
                    "VALUES ('run-old', 'session-old', '{}', '{}', 'RUNNING', 2, 1, 2)"
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            AgentDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query("SELECT runId, retentionClass FROM runs WHERE runId = 'run-old'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("run-old", cursor.getString(0))
                assertEquals("DEFAULT", cursor.getString(1))
            }
            database.query("PRAGMA table_info(memories)").use { cursor ->
                var foundUpdatedAt = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "updatedAtEpochMillis") foundUpdatedAt = true
                }
                assertEquals(true, foundUpdatedAt)
            }
        }
    }
}
