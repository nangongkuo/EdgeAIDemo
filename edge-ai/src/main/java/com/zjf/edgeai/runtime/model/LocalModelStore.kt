package com.zjf.edgeai.runtime.model

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import java.io.File

class LocalModelStore(
    context: Context,
    private val bundledModel: BundledModelSpec = BundledModelSpec.DEFAULT
) : ModelStore {
    companion object {
        private const val PREFS_NAME = "edge_ai_models"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_PATH = "path"
        private const val KEY_SIZE = "size"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_IMPORTED_AT = "imported_at"
    }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelsDir = File(appContext.filesDir, "models")
    private val importer = ModelFileImporter(modelsDir)
    private val _selectedModel = MutableStateFlow(restoreModel())

    override val selectedModel: StateFlow<ModelDescriptor?> = _selectedModel

    override fun installBundledModel(): Flow<ModelImportEvent> = flow {
        val existing = _selectedModel.value
        if (existing != null) {
            emit(ModelImportEvent.Completed(existing))
            return@flow
        }

        val totalBytes = bundledAssetLength()
        importer.import(bundledModel.displayName, totalBytes) {
            try {
                appContext.assets.open(bundledModel.assetPath, AssetManager.ACCESS_STREAMING)
            } catch (failure: Throwable) {
                throw ModelImportException(
                    "当前 APK 未包含内置模型 ${bundledModel.displayName}",
                    failure
                )
            }
        }.collect { event ->
            if (event is ModelImportEvent.Completed) {
                validateBundledModel(event.model)
                selectImportedModel(event.model, null)
            }
            emit(event)
        }
    }.flowOn(Dispatchers.IO)

    override fun importModel(uri: Uri): Flow<ModelImportEvent> = flow {
        val metadata = queryMetadata(uri)
        val previous = _selectedModel.value
        importer.import(metadata.displayName, metadata.sizeBytes) {
            appContext.contentResolver.openInputStream(uri)
                ?: throw ModelImportException("无法读取所选模型")
        }.collect { event ->
            if (event is ModelImportEvent.Completed) {
                selectImportedModel(event.model, previous)
            }
            emit(event)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun deleteSelectedModel() {
        val model = _selectedModel.value
        if (model != null) {
            File(model.absolutePath).delete()
            File(appContext.filesDir, "litertlm-cache/${model.sha256}").deleteRecursively()
        }
        preferences.edit().clear().apply()
        _selectedModel.value = null
        importer.cleanupPartials()
    }

    private fun queryMetadata(uri: Uri): SourceMetadata {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "model.litertlm"
        var size: Long? = null
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { displayName = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { size = cursor.getLong(it).takeIf { value -> value >= 0L } }
            }
        }
        return SourceMetadata(displayName, size)
    }

    private fun persist(model: ModelDescriptor) {
        preferences.edit()
            .putString(KEY_DISPLAY_NAME, model.displayName)
            .putString(KEY_PATH, model.absolutePath)
            .putLong(KEY_SIZE, model.sizeBytes)
            .putString(KEY_SHA256, model.sha256)
            .putLong(KEY_IMPORTED_AT, model.importedAtEpochMillis)
            .apply()
    }

    private fun selectImportedModel(model: ModelDescriptor, previous: ModelDescriptor?) {
        persist(model)
        _selectedModel.update { model }
        if (previous != null && previous.absolutePath != model.absolutePath) {
            File(previous.absolutePath).delete()
            File(appContext.filesDir, "litertlm-cache/${previous.sha256}").deleteRecursively()
        }
    }

    private fun bundledAssetLength(): Long? = try {
        appContext.assets.openFd(bundledModel.assetPath).use { descriptor ->
            descriptor.length.takeIf { it > 0L }
        }
    } catch (_: Throwable) {
        null
    }

    private fun validateBundledModel(model: ModelDescriptor) {
        if (model.sizeBytes == bundledModel.expectedSizeBytes &&
            model.sha256.equals(bundledModel.expectedSha256, ignoreCase = true)
        ) {
            return
        }
        File(model.absolutePath).delete()
        throw InvalidModelException("内置模型校验失败，请重新提供许可批准后的 Gemma 文件")
    }

    private fun restoreModel(): ModelDescriptor? {
        val path = preferences.getString(KEY_PATH, null) ?: return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L || file.extension != "litertlm") {
            preferences.edit().clear().apply()
            return null
        }
        return ModelDescriptor(
            displayName = preferences.getString(KEY_DISPLAY_NAME, file.name) ?: file.name,
            absolutePath = file.absolutePath,
            sizeBytes = preferences.getLong(KEY_SIZE, file.length()),
            sha256 = preferences.getString(KEY_SHA256, file.nameWithoutExtension)
                ?: file.nameWithoutExtension,
            importedAtEpochMillis = preferences.getLong(KEY_IMPORTED_AT, file.lastModified())
        )
    }

    private data class SourceMetadata(val displayName: String, val sizeBytes: Long?)
}
