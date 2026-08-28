package dev.properpcloud.core.model

import java.io.File

@JvmInline
value class SourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "source id must not be blank" }
    }
}

enum class LibraryFileKind {
    GENERIC,
    PLAYLIST,
    ;

    companion object {
        private val playlistExtensions = setOf("m3u", "m3u8", "pls", "xspf")

        fun fromFilename(name: String): LibraryFileKind =
            if (name.substringAfterLast('.', "").lowercase() in playlistExtensions) PLAYLIST else GENERIC
    }
}

/** A non-audio file that remains visible in the filesystem-first library model. */
data class LibraryFile(
    override val sourceId: SourceId,
    override val id: NodeId,
    override val parentId: NodeId,
    override val name: String,
    override val modifiedAtEpochMillis: Long? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val kind: LibraryFileKind = LibraryFileKind.fromFilename(name),
) : MediaNode

@JvmInline
value class NodeId(val value: String) {
    init {
        require(value.isNotBlank()) { "node id must not be blank" }
    }
}

sealed interface MediaNode {
    val sourceId: SourceId
    val id: NodeId
    val parentId: NodeId?
    val name: String
    val modifiedAtEpochMillis: Long?
}

data class AudioFolder(
    override val sourceId: SourceId,
    override val id: NodeId,
    override val parentId: NodeId?,
    override val name: String,
    override val modifiedAtEpochMillis: Long? = null,
) : MediaNode

data class AudioTrack(
    override val sourceId: SourceId,
    override val id: NodeId,
    override val parentId: NodeId,
    override val name: String,
    override val modifiedAtEpochMillis: Long? = null,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val taggedTitle: String? = null,
    val durationMillis: Long? = null,
) : MediaNode {
    val filenameStem: String
        get() = name.substringBeforeLast('.', name)
}

data class StreamHandle(
    val url: String,
    val expiresAtEpochMillis: Long? = null,
    val contentType: String? = null,
)

data class NodeInspection(
    val fields: Map<String, String>,
)

data class PreparedMetadataSource(
    val sourceId: SourceId,
    val nodeId: NodeId,
    val localFile: File,
    val originalFilename: String,
    val expectedRevision: String? = null,
    val expectedContentHash: String,
    val sizeBytes: Long,
) {
    init {
        require(originalFilename.isNotBlank()) { "metadata source filename must not be blank" }
        require(expectedContentHash.isNotBlank()) { "metadata source content hash must not be blank" }
        require(sizeBytes > 0) { "metadata source must not be empty" }
    }
}

interface MetadataContentSource {
    suspend fun prepareMetadataSource(nodeId: NodeId, destinationFile: File): PreparedMetadataSource
}

interface AudioSource {
    val id: SourceId
    val root: AudioFolder

    suspend fun list(folderId: NodeId): List<MediaNode>

    suspend fun load(nodeId: NodeId): MediaNode

    suspend fun resolveStream(trackId: NodeId): StreamHandle

    suspend fun inspect(nodeId: NodeId): NodeInspection
}
