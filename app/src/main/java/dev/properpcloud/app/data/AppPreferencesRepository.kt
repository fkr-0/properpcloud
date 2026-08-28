package dev.properpcloud.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackHistoryEntry
import dev.properpcloud.core.model.PlaybackHistoryPolicy
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.SearchMatchType
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
    val searchMatchTypes: Set<SearchMatchType> = SearchMatchType.entries.toSet(),
    val playbackHistoryEnabled: Boolean = false,
    val playbackHistoryRetention: Int = PlaybackHistoryPolicy.DEFAULT_RETENTION,
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

    suspend fun loadPlaybackHistory(): List<PlaybackHistoryEntry> =
        AppPersistenceCodec.decodeHistory(dataStore.data.first()[HISTORY_JSON].orEmpty())

    suspend fun updateSource(kind: SourceKind) {
        dataStore.edit { it[SOURCE_KIND] = kind.id }
    }

    suspend fun updateSort(key: TrackSortKey) {
        dataStore.edit { it[SORT_KEY] = key.name }
    }

    suspend fun updateSearchMatchTypes(types: Set<SearchMatchType>) {
        dataStore.edit { it[SEARCH_MATCH_TYPES] = types.sortedBy { type -> type.ordinal }.joinToString(",") { type -> type.name } }
    }

    suspend fun updatePlaybackHistoryEnabled(enabled: Boolean) {
        dataStore.edit { it[HISTORY_ENABLED] = enabled }
    }

    suspend fun updatePlaybackHistoryRetention(retention: Int) {
        dataStore.edit { it[HISTORY_RETENTION] = PlaybackHistoryPolicy.normalizeRetention(retention) }
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
            if (preferences[HISTORY_ENABLED] == true) {
                preferences[HISTORY_JSON] = AppPersistenceCodec.upsertHistory(
                    preferences[HISTORY_JSON].orEmpty(),
                    progress,
                    preferences[HISTORY_RETENTION] ?: PlaybackHistoryPolicy.DEFAULT_RETENTION,
                )
            }
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
        searchMatchTypes = preferences[SEARCH_MATCH_TYPES]?.let { encoded ->
            encoded.split(',')
                .filter(String::isNotBlank)
                .mapNotNull { name -> SearchMatchType.entries.firstOrNull { it.name == name } }
                .toSet()
        } ?: SearchMatchType.entries.toSet(),
        playbackHistoryEnabled = preferences[HISTORY_ENABLED] ?: false,
        playbackHistoryRetention = PlaybackHistoryPolicy.normalizeRetention(
            preferences[HISTORY_RETENTION] ?: PlaybackHistoryPolicy.DEFAULT_RETENTION,
        ),
    )

    private companion object {
        val CLIENT_ID = stringPreferencesKey("pcloud_client_id")
        val SOURCE_KIND = stringPreferencesKey("selected_source")
        val SORT_KEY = stringPreferencesKey("sort_key")
        val QUEUE_JSON = stringPreferencesKey("queue_json")
        val QUEUE_INDEX = intPreferencesKey("queue_index")
        val PROGRESS_JSON = stringPreferencesKey("progress_json")
        val SEARCH_MATCH_TYPES = stringPreferencesKey("search_match_types")
        val HISTORY_ENABLED = booleanPreferencesKey("playback_history_enabled")
        val HISTORY_RETENTION = intPreferencesKey("playback_history_retention")
        val HISTORY_JSON = stringPreferencesKey("playback_history_json")
    }
}
