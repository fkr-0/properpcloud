package dev.fkr.properpcloud.app.ui

import dev.fkr.properpcloud.app.data.SourceKind
import dev.fkr.properpcloud.app.data.DirectoryBookmark
import dev.fkr.properpcloud.app.playback.PlaybackUiState
import dev.fkr.properpcloud.core.model.AudioTabCollection
import dev.fkr.properpcloud.core.model.AudioTabDefaults
import dev.fkr.properpcloud.core.model.AudioFolder
import dev.fkr.properpcloud.core.model.AudioTrack
import dev.fkr.properpcloud.core.model.MediaNode
import dev.fkr.properpcloud.core.model.NodeId
import dev.fkr.properpcloud.core.model.NodeInspection
import dev.fkr.properpcloud.core.model.PlaybackProgress
import dev.fkr.properpcloud.core.model.PlaybackQueue
import dev.fkr.properpcloud.core.model.PlayerTargetId
import dev.fkr.properpcloud.core.model.PlayerTargetSnapshot
import dev.fkr.properpcloud.core.model.QueueBuildResult
import dev.fkr.properpcloud.core.model.SearchMatchType
import dev.fkr.properpcloud.core.model.TrackSortKey

enum class AppDestination {
    LIBRARY,
    PLAYER,
    PLAYERS,
    QUEUE,
    METADATA,
    SETTINGS,
}

data class LibrarySearchUiState(
    val expanded: Boolean = false,
    val query: String = "",
    val matchTypes: Set<SearchMatchType> = SearchMatchType.entries.toSet(),
    val results: List<MediaNode> = emptyList(),
    val searching: Boolean = false,
)

data class AppUiState(
    val destination: AppDestination = AppDestination.LIBRARY,
    val playerReturnDestination: AppDestination = AppDestination.LIBRARY,
    val metadataReturnDestination: AppDestination = AppDestination.LIBRARY,
    val sourceKind: SourceKind = SourceKind.NONE,
    val sourceName: String = "No source connected",
    val pCloudConnected: Boolean = false,
    val pCloudLoginInProgress: Boolean = false,
    val serverConnected: Boolean = false,
    val serverConnectInProgress: Boolean = false,
    val serverBaseUrl: String = "",
    val clientId: String = "",
    val currentFolder: AudioFolder? = null,
    val breadcrumbs: List<AudioFolder> = emptyList(),
    val directoryBookmarks: List<DirectoryBookmark> = emptyList(),
    val nodes: List<MediaNode> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val errorMessage: String? = null,
    val queue: PlaybackQueue = PlaybackQueue(),
    val audioTabs: AudioTabCollection = AudioTabDefaults.collection(),
    val queueBuildReport: QueueBuildResult? = null,
    val queueBuilding: Boolean = false,
    val sortKey: TrackSortKey = TrackSortKey.DISC_THEN_TRACK,
    val search: LibrarySearchUiState = LibrarySearchUiState(),
    val playbackHistoryEnabled: Boolean = false,
    val playbackHistoryRetention: Int = 100,
    val playback: PlaybackUiState = PlaybackUiState(),
    val players: List<PlayerTargetSnapshot> = emptyList(),
    val activePlayerId: PlayerTargetId? = null,
    val playersRefreshing: Boolean = false,
    val progressByNodeId: Map<NodeId, PlaybackProgress> = emptyMap(),
    val sleepTimerEndsAtEpochMillis: Long? = null,
    val inspection: NodeInspection? = null,
    val inspectedNodeName: String? = null,
    val metadataSelection: List<AudioTrack> = emptyList(),
    val metadataEditor: MetadataEditorUiState? = null,
    val message: String? = null,
)
