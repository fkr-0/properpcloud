package dev.properpcloud.app.data

import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.AudioTabCollection
import dev.properpcloud.core.model.AudioTabColor
import dev.properpcloud.core.model.AudioTabId
import dev.properpcloud.core.model.PlayerRepeatMode
import dev.properpcloud.core.model.PlaybackHistoryEntry
import dev.properpcloud.core.model.PlaybackHistoryPolicy
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.normalizeAudioRootPath
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

    fun encodeAudioTabs(collection: AudioTabCollection): String = JSONObject().also { root ->
        root.put("version", 1)
        root.put("activeTab", collection.activeTabId.value)
        root.put(
            "tabs",
            JSONArray().also { tabs ->
                collection.tabs.forEach { tab ->
                    tabs.put(
                        JSONObject()
                            .put("id", tab.definition.id.value)
                            .put("name", tab.definition.name)
                            .put("rootPath", tab.definition.rootPath)
                            .put("icon", tab.definition.icon ?: JSONObject.NULL)
                            .put("color", tab.definition.color?.name ?: JSONObject.NULL)
                            .put("queue", encodeQueueReferences(tab.queue.entries))
                            .put("queueIndex", tab.queue.currentIndex)
                            .put("currentFolder", tab.currentFolderId?.value ?: JSONObject.NULL)
                            .put("position", tab.playbackPositionMillis)
                            .put("speed", tab.playbackSpeed.toDouble())
                            .put("volume", tab.volume.toDouble())
                            .put("shuffle", tab.shuffle)
                            .put("repeat", tab.repeatMode.name),
                    )
                }
            },
        )
        root.put(
            "playlists",
            JSONArray().also { playlists ->
                collection.playlists.forEach { playlist ->
                    playlists.put(
                        JSONObject()
                            .put("name", playlist.name)
                            .put("entries", encodeQueueReferences(playlist.entries)),
                    )
                }
            },
        )
    }.toString()

    fun decodeAudioTabs(json: String): StoredAudioTabs? {
        if (json.isBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optInt("version") != 1) return null
        val array = root.optJSONArray("tabs") ?: return null
        val tabs = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching {
                    val id = AudioTabId(item.getString("id"))
                    val rootPath = normalizeAudioRootPath(item.optString("rootPath"))
                    StoredAudioTab(
                        id = id,
                        name = item.getString("name").trim().also { require(it.isNotEmpty()) },
                        rootPath = rootPath,
                        icon = item.optStringOrNull("icon"),
                        color = item.optStringOrNull("color")?.let { name -> AudioTabColor.entries.firstOrNull { it.name == name } },
                        queue = StoredQueue(
                            entries = decodeQueueReferences(item.optJSONArray("queue")),
                            currentIndex = item.optInt("queueIndex", -1),
                        ),
                        currentFolderId = item.optStringOrNull("currentFolder")?.let(::NodeId),
                        playbackPositionMillis = item.optLong("position", 0).coerceAtLeast(0),
                        playbackSpeed = item.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 3f),
                        volume = item.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f),
                        shuffle = item.optBoolean("shuffle", false),
                        repeatMode = PlayerRepeatMode.entries.firstOrNull { it.name == item.optString("repeat") }
                            ?: PlayerRepeatMode.OFF,
                    )
                }.getOrNull()?.let(::add)
            }
        }
        if (tabs.isEmpty() || tabs.map { it.id }.distinct().size != tabs.size) return null
        val requestedActive = runCatching { AudioTabId(root.getString("activeTab")) }.getOrNull()
        val active = requestedActive?.takeIf { candidate -> tabs.any { it.id == candidate } } ?: tabs.first().id
        val playlists = buildList {
            val playlistArray = root.optJSONArray("playlists") ?: JSONArray()
            for (index in 0 until playlistArray.length()) {
                val item = playlistArray.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                if (name.isNotEmpty()) add(StoredNamedPlaylist(name, decodeQueueReferences(item.optJSONArray("entries"))))
            }
        }.distinctBy { it.name.lowercase() }
        return StoredAudioTabs(tabs, active, playlists)
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

    private fun encodeQueueReferences(entries: List<dev.properpcloud.core.model.QueueEntry>): JSONArray = JSONArray().also { array ->
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("source", entry.track.sourceId.value)
                    .put("node", entry.track.id.value)
                    .put("origin", entry.originFolderId.value),
            )
        }
    }

    private fun decodeQueueReferences(array: JSONArray?): List<StoredQueueReference> {
        if (array == null) return emptyList()
        return buildList {
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
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)
}
