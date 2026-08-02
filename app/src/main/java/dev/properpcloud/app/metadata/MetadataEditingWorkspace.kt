package dev.properpcloud.app.metadata

import android.content.Context
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MetadataCandidate
import dev.properpcloud.core.model.MetadataContentSource
import dev.properpcloud.core.model.PreparedMetadataSource
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.core.model.TagSnapshot
import dev.properpcloud.metadata.online.OnlineMetadataProvider
import dev.properpcloud.metadata.tags.AudioTagToolkit
import dev.properpcloud.metadata.tags.StagedTagResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class LoadedMetadataItem(
    val track: AudioTrack,
    val prepared: PreparedMetadataSource,
    val snapshot: TagSnapshot,
)

data class MetadataExportArtifact(
    val file: File,
    val displayName: String,
    val mimeType: String,
    val itemCount: Int,
    val sha256: String,
)

class MetadataEditingWorkspace(
    context: Context,
    private val tagToolkit: AudioTagToolkit,
    private val onlineProvider: OnlineMetadataProvider,
) {
    private val sourceDirectory = File(context.cacheDir, "metadata-sources").apply {
        mkdirs()
        deleteExpiredFiles(maxAgeMillis = SOURCE_RETENTION_MILLIS, maxFiles = 40)
    }
    private val exportDirectory = File(context.filesDir, "metadata-exports").apply {
        mkdirs()
        deleteExpiredFiles(maxAgeMillis = EXPORT_RETENTION_MILLIS, maxFiles = 100)
    }

    suspend fun load(source: AudioSource, track: AudioTrack): LoadedMetadataItem {
        val contentSource = source as? MetadataContentSource
            ?: error("${source.root.name} does not support metadata source preparation")
        val destination = File(sourceDirectory, uniqueFilename(track.name))
        try {
            return withContext(Dispatchers.IO) {
                val prepared = contentSource.prepareMetadataSource(track.id, destination)
                LoadedMetadataItem(track, prepared, tagToolkit.inspect(prepared.localFile))
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    suspend fun search(item: LoadedMetadataItem, draft: Map<TagField, String>): List<MetadataCandidate> {
        val query = MetadataDraftPlanner.searchQuery(item.track, item.snapshot, draft)
        return onlineProvider.search(query, limit = 8)
    }

    suspend fun stage(item: LoadedMetadataItem, patch: TagPatch): StagedTagResult {
        var candidate: File? = null
        try {
            return withContext(Dispatchers.IO) {
                tagToolkit.stagePatch(
                    source = item.prepared.localFile,
                    stagingDirectory = exportDirectory,
                    patch = patch,
                    expectedSourceSha256 = item.prepared.expectedContentHash,
                ).also { candidate = it.stagedFile }
            }
        } catch (error: Throwable) {
            candidate?.delete()
            throw error
        }
    }

    suspend fun artifact(result: StagedTagResult): MetadataExportArtifact = withContext(Dispatchers.IO) {
        MetadataExportArtifact(
            file = result.stagedFile,
            displayName = result.stagedFile.name,
            mimeType = result.stagedFile.audioMimeType(),
            itemCount = 1,
            sha256 = result.stagedSha256,
        )
    }

    suspend fun bundle(results: List<StagedTagResult>): MetadataExportArtifact {
        var completedBundle: File? = null
        try {
            return withContext(Dispatchers.IO) {
                require(results.isNotEmpty()) { "metadata bundle requires at least one staged file" }
                val bundle = File(
                    exportDirectory,
                    "properpcloud-tagged-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip",
                )
                try {
                    ZipOutputStream(bundle.outputStream().buffered()).use { zip ->
                        val manifest = buildString {
                            appendLine("index,filename,sha256,changed_fields")
                            results.forEachIndexed { index, result ->
                                val name = "${(index + 1).toString().padStart(2, '0')}-${result.stagedFile.name.sanitize()}"
                                append(index + 1).append(',')
                                    .append(name.csv()).append(',')
                                    .append(result.stagedSha256).append(',')
                                    .append(result.changedFields.joinToString("|") { it.name }.csv())
                                    .appendLine()
                                zip.putNextEntry(ZipEntry(name))
                                result.stagedFile.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                        zip.putNextEntry(ZipEntry("metadata-manifest.csv"))
                        zip.write(manifest.toByteArray())
                        zip.closeEntry()
                    }
                    val artifact = MetadataExportArtifact(
                        file = bundle,
                        displayName = bundle.name,
                        mimeType = "application/zip",
                        itemCount = results.size,
                        sha256 = bundle.sha256(),
                    )
                    completedBundle = bundle
                    results.forEach { it.stagedFile.delete() }
                    artifact
                } catch (error: Throwable) {
                    bundle.delete()
                    throw error
                }
            }
        } catch (error: Throwable) {
            completedBundle?.delete()
            throw error
        }
    }

    fun discard(items: Collection<LoadedMetadataItem>) {
        items.forEach { it.prepared.localFile.delete() }
    }

    private fun uniqueFilename(original: String): String {
        val stem = original.substringBeforeLast('.', original).sanitize().take(80).ifBlank { "audio" }
        val extension = original.substringAfterLast('.', "bin").lowercase().sanitize().take(12).ifBlank { "bin" }
        return "$stem-${UUID.randomUUID()}.$extension"
    }

    private companion object {
        const val SOURCE_RETENTION_MILLIS = 24L * 60 * 60 * 1_000
        const val EXPORT_RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1_000
    }
}

private fun File.audioMimeType(): String = when (extension.lowercase()) {
    "mp3" -> "audio/mpeg"
    "m4a", "m4b" -> "audio/mp4"
    "flac" -> "audio/flac"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "wav" -> "audio/wav"
    "aiff", "aif" -> "audio/aiff"
    else -> "application/octet-stream"
}

private fun String.sanitize(): String = replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "file" }

private fun File.deleteExpiredFiles(maxAgeMillis: Long, maxFiles: Int) {
    val now = System.currentTimeMillis()
    val files = listFiles()?.filter(File::isFile).orEmpty().sortedByDescending(File::lastModified)
    files.forEachIndexed { index, file ->
        if (index >= maxFiles || now - file.lastModified() > maxAgeMillis) file.delete()
    }
}
private fun String.csv(): String = "\"${replace("\"", "\"\"")}\""

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
