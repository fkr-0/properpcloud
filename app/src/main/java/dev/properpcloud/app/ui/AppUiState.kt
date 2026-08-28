package dev.properpcloud.app.ui

import dev.properpcloud.app.data.SourceKind
import dev.properpcloud.app.playback.PlaybackUiState
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.NodeInspection
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueBuildResult
import dev.properpcloud.core.model.SearchMatchType
import dev.properpcloud.core.model.TrackSortKey

enum class AppDestination {
    LIBRARY,
    PLAYER,
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
    val sourceKind: SourceKind = SourceKind.DEMO,
    val sourceName: String = "Demo library",
    val pCloudConnected: Boolean = false,
    val pCloudLoginInProgress: Boolean = false,
    val clientId: String = "",
    val currentFolder: AudioFolder? = null,
    val breadcrumbs: List<AudioFolder> = emptyList(),
    val nodes: List<MediaNode> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val errorMessage: String? = null,
    val queue: PlaybackQueue = PlaybackQueue(),
    val queueBuildReport: QueueBuildResult? = null,
    val queueBuilding: Boolean = false,
    val sortKey: TrackSortKey = TrackSortKey.DISC_THEN_TRACK,
    val search: LibrarySearchUiState = LibrarySearchUiState(),
    val playbackHistoryEnabled: Boolean = false,
    val playbackHistoryRetention: Int = 100,
    val playback: PlaybackUiState = PlaybackUiState(),
    val inspection: NodeInspection? = null,
    val inspectedNodeName: String? = null,
    val metadataSelection: List<AudioTrack> = emptyList(),
    val metadataEditor: MetadataEditorUiState? = null,
    val message: String? = null,
)
