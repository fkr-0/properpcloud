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
        val payload = AppPersistenceCodec.encodeQueue(queue)
        dataStore.edit {
            it[QUEUE_JSON] = payload.json
            it[QUEUE_INDEX] = payload.currentIndex
        }
    }

    suspend fun loadQueue(): StoredQueue {
        val preferences = dataStore.data.first()
        return AppPersistenceCodec.decodeQueue(
            preferences[QUEUE_JSON].orEmpty(),
            preferences[QUEUE_INDEX] ?: -1,
        )
    }

    suspend fun saveProgress(progress: PlaybackProgress) {
        dataStore.edit { preferences ->
            preferences[PROGRESS_JSON] = AppPersistenceCodec.upsertProgress(
                preferences[PROGRESS_JSON].orEmpty(),
                progress,
            )
        }
    }

    suspend fun loadProgress(sourceId: SourceId, nodeId: NodeId): PlaybackProgress? {
        val preferences = dataStore.data.first()
        return AppPersistenceCodec.decodeProgress(preferences[PROGRESS_JSON].orEmpty(), sourceId, nodeId)
    }

    private fun decodeSettings(preferences: Preferences): StoredSettings = StoredSettings(
        clientId = preferences[CLIENT_ID].orEmpty(),
        sourceKind = SourceKind.entries.firstOrNull { it.id == preferences[SOURCE_KIND] } ?: SourceKind.DEMO,
        sortKey = TrackSortKey.entries.firstOrNull { it.name == preferences[SORT_KEY] }
            ?: TrackSortKey.DISC_THEN_TRACK,
    )

    private companion object {
        val CLIENT_ID = stringPreferencesKey("pcloud_client_id")
        val SOURCE_KIND = stringPreferencesKey("selected_source")
        val SORT_KEY = stringPreferencesKey("sort_key")
        val QUEUE_JSON = stringPreferencesKey("queue_json")
        val QUEUE_INDEX = intPreferencesKey("queue_index")
        val PROGRESS_JSON = stringPreferencesKey("progress_json")
    }
}
