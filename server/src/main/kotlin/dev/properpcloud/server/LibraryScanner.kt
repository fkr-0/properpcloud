package dev.properpcloud.server

import dev.properpcloud.core.model.TagField
import dev.properpcloud.metadata.tags.JAudioTaggerToolkit
import java.io.File
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.name

data class ScanResult(
    val generation: Long,
    val scannedFiles: Long,
    val changedFiles: Long,
    val errors: Long,
    val providerOnline: Boolean,
)

class LibraryScanner(
    private val root: Path,
    private val repository: CatalogRepository,
    private val pCloud: PCloudRestClient? = null,
    private val tagToolkit: JAudioTaggerToolkit = JAudioTaggerToolkit(),
) {
    fun scan(): ScanResult {
        val mountOnline = Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(root)
        var providerOnline = false
        val pCloudClient = pCloud
        if (pCloudClient != null) {
            runCatching {
                val providerEntries = pCloudClient.listAll()
                repository.replaceProviderEntries(providerEntries)
                providerOnline = true
            }
        }
        val generation = repository.beginScan(providerOnline, mountOnline)
        if (!mountOnline) {
            repository.failScan(generation, providerOnline, false, "mounted pCloud library is unavailable; retained previous catalog")
            error("mounted pCloud library is unavailable")
        }

        var scanned = 0L
        var changed = 0L
        var errors = 0L
        try {
            Files.walk(root, Int.MAX_VALUE, *emptyArray<FileVisitOption>()).use { paths ->
                paths.forEach { path ->
                    if (Files.isSymbolicLink(path)) return@forEach
                    val relative = normalizeRelative(root.relativize(path))
                    if (relative.isEmpty()) {
                        repository.upsert(
                            CatalogEntry(
                                nodeId = ROOT_NODE_ID,
                                parentNodeId = null,
                                path = "",
                                name = "Server library",
                                kind = "folder",
                                providerFolderId = repository.providerEntry("")?.folderId ?: 0L,
                                scanGeneration = generation,
                            ),
                        )
                        return@forEach
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        val provider = repository.providerEntry(relative)
                        repository.upsert(
                            CatalogEntry(
                                nodeId = nodeId(relative, provider, folder = true),
                                parentNodeId = parentNodeId(relative),
                                path = relative,
                                name = path.name,
                                kind = "folder",
                                modifiedAtEpochMillis = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(),
                                providerFolderId = provider?.folderId,
                                scanGeneration = generation,
                            ),
                        )
                        return@forEach
                    }
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    scanned += 1
                    val size = Files.size(path)
                    val modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                    val previous = repository.existingByPath(relative)
                    val provider = repository.providerEntry(relative)
                    val desiredNodeId = nodeId(relative, provider, folder = false)
                    if (
                        previous != null &&
                        previous.sizeBytes == size &&
                        previous.modifiedAtEpochMillis == modified &&
                        previous.nodeId == desiredNodeId
                    ) {
                        repository.touch(relative, generation)
                    } else {
                        changed += 1
                        try {
                            repository.upsert(scanFile(path, relative, size, modified, generation))
                        } catch (error: Throwable) {
                            errors += 1
                            val basic = basicFileEntry(path, relative, size, modified, generation)
                            repository.upsert(if (isAudio(path)) {
                                basic.copy(
                                    kind = "audio",
                                    contentHash = runCatching { sha256(path) }.getOrNull(),
                                    metadataError = error::class.simpleName ?: "metadata_error",
                                )
                            } else {
                                basic.copy(metadataError = error::class.simpleName ?: "metadata_error")
                            })
                        }
                    }
                    if (scanned % 100L == 0L) repository.updateScanProgress(generation, scanned, changed, errors)
                }
            }
            repository.removeNotInGeneration(generation)
            repository.completeScan(
                generation,
                scanned,
                changed,
                errors,
                providerOnline,
                if (providerOnline) null else "pCloud metadata refresh unavailable; catalog generated from mounted storage and cached provider identity",
            )
            return ScanResult(generation, scanned, changed, errors, providerOnline)
        } catch (error: Throwable) {
            repository.updateScanProgress(generation, scanned, changed, errors)
            repository.failScan(generation, providerOnline, true, "scan failed; previous catalog entries retained")
            throw error
        }
    }

    private fun scanFile(path: Path, relative: String, size: Long, modified: Long, generation: Long): CatalogEntry {
        val basic = basicFileEntry(path, relative, size, modified, generation)
        if (!isAudio(path)) return basic
        val file = path.toFile()
        val snapshot = tagToolkit.inspect(file)
        val technical = readTechnicalAudioHeader(file)
        val segments = relative.split('/').dropLast(1)
        val folderAlbum = segments.lastOrNull()
        val folderArtist = segments.dropLast(1).lastOrNull()
        val folderGenre = segments.dropLast(2).lastOrNull()
        fun field(tag: TagField): String? = snapshot.fields[tag]?.value
        return basic.copy(
            kind = "audio",
            contentHash = sha256(path),
            title = field(TagField.TITLE),
            artist = field(TagField.ARTIST) ?: folderArtist,
            album = field(TagField.ALBUM) ?: folderAlbum,
            albumArtist = field(TagField.ALBUM_ARTIST),
            genre = field(TagField.GENRE) ?: folderGenre,
            year = field(TagField.YEAR),
            trackNumber = parseLeadingInt(field(TagField.TRACK_NUMBER)),
            discNumber = parseLeadingInt(field(TagField.DISC_NUMBER)),
            durationMillis = snapshot.durationMillis,
            sampleRate = technical.sampleRate,
            channels = technical.channels,
            bitDepth = technical.bitDepth,
            format = snapshot.format,
        )
    }

    private fun basicFileEntry(path: Path, relative: String, size: Long, modified: Long, generation: Long): CatalogEntry {
        val provider = repository.providerEntry(relative)
        return CatalogEntry(
            nodeId = nodeId(relative, provider, folder = false),
            parentNodeId = parentNodeId(relative),
            path = relative,
            name = path.name,
            kind = "file",
            sizeBytes = size,
            modifiedAtEpochMillis = modified,
            providerFileId = provider?.fileId,
            contentType = runCatching { Files.probeContentType(path) }.getOrNull(),
            scanGeneration = generation,
        )
    }

    private fun parentNodeId(relative: String): String {
        val parentPath = relative.substringBeforeLast('/', "")
        if (parentPath.isEmpty()) return ROOT_NODE_ID
        return nodeId(parentPath, repository.providerEntry(parentPath), folder = true)
    }

    private fun nodeId(relative: String, provider: PCloudProviderEntry?, folder: Boolean): String = when {
        relative.isEmpty() -> ROOT_NODE_ID
        folder && provider?.folderId != null -> "catalog:pcloud:folder:${provider.folderId}"
        !folder && provider?.fileId != null -> "catalog:pcloud:file:${provider.fileId}"
        else -> "catalog:local:${if (folder) "folder" else "file"}:${sha256Text(relative).take(32)}"
    }

    private fun isAudio(path: Path): Boolean = path.extension.lowercase() in AUDIO_EXTENSIONS

    private fun parseLeadingInt(value: String?): Int? = value
        ?.trim()
        ?.substringBefore('/')
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    private fun normalizeRelative(relative: Path): String = relative.joinToString("/") { it.toString() }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .toHex()

    private data class TechnicalAudioHeader(val sampleRate: Int?, val channels: Int?, val bitDepth: Int?)

    private fun readTechnicalAudioHeader(file: File): TechnicalAudioHeader {
        return runCatching {
            val audioFileClass = Class.forName("org.jaudiotagger.audio.AudioFileIO")
            val audioFile = audioFileClass.getMethod("read", File::class.java).invoke(null, file)
            val header = audioFile.javaClass.getMethod("getAudioHeader").invoke(audioFile)
            fun invokeNumber(vararg methodNames: String): Int? = methodNames.firstNotNullOfOrNull { methodName ->
                runCatching {
                    val value = header.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }?.invoke(header)
                    when (value) {
                        is Number -> value.toInt()
                        is String -> value.filter(Char::isDigit).toIntOrNull()
                        else -> null
                    }
                }.getOrNull()
            }?.takeIf { it > 0 }
            TechnicalAudioHeader(
                sampleRate = invokeNumber("getSampleRateAsNumber", "getSampleRate"),
                channels = invokeNumber("getChannelNumber", "getChannels"),
                bitDepth = invokeNumber("getBitsPerSample", "getBitDepth"),
            )
        }.getOrElse {
            runCatching {
                val format = javax.sound.sampled.AudioSystem.getAudioFileFormat(file).format
                TechnicalAudioHeader(
                    sampleRate = format.sampleRate.toInt().takeIf { it > 0 },
                    channels = format.channels.takeIf { it > 0 },
                    bitDepth = format.sampleSizeInBits.takeIf { it > 0 },
                )
            }.getOrDefault(TechnicalAudioHeader(null, null, null))
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val ROOT_NODE_ID = "catalog:root"
        val AUDIO_EXTENSIONS = setOf("aac", "aif", "aiff", "alac", "flac", "m4a", "m4b", "mp3", "oga", "ogg", "opus", "wav", "wma")
    }
}
