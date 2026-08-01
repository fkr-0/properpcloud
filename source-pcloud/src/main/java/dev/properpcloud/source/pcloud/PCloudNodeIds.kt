package dev.properpcloud.source.pcloud

import dev.properpcloud.core.model.NodeId

internal enum class PCloudNodeKind(val token: String) {
    FOLDER("folder"),
    FILE("file"),
}

internal data class ParsedPCloudNodeId(
    val kind: PCloudNodeKind,
    val numericId: Long,
)

internal object PCloudNodeIds {
    fun folder(folderId: Long): NodeId = NodeId("pcloud:folder:$folderId")

    fun file(fileId: Long): NodeId = NodeId("pcloud:file:$fileId")

    fun parse(id: NodeId): ParsedPCloudNodeId {
        val parts = id.value.split(':')
        require(parts.size == 3 && parts[0] == "pcloud") { "not a pCloud node id: ${id.value}" }

        val kind = PCloudNodeKind.entries.firstOrNull { it.token == parts[1] }
            ?: error("unknown pCloud node kind: ${parts[1]}")
        val numericId = parts[2].toLongOrNull()
            ?: error("invalid pCloud numeric id: ${parts[2]}")
        return ParsedPCloudNodeId(kind, numericId)
    }
}
