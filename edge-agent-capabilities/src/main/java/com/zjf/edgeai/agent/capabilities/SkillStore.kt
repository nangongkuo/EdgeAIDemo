package com.zjf.edgeai.agent.capabilities

import android.content.Context
import android.content.res.AssetManager
import com.zjf.edgeai.agent.api.CapabilityId
import com.zjf.edgeai.agent.api.SkillBackend
import com.zjf.edgeai.agent.api.SkillManifest
import com.zjf.edgeai.agent.api.SkillSignatureVerifier
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SkillSummary(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val backend: SkillBackend,
    val capabilities: Set<CapabilityId>,
    val enabled: Boolean,
)

data class LoadedSkill(
    val manifest: SkillManifest,
    val instructions: String,
    val resourceFiles: Map<String, File>,
)

class SkillStore private constructor(
    private val root: File,
    private val privateRoots: List<File>,
    private val rootContextAssets: AssetManager?,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val signatureVerifier: SkillSignatureVerifier? = null,
) {
    constructor(
        context: Context,
        json: Json = Json { ignoreUnknownKeys = true },
        signatureVerifier: SkillSignatureVerifier? = null,
    ) : this(
        root = File(context.filesDir, "edge-agent/skills"),
        privateRoots = listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir),
        rootContextAssets = context.assets,
        json = json,
        signatureVerifier = signatureVerifier,
    )

    internal constructor(
        root: File,
        privateSourceRoot: File,
        signatureVerifier: SkillSignatureVerifier? = null,
    ) : this(
        root = root,
        privateRoots = listOf(privateSourceRoot),
        rootContextAssets = null,
        signatureVerifier = signatureVerifier,
    )

    init { root.mkdirs() }

    fun list(): List<SkillSummary> = root.listFiles().orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory -> runCatching { readManifest(directory) }.getOrNull() }
        .map(::summary)
        .sortedBy { it.id }
        .toList()

    fun load(id: String): LoadedSkill {
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))) { "非法 Skill ID" }
        val directory = File(root, id).canonicalFile
        require(directory.parentFile == root.canonicalFile && directory.isDirectory) { "Skill 不存在" }
        val manifest = readManifest(directory)
        require(manifest.id == id) { "Skill 目录与 Manifest ID 不一致" }
        require(manifest.enabled) { "Skill 已禁用" }
        verifyIntegrity(directory, manifest)
        val entry = safeChild(directory, manifest.entryPoint)
        val resources = manifest.resources.associateWith { resource -> safeChild(directory, resource) }
        return LoadedSkill(manifest, entry.readText(), resources)
    }

    fun installFromPrivateDirectory(source: File): SkillSummary {
        val canonicalSource = source.canonicalFile
        require(canonicalSource.isDirectory) { "Skill 来源必须是目录" }
        require(privateRoots.any { canonicalSource.path.startsWith(it.canonicalPath + File.separator) }) {
            "Skill 只能从应用私有目录安装"
        }
        val manifest = readManifest(canonicalSource)
        verifyIntegrity(canonicalSource, manifest)
        val destination = File(root, manifest.id).canonicalFile
        if (destination.exists()) return existingOrConflict(destination, manifest)
        val staging = File(root, ".install-${manifest.id}-${System.nanoTime()}").canonicalFile
        try {
            staging.mkdirs()
            canonicalSource.walkTopDown().forEach { file ->
                require(!Files.isSymbolicLink(file.toPath())) { "Skill 不允许符号链接" }
                val relative = file.relativeTo(canonicalSource)
                val target = File(staging, relative.path)
                if (file.isDirectory) target.mkdirs() else file.copyTo(target, overwrite = false)
            }
            verifyIntegrity(staging, manifest)
            check(staging.renameTo(destination)) { "Skill 原子安装失败" }
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        return summary(manifest)
    }

    fun installFromAssets(assetDirectory: String): SkillSummary {
        require(assetDirectory.isNotBlank() && !assetDirectory.startsWith("/")) { "非法 Asset 路径" }
        val stagingSource = File(root, ".asset-${System.nanoTime()}").apply { mkdirs() }
        try {
            copyAssetTree(assetDirectory.trimEnd('/'), stagingSource)
            val manifest = readManifest(stagingSource)
            verifyIntegrity(stagingSource, manifest)
            val destination = File(root, manifest.id).canonicalFile
            if (destination.exists()) return existingOrConflict(destination, manifest)
            check(stagingSource.renameTo(destination)) { "内置 Skill 原子安装失败" }
            return summary(manifest)
        } finally {
            if (stagingSource.exists()) stagingSource.deleteRecursively()
        }
    }

    private fun readManifest(directory: File): SkillManifest {
        val file = safeChild(directory, "manifest.json")
        return json.decodeFromString(SkillManifest.serializer(), file.readText())
    }

    private fun existingOrConflict(destination: File, incoming: SkillManifest): SkillSummary {
        val existing = readManifest(destination)
        require(existing.version == incoming.version && existing.sha256.equals(incoming.sha256, true)) {
            "Skill ${incoming.id} 已安装其他版本 ${existing.version}，请显式迁移后再替换"
        }
        verifyIntegrity(destination, existing)
        return summary(existing)
    }

    private fun summary(manifest: SkillManifest) = SkillSummary(
        manifest.id,
        manifest.version,
        manifest.name,
        manifest.description,
        manifest.backend,
        manifest.capabilities,
        manifest.enabled,
    )

    private fun verifyIntegrity(directory: File, manifest: SkillManifest) {
        val files = buildList {
            add(manifest.entryPoint)
            addAll(manifest.resources)
        }.distinct().sorted()
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { relative ->
            val file = safeChild(directory, relative)
            require(file.isFile) { "Skill 资源不存在: $relative" }
            digest.update(relative.encodeToByteArray())
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(manifest.sha256, ignoreCase = true)) { "Skill SHA-256 校验失败" }
        manifest.signature?.let { signature ->
            val verifier = requireNotNull(signatureVerifier) { "Skill 带有签名但未配置签名验证器" }
            require(verifier.verify(manifest, actual, signature)) { "Skill 签名校验失败" }
        }
    }

    private fun safeChild(directory: File, relative: String): File {
        require(relative.isNotBlank() && !File(relative).isAbsolute) { "Skill 路径必须是相对路径" }
        val file = File(directory, relative).canonicalFile
        require(file.path.startsWith(directory.canonicalPath + File.separator)) { "Skill 路径越界" }
        return file
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val assets = requireNotNull(rootContextAssets) { "该 SkillStore 未配置 AssetManager" }
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(destination, child))
        }
    }
}

@Serializable
data class DeclarativeSkillWorkflow(
    val steps: List<DeclarativeSkillStep>,
)

@Serializable
data class DeclarativeSkillStep(
    val capabilityId: CapabilityId,
    val argumentsJson: String,
)
