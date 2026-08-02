package dev.properpcloud.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TrackSortKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.properpcloudDataStore by preferencesDataStore(name = "properpcloud")

data class StoredSettings(
    val clientId: String = "",
    val sourceKind: SourceKind = SourceKind.DEMO,
    val sortKey: TrackSortKey = TrackSortKey.DISC_THEN_TRACK,
)

data class StoredQueueReference(
    val sourceId: SourceId,
    val nodeId: NodeId,
    val originFolderId: NodeId,
)

data class StoredQueue(
    val entries: List<StoredQueueReference>,
    val currentIndex: Int,
)

class AppPreferencesRepository(context: Context) {
    private val dataStore = context.applicationContext.properpcloudDataStore

    val settings: Flow<StoredSettings> = dataStore.data.map(::decodeSettings)

    suspend fun updateClientId(value: String) {
        dataStore.edit { it[CLIENT_ID] = value.trim() }
    }

    suspend fun updateSource(kind: SourceKind) {
        dataStore.edit { it[SOURCE_KIND] = kind.id }
    }

    suspend fun updateSort(key: TrackSortKey) {
        dataStore.edit { it[SORT_KEY] = key.name }
    }

    suspend fun saveQueue(queue: PlaybackQueue) {
        val array = JSONArray()
        queue.entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("source", entry.track.sourceId.value)
                    .put("node", entry.track.id.value)
                    .put("origin", entry.originFolderId.value),
            )
        }
        dataStore.edit {
            it[QUEUE_JSON] = array.toString()
            it[QUEUE_INDEX] = queue.currentIndex
        }
    }

    suspend fun loadQueue(): StoredQueue {
        val preferences = dataStore.data.first()
        val array = JSONArray(preferences[QUEUE_JSON] ?: "[]")
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
        return StoredQueue(entries, preferences[QUEUE_INDEX] ?: -1)
    }

    suspend fun saveProgress(progress: PlaybackProgress) {
        dataStore.edit { preferences ->
            val root = JSONObject(preferences[PROGRESS_JSON] ?: "{}")
            root.put(
                progressKey(progress.sourceId, progress.nodeId),
                JSONObject()
                    .put("position", progress.positionMillis)
                    .put("duration", progress.durationMillis ?: JSONObject.NULL)
                    .put("speed", progress.playbackSpeed.toDouble())
                    .put("observed", progress.observedAtEpochMillis)
                    .put("completed", progress.completed),
            )
            preferences[PROGRESS_JSON] = root.toString()
        }
    }

    suspend fun loadProgress(sourceId: SourceId, nodeId: NodeId): PlaybackProgress? {
        val preferences = dataStore.data.first()
        val root = JSONObject(preferences[PROGRESS_JSON] ?: "{}")
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

    private fun decodeSettings(preferences: Preferences): StoredSettings = StoredSettings(
        clientId = preferences[CLIENT_ID].orEmpty(),
        sourceKind = SourceKind.entries.firstOrNull { it.id == preferences[SOURCE_KIND] } ?: SourceKind.DEMO,
        sortKey = TrackSortKey.entries.firstOrNull { it.name == preferences[SORT_KEY] }
            ?: TrackSortKey.DISC_THEN_TRACK,
    )

    private fun progressKey(sourceId: SourceId, nodeId: NodeId): String = sourceId.value + "\u001f" + nodeId.value

    private companion object {
        val CLIENT_ID = stringPreferencesKey("pcloud_client_id")
        val SOURCE_KIND = stringPreferencesKey("selected_source")
        val SORT_KEY = stringPreferencesKey("sort_key")
        val QUEUE_JSON = stringPreferencesKey("queue_json")
        val QUEUE_INDEX = intPreferencesKey("queue_index")
        val PROGRESS_JSON = stringPreferencesKey("progress_json")
    }
}
