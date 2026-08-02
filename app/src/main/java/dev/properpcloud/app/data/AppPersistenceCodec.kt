package dev.properpcloud.app.data

import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.SourceId
import org.json.JSONArray
import org.json.JSONObject

internal data class StoredQueuePayload(
    val json: String,
    val currentIndex: Int,
)

internal object AppPersistenceCodec {
    fun encodeQueue(queue: PlaybackQueue): StoredQueuePayload {
        val array = JSONArray()
        queue.entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("source", entry.track.sourceId.value)
                    .put("node", entry.track.id.value)
                    .put("origin", entry.originFolderId.value),
            )
        }
        return StoredQueuePayload(array.toString(), queue.currentIndex)
    }

    fun decodeQueue(json: String, currentIndex: Int): StoredQueue {
        val array = JSONArray(json.ifBlank { "[]" })
        val entries = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    StoredQueueReference(
                        SourceId(item.getString("source")),
                        NodeId(item.getString("node")),
                        NodeId(item.getString("origin")),
                    ),
                )
            }
        }
        return StoredQueue(entries, currentIndex)
    }

    fun upsertProgress(json: String, progress: PlaybackProgress): String {
        val root = JSONObject(json.ifBlank { "{}" })
        root.put(
            progressKey(progress.sourceId, progress.nodeId),
            JSONObject()
                .put("position", progress.positionMillis)
                .put("duration", progress.durationMillis ?: JSONObject.NULL)
                .put("speed", progress.playbackSpeed.toDouble())
                .put("observed", progress.observedAtEpochMillis)
                .put("completed", progress.completed),
        )
        return root.toString()
    }

    fun decodeProgress(json: String, sourceId: SourceId, nodeId: NodeId): PlaybackProgress? {
        val root = JSONObject(json.ifBlank { "{}" })
        val item = root.optJSONObject(progressKey(sourceId, nodeId)) ?: return null
        return PlaybackProgress(
            sourceId = sourceId,
            nodeId = nodeId,
            positionMillis = item.getLong("position"),
            durationMillis = if (item.isNull("duration")) null else item.getLong("duration"),
            playbackSpeed = item.getDouble("speed").toFloat(),
            observedAtEpochMillis = item.getLong("observed"),
            completed = item.optBoolean("completed"),
        )
    }

    private fun progressKey(sourceId: SourceId, nodeId: NodeId): String =
        sourceId.value + "\u001f" + nodeId.value
}
