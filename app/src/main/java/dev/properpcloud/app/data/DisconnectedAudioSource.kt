package dev.properpcloud.app.data

import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.NodeInspection
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.StreamHandle
import dev.properpcloud.core.model.StreamResolutionException
import dev.properpcloud.core.model.StreamResolutionFailureKind

/** Neutral source used only while no provider-backed library is selected. */
object DisconnectedAudioSource : AudioSource {
    override val id = SourceId(SourceKind.NONE.id)
    override val root = AudioFolder(id, NodeId("none:folder:root"), null, "No source connected")

    override suspend fun list(folderId: NodeId): List<MediaNode> {
        require(folderId == root.id) { "no source is connected" }
        return emptyList()
    }

    override suspend fun load(nodeId: NodeId): MediaNode {
        require(nodeId == root.id) { "no source is connected" }
        return root
    }

    override suspend fun resolveStream(trackId: NodeId): StreamHandle =
        throw StreamResolutionException(
            StreamResolutionFailureKind.TRANSIENT,
            "no source is connected",
        )

    override suspend fun inspect(nodeId: NodeId): NodeInspection {
        require(nodeId == root.id) { "no source is connected" }
        return NodeInspection(mapOf("status" to "disconnected"))
    }
}
