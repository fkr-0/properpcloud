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
import dev.properpcloud.core.model.NaturalTextComparator
import dev.properpcloud.metadata.online.OnlineMetadataProvider
import dev.properpcloud.metadata.tags.AudioTagToolkit
import dev.properpcloud.metadata.tags.FolderPlaylistOrder
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

data class MetadataBundleItem(
    val originalFilename: String,
    val result: StagedTagResult,
    val modifiedAtEpochMillis: Long? = null,
) {
    init {
        require(originalFilename.isNotBlank()) { "metadata bundle filename must not be blank" }
    }
}

private data class BundleEntry(
    val filename: String,
    val item: MetadataBundleItem,
)

private fun uniqueArchiveName(original: String, usedNames: MutableSet<String>): String {
    val safe = original
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .ifBlank { "audio" }
    if (usedNames.add(safe.lowercase())) return safe
    val stem = safe.substringBeforeLast('.', safe)
    val extension = safe.substringAfterLast('.', "").takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
    var suffix = 2
    while (true) {
        val candidate = "$stem ($suffix)$extension"
        if (usedNames.add(candidate.lowercase())) return candidate
        suffix++
    }
}

private fun bundlePlaylistName(entries: List<BundleEntry>): String {
    val album = unanimousBundleValue(entries) { entry ->
        entry.item.result.snapshot.fields[TagField.ALBUM]?.value?.trim()?.takeIf(String::isNotBlank)
    }
    val artist = unanimousBundleValue(entries) { entry ->
        val snapshot = entry.item.result.snapshot
        snapshot.fields[TagField.ALBUM_ARTIST]?.value?.trim()?.takeIf(String::isNotBlank)
            ?: snapshot.fields[TagField.ARTIST]?.value?.trim()?.takeIf(String::isNotBlank)
    }
    val stem = when {
        artist != null && album != null -> "$artist - $album"
        album != null -> album
        else -> "properpcloud selection"
    }
        ?.replace(Regex("[\\u0000-\\u001f<>:\"/\\\\|?*\\u007f]"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim(' ', '.')
        ?.takeIf(String::isNotBlank)
        ?: "properpcloud selection"
    return "$stem.m3u8"
}

private fun unanimousBundleValue(entries: List<BundleEntry>, value: (BundleEntry) -> String?): String? {
    if (entries.isEmpty()) return null
    val values = entries.map { value(it)?.trim()?.takeIf(String::isNotBlank) }
    if (values.any { it == null }) return null
    return values.filterNotNull().distinct().singleOrNull()
}

private fun bundlePlaylist(entries: List<BundleEntry>, order: FolderPlaylistOrder): String {
    val sorted = entries.sortedWith(Comparator { left, right ->
        when (order) {
            FolderPlaylistOrder.NATURAL_FILENAME ->
                NaturalTextComparator.compare(left.filename, right.filename)
            FolderPlaylistOrder.TAGGED_TITLE -> {
                val title = NaturalTextComparator.compare(bundleTitle(left), bundleTitle(right))
                if (title != 0) title else NaturalTextComparator.compare(left.filename, right.filename)
            }
            FolderPlaylistOrder.TAG_TRACK_NUMBER -> {
                val disc = compareValues(bundleNumber(left, TagField.DISC_NUMBER), bundleNumber(right, TagField.DISC_NUMBER))
                if (disc != 0) return@Comparator disc
                val track = compareValues(bundleNumber(left, TagField.TRACK_NUMBER), bundleNumber(right, TagField.TRACK_NUMBER))
                if (track != 0) return@Comparator track
                NaturalTextComparator.compare(left.filename, right.filename)
            }
            FolderPlaylistOrder.MODIFICATION_TIME -> {
                val modified = compareValues(
                    left.item.modifiedAtEpochMillis ?: Long.MAX_VALUE,
                    right.item.modifiedAtEpochMillis ?: Long.MAX_VALUE,
                )
                if (modified != 0) modified else NaturalTextComparator.compare(left.filename, right.filename)
            }
        }
    })
    return buildString {
        append("#EXTM3U\n")
        sorted.forEach { entry ->
            val artist = entry.item.result.snapshot.fields[TagField.ARTIST]?.value?.trim()?.takeIf(String::isNotBlank)
            val title = bundleTitle(entry)
            val durationSeconds = entry.item.result.snapshot.durationMillis
                ?.takeIf { it > 0L }
                ?.let { ((it + 500L) / 1_000L).coerceAtLeast(1L) }
            append("#EXTINF:").append(durationSeconds ?: -1).append(',')
            append((if (artist == null) title else "$artist - $title").replace('\r', ' ').replace('\n', ' '))
            append('\n')
            append("./").append(entry.filename).append('\n')
        }
    }
}

private fun bundleTitle(entry: BundleEntry): String =
    entry.item.result.snapshot.fields[TagField.TITLE]?.value?.trim()?.takeIf(String::isNotBlank)
        ?: entry.filename.substringBeforeLast('.', entry.filename)

private fun bundleNumber(entry: BundleEntry, field: TagField): Int =
    entry.item.result.snapshot.fields[field]?.value
        ?.substringBefore('/')
        ?.trim()
        ?.toIntOrNull()
        ?: Int.MAX_VALUE

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

    suspend fun bundle(
        items: List<MetadataBundleItem>,
        includePlaylist: Boolean = true,
        playlistOrder: FolderPlaylistOrder = FolderPlaylistOrder.TAG_TRACK_NUMBER,
    ): MetadataExportArtifact {
        var completedBundle: File? = null
        try {
            return withContext(Dispatchers.IO) {
                require(items.isNotEmpty()) { "metadata bundle requires at least one staged file" }
                val bundle = File(
                    exportDirectory,
                    "properpcloud-tagged-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip",
                )
                try {
                    ZipOutputStream(bundle.outputStream().buffered()).use { zip ->
                        val usedNames = mutableSetOf<String>()
                        val bundleEntries = items.map { item ->
                            BundleEntry(
                                filename = uniqueArchiveName(item.originalFilename, usedNames),
                                item = item,
                            )
                        }
                        val manifest = buildString {
                            appendLine("index,filename,sha256,changed_fields")
                            bundleEntries.forEachIndexed { index, entry ->
                                val result = entry.item.result
                                append(index + 1).append(',')
                                    .append(entry.filename.csv()).append(',')
                                    .append(result.stagedSha256).append(',')
                                    .append(result.changedFields.joinToString("|") { it.name }.csv())
                                    .appendLine()
                                zip.putNextEntry(ZipEntry(entry.filename))
                                result.stagedFile.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                        zip.putNextEntry(ZipEntry("metadata-manifest.csv"))
                        zip.write(manifest.toByteArray())
                        zip.closeEntry()
                        if (includePlaylist) {
                            val playlistName = bundlePlaylistName(bundleEntries)
                            zip.putNextEntry(ZipEntry(playlistName))
                            zip.write(bundlePlaylist(bundleEntries, playlistOrder).toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                    }
                    val artifact = MetadataExportArtifact(
                        file = bundle,
                        displayName = bundle.name,
                        mimeType = "application/zip",
                        itemCount = items.size,
                        sha256 = bundle.sha256(),
                    )
                    completedBundle = bundle
                    items.forEach { it.result.stagedFile.delete() }
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
