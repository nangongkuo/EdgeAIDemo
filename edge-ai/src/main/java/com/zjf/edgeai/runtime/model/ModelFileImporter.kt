package com.zjf.edgeai.runtime.model

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

class ModelFileImporter(
    private val modelsDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val usableSpace: () -> Long = { modelsDir.usableSpace }
) {
    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val MINIMUM_SAFETY_MARGIN_BYTES = 256L * 1024L * 1024L
    }

    init {
        modelsDir.mkdirs()
        cleanupPartials()
    }

    fun import(
        displayName: String,
        totalBytes: Long?,
        source: () -> InputStream
    ): Flow<ModelImportEvent> = flow {
        validateName(displayName)
        if (totalBytes == 0L) throw InvalidModelException("模型文件为空")
        ensureStorage(totalBytes)

        modelsDir.mkdirs()
        val partial = File(modelsDir, "${UUID.randomUUID()}.partial")
        var copiedBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")

        emit(ModelImportEvent.Started(displayName, totalBytes?.takeIf { it > 0L }))

        try {
            source().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copiedBytes += count
                        emit(ModelImportEvent.Progress(copiedBytes, totalBytes?.takeIf { it > 0L }))
                    }
                    output.flush()
                    output.fd.sync()
                }
            }

            if (copiedBytes <= 0L) throw InvalidModelException("模型文件为空")
            if (totalBytes != null && totalBytes > 0L && copiedBytes != totalBytes) {
                throw ModelImportException("模型复制不完整：预期 $totalBytes 字节，实际 $copiedBytes 字节")
            }

            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(modelsDir, "$sha256.litertlm")
            if (destination.exists()) {
                partial.delete()
            } else {
                moveAtomically(partial, destination)
            }

            emit(
                ModelImportEvent.Completed(
                    ModelDescriptor(
                        displayName = displayName,
                        absolutePath = destination.absolutePath,
                        sizeBytes = copiedBytes,
                        sha256 = sha256,
                        importedAtEpochMillis = clock()
                    )
                )
            )
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    fun cleanupPartials() {
        modelsDir.listFiles { file -> file.extension == "partial" }
            ?.forEach(File::delete)
    }

    private fun validateName(displayName: String) {
        if (!displayName.lowercase().endsWith(".litertlm")) {
            throw InvalidModelException("请选择 .litertlm 模型文件")
        }
    }

    private fun ensureStorage(totalBytes: Long?) {
        if (totalBytes == null || totalBytes <= 0L) return
        val proportionalMargin = totalBytes / 10L
        val required = totalBytes + max(MINIMUM_SAFETY_MARGIN_BYTES, proportionalMargin)
        if (usableSpace() < required) {
            throw InsufficientStorageException(
                "可用空间不足，需要至少 ${required.asReadableSize()}，当前 ${usableSpace().asReadableSize()}"
            )
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

fun Long.asReadableSize(): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${toLong()} ${units[unit]}" else "%.2f %s".format(value, units[unit])
}
