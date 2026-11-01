package dev.fkr.properpcloud.app.data

import dev.fkr.properpcloud.core.model.AudioFolder
import dev.fkr.properpcloud.core.model.AudioSource
import dev.fkr.properpcloud.core.model.MediaNode
import dev.fkr.properpcloud.core.model.NodeId
import dev.fkr.properpcloud.core.model.NodeInspection
import dev.fkr.properpcloud.core.model.SourceId
import dev.fkr.properpcloud.core.model.StreamHandle
import dev.fkr.properpcloud.core.model.StreamResolutionException
import dev.fkr.properpcloud.core.model.StreamResolutionFailureKind

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
