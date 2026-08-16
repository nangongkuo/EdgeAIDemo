package com.zjf.edgeai.runtime.model

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

class ModelFileImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun importCopiesModelByShaAndReportsProgress() = runTest {
        val payload = "offline-edge-ai".encodeToByteArray()
        val modelsDir = temporaryFolder.newFolder("models")
        val importer = ModelFileImporter(
            modelsDir = modelsDir,
            clock = { 1234L },
            usableSpace = { Long.MAX_VALUE }
        )

        val events = importer.import("gemma.litertlm", payload.size.toLong()) {
            ByteArrayInputStream(payload)
        }.toList()

        val completed = events.last() as ModelImportEvent.Completed
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedSha, completed.model.sha256)
        assertEquals(1234L, completed.model.importedAtEpochMillis)
        assertEquals("$expectedSha.litertlm", File(completed.model.absolutePath).name)
        assertArrayEquals(payload, File(completed.model.absolutePath).readBytes())
        assertTrue(events.any { it is ModelImportEvent.Progress })
        assertTrue(modelsDir.listFiles().orEmpty().none { it.extension == "partial" })
    }

    @Test
    fun invalidExtensionIsRejectedWithoutCreatingFiles() = runTest {
        val modelsDir = temporaryFolder.newFolder("models")
        val importer = ModelFileImporter(modelsDir, usableSpace = { Long.MAX_VALUE })

        expectFailure<InvalidModelException> {
            importer.import("model.bin", 1L) { ByteArrayInputStream(byteArrayOf(1)) }.toList()
        }

        assertTrue(modelsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun emptySourceIsRejectedAndPartialIsRemoved() = runTest {
        val modelsDir = temporaryFolder.newFolder("models")
        val importer = ModelFileImporter(modelsDir, usableSpace = { Long.MAX_VALUE })

        expectFailure<InvalidModelException> {
            importer.import("model.litertlm", null) { ByteArrayInputStream(byteArrayOf()) }.toList()
        }

        assertTrue(modelsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun insufficientStorageFailsBeforeOpeningSource() = runTest {
        val modelsDir = temporaryFolder.newFolder("models")
        val importer = ModelFileImporter(modelsDir, usableSpace = { 0L })
        var sourceOpened = false

        expectFailure<InsufficientStorageException> {
            importer.import("model.litertlm", 1024L) {
                sourceOpened = true
                ByteArrayInputStream(byteArrayOf(1))
            }.toList()
        }

        assertFalse(sourceOpened)
        assertTrue(modelsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellingCollectionRemovesPartialFile() = runTest {
        val modelsDir = temporaryFolder.newFolder("models")
        val importer = ModelFileImporter(modelsDir, usableSpace = { Long.MAX_VALUE })
        val payload = ByteArray(2 * 1024 * 1024) { 7 }

        importer.import("model.litertlm", payload.size.toLong()) {
            ByteArrayInputStream(payload)
        }.take(2).toList()

        assertTrue(modelsDir.listFiles().orEmpty().none { it.extension == "partial" })
        assertTrue(modelsDir.listFiles().orEmpty().none { it.extension == "litertlm" })
    }

    @Test
    fun constructorCleansStalePartialFromInterruptedImport() {
        val modelsDir = temporaryFolder.newFolder("models")
        val stale = File(modelsDir, "stale.partial").apply { writeText("incomplete") }

        ModelFileImporter(modelsDir, usableSpace = { Long.MAX_VALUE })

        assertFalse(stale.exists())
    }

    private suspend inline fun <reified T : Throwable> expectFailure(
        crossinline block: suspend () -> Unit
    ) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }
}
