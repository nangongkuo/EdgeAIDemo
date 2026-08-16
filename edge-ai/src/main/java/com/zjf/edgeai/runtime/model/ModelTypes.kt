package com.zjf.edgeai.runtime.model

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class ModelDescriptor(
    val displayName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val importedAtEpochMillis: Long
)

sealed interface ModelImportEvent {
    data class Started(val displayName: String, val totalBytes: Long?) : ModelImportEvent
    data class Progress(val copiedBytes: Long, val totalBytes: Long?) : ModelImportEvent
    data class Completed(val model: ModelDescriptor) : ModelImportEvent
}

interface ModelStore {
    val selectedModel: StateFlow<ModelDescriptor?>

    fun installBundledModel(): Flow<ModelImportEvent>

    fun importModel(uri: Uri): Flow<ModelImportEvent>

    suspend fun deleteSelectedModel()
}

open class ModelImportException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class InvalidModelException(message: String) : ModelImportException(message)

class InsufficientStorageException(message: String) : ModelImportException(message)

data class BundledModelSpec(
    val assetPath: String,
    val displayName: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String
) {
    companion object {
        val DEFAULT = BundledModelSpec(
            assetPath = "models/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
            displayName = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm",
            expectedSizeBytes = 584_417_280L,
            expectedSha256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be"
        )
    }
}
