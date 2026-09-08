package com.zjf.edgeai.agent.storage.room

import android.content.Context
import com.zjf.edgeai.agent.api.ArtifactId
import com.zjf.edgeai.agent.api.ArtifactRef
import com.zjf.edgeai.agent.api.ArtifactStore
import com.zjf.edgeai.agent.api.RunId
import com.zjf.edgeai.agent.api.StepId
import java.io.File
import java.security.MessageDigest

class RoomArtifactStore(
    context: Context,
    database: AgentDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : ArtifactStore {
    private val root = File(context.filesDir, "edge-agent/artifacts").apply { mkdirs() }
    private val dao = database.artifactDao()

    override suspend fun save(
        runId: RunId,
        stepId: StepId?,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        summary: String?,
    ): ArtifactRef {
        require(bytes.size <= MAX_ARTIFACT_BYTES) { "Artifact 超过单文件限制" }
        val id = ArtifactId.create()
        val runDirectory = File(root, runId.value).apply { mkdirs() }.canonicalFile
        val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(128).ifBlank { "artifact.bin" }
        val target = File(runDirectory, "${id.value}-$safeName").canonicalFile
        require(target.parentFile == runDirectory) { "Artifact 路径越界" }
        val temporary = File(runDirectory, ".${id.value}.tmp")
        temporary.outputStream().use { it.write(bytes) }
        check(temporary.renameTo(target)) { "Artifact 原子提交失败" }
        val timestamp = now()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val ref = ArtifactRef(
            id = id,
            runId = runId,
            stepId = stepId,
            name = safeName,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256,
            summary = summary?.take(2_000),
            createdAtEpochMillis = timestamp,
        )
        try {
            dao.insert(
                ArtifactEntity(
                    artifactId = id.value,
                    runId = runId.value,
                    mimeType = mimeType,
                    path = target.absolutePath,
                    sha256 = sha256,
                    metadataJson = "{}",
                    createdAtEpochMillis = timestamp,
                )
            )
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
        return ref
    }

    override suspend fun read(ref: ArtifactRef): ByteArray {
        val entity = dao.artifact(ref.id.value) ?: throw NoSuchElementException("Artifact 不存在")
        require(entity.runId == ref.runId.value && entity.sha256 == ref.sha256) { "Artifact 引用不匹配" }
        val file = File(entity.path).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "Artifact 路径越界" }
        val bytes = file.readBytes()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(actual == entity.sha256) { "Artifact 完整性校验失败" }
        return bytes
    }

    override suspend fun delete(id: ArtifactId): Boolean {
        val entity = dao.artifact(id.value) ?: return false
        val file = File(entity.path).canonicalFile
        if (file.path.startsWith(root.canonicalPath + File.separator)) file.delete()
        return dao.delete(id.value) > 0
    }

    private companion object { const val MAX_ARTIFACT_BYTES = 64 * 1024 * 1024 }
}
