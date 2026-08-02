package dev.properpcloud.core.model

@JvmInline
value class SourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "source id must not be blank" }
    }
}

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

interface AudioSource {
    val id: SourceId
    val root: AudioFolder

    suspend fun list(folderId: NodeId): List<MediaNode>

    suspend fun load(nodeId: NodeId): MediaNode

    suspend fun resolveStream(trackId: NodeId): StreamHandle

    suspend fun inspect(nodeId: NodeId): NodeInspection
}
