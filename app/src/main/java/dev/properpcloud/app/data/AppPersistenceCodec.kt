package dev.properpcloud.app.data

import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackHistoryEntry
import dev.properpcloud.core.model.PlaybackHistoryPolicy
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
        val array = runCatching { JSONArray(json.ifBlank { "[]" }) }.getOrElse { JSONArray() }
        val entries = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching {
                    StoredQueueReference(
                        SourceId(item.getString("source")),
                        NodeId(item.getString("node")),
                        NodeId(item.getString("origin")),
                    )
                }.getOrNull()?.let(::add)
            }
        }
        return StoredQueue(entries, currentIndex)
    }

    fun upsertProgress(json: String, progress: PlaybackProgress): String {
        val root = runCatching { JSONObject(json.ifBlank { "{}" }) }.getOrElse { JSONObject() }
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
        val root = runCatching { JSONObject(json.ifBlank { "{}" }) }.getOrNull() ?: return null
        val item = root.optJSONObject(progressKey(sourceId, nodeId)) ?: return null
        return runCatching {
            PlaybackProgress(
                sourceId = sourceId,
                nodeId = nodeId,
                positionMillis = item.getLong("position"),
                durationMillis = if (item.isNull("duration")) null else item.getLong("duration"),
                playbackSpeed = item.getDouble("speed").toFloat(),
                observedAtEpochMillis = item.getLong("observed"),
                completed = item.optBoolean("completed"),
            )
        }.getOrNull()
    }

    fun upsertHistory(json: String, progress: PlaybackProgress, retention: Int): String =
        encodeHistory(PlaybackHistoryPolicy.upsert(decodeHistory(json), progress, retention))

    fun decodeHistory(json: String): List<PlaybackHistoryEntry> {
        val array = runCatching { JSONArray(json.ifBlank { "[]" }) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching {
                    PlaybackHistoryEntry(
                        sourceId = SourceId(item.getString("source")),
                        nodeId = NodeId(item.getString("node")),
                        positionMillis = item.getLong("position"),
                        durationMillis = if (item.isNull("duration")) null else item.getLong("duration"),
                        observedAtEpochMillis = item.getLong("observed"),
                        completed = item.optBoolean("completed"),
                    )
                }.getOrNull()?.let(::add)
            }
        }.sortedByDescending { it.observedAtEpochMillis }
    }

    private fun encodeHistory(entries: List<PlaybackHistoryEntry>): String = JSONArray().also { array ->
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("source", entry.sourceId.value)
                    .put("node", entry.nodeId.value)
                    .put("position", entry.positionMillis)
                    .put("duration", entry.durationMillis ?: JSONObject.NULL)
                    .put("observed", entry.observedAtEpochMillis)
                    .put("completed", entry.completed),
            )
        }
    }.toString()

    private fun progressKey(sourceId: SourceId, nodeId: NodeId): String =
        sourceId.value + "\u001f" + nodeId.value
}
