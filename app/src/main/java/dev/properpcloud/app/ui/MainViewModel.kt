package dev.properpcloud.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.properpcloud.app.AppContainer
import dev.properpcloud.app.data.SourceKind
import dev.properpcloud.app.playback.PlaybackController
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.FolderQueueAssembler
import dev.properpcloud.core.model.FolderQueueBuilder
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.core.model.QueueReducer
import dev.properpcloud.core.model.ResumePolicy
import dev.properpcloud.core.model.TrackSortKey
import dev.properpcloud.core.model.TrackSortPolicy
import dev.properpcloud.source.pcloud.PCloudSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
    private val container: AppContainer,
    private val playbackConnection: PlaybackController,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private var folderJob: Job? = null
    private var queueJob: Job? = null
    private var lastSavedMediaId: String? = null
    private var lastSavedPosition = -1L

    init {
        viewModelScope.launch {
            val settings = container.preferences.settings.first()
            val selected = if (settings.sourceKind == SourceKind.PCLOUD && container.sources.hasPCloudSession()) {
                SourceKind.PCLOUD
            } else {
                SourceKind.DEMO
            }
            container.sources.select(selected)
            _state.value = _state.value.copy(
                sourceKind = selected,
                clientId = settings.clientId,
                sortKey = settings.sortKey,
                pCloudConnected = container.sources.hasPCloudSession(),
            )
            openRoot()
            restoreQueue()
        }
        viewModelScope.launch {
            playbackConnection.state.collect { playback ->
                val queue = synchronizeQueueSelection(_state.value.queue, playback.mediaId)
                _state.value = _state.value.copy(playback = playback, queue = queue)
                checkpointProgress(queue, playback)
            }
        }
    }

    fun selectDestination(destination: AppDestination) {
        val current = _state.value
        _state.value = if (destination == AppDestination.PLAYER) {
            current.copy(
                destination = destination,
                playerReturnDestination = current.destination
                    .takeUnless { it == AppDestination.PLAYER }
                    ?: current.playerReturnDestination,
            )
        } else {
            current.copy(destination = destination)
        }
    }

    fun openRoot() = loadFolder(container.sources.current.value.root, replaceHistory = true)

    fun openFolder(folder: AudioFolder) = loadFolder(folder, replaceHistory = false)

    fun navigateBreadcrumb(index: Int) {
        val target = _state.value.breadcrumbs.getOrNull(index) ?: return
        loadFolder(target, replaceHistory = true)
    }

    fun refresh() {
        val folder = _state.value.currentFolder ?: return
        loadFolder(folder, replaceHistory = true, refreshing = true)
    }

    fun setSort(key: TrackSortKey) {
        val policy = TrackSortPolicy(listOf(key, TrackSortKey.NATURAL_FILENAME))
        _state.value = _state.value.copy(
            sortKey = key,
            nodes = FolderQueueBuilder.sortNodes(_state.value.nodes, policy),
        )
        viewModelScope.launch { container.preferences.updateSort(key) }
    }

    fun playTrack(track: AudioTrack) {
        val queue = QueueReducer.apply(
            _state.value.queue,
            QueueOperation.REPLACE,
            listOf(QueueEntry(track)),
        )
        applyQueue(queue, play = true)
    }

    fun enqueueTrack(track: AudioTrack, operation: QueueOperation) {
        val queue = QueueReducer.apply(_state.value.queue, operation, listOf(QueueEntry(track)))
        applyQueue(queue, play = operation == QueueOperation.REPLACE)
    }

    fun enqueueFolder(folder: AudioFolder, operation: QueueOperation, recursive: Boolean) {
        queueJob?.cancel()
        _state.value = _state.value.copy(
            queueBuilding = true,
            message = if (recursive) "Scanning folder tree…" else "Reading folder…",
        )
        queueJob = viewModelScope.launch {
            try {
                val result = FolderQueueAssembler(
                    container.sources.current.value,
                    TrackSortPolicy(listOf(_state.value.sortKey, TrackSortKey.NATURAL_FILENAME)),
                ).build(folder.id, recursive)
                if (result.entries.isEmpty()) {
                    _state.value = _state.value.copy(
                        queueBuildReport = result,
                        queueBuilding = false,
                        message = "No playable audio found; the existing queue was preserved.",
                    )
                    return@launch
                }
                val queue = QueueReducer.apply(_state.value.queue, operation, result.entries)
                _state.value = _state.value.copy(
                    queueBuildReport = result,
                    queueBuilding = false,
                    message = if (result.isPartial) {
                        "Queued ${result.entries.size} items with ${result.omissions.size} omission(s)."
                    } else {
                        "Queued ${result.entries.size} items."
                    },
                )
                applyQueue(queue, play = operation == QueueOperation.REPLACE)
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(
                    queueBuilding = false,
                    message = "Queue scan cancelled; the previous queue was preserved.",
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    queueBuilding = false,
                    message = error.userMessage("Could not build queue"),
                )
            }
        }
    }

    fun cancelQueueBuild() {
        queueJob?.cancel()
        _state.value = _state.value.copy(queueBuilding = false)
    }

    fun selectQueueItem(index: Int) {
        val queue = QueueReducer.select(_state.value.queue, index)
        _state.value = _state.value.copy(queue = queue)
        playbackConnection.select(index)
        viewModelScope.launch { container.preferences.saveQueue(queue) }
    }

    fun removeQueueItem(index: Int) {
        val queue = QueueReducer.remove(_state.value.queue, index)
        applyQueue(queue, play = _state.value.playback.isPlaying)
        _state.value = _state.value.copy(message = "Removed from queue")
    }

    fun moveQueueItem(from: Int, to: Int) {
        val queue = QueueReducer.move(_state.value.queue, from, to)
        applyQueue(queue, play = _state.value.playback.isPlaying)
    }

    fun clearQueue() {
        val queue = PlaybackQueue(generation = _state.value.queue.generation + 1)
        playbackConnection.clearQueue()
        _state.value = _state.value.copy(queue = queue, message = "Queue cleared")
        viewModelScope.launch { container.preferences.saveQueue(queue) }
    }

    fun openContainingFolder(track: AudioTrack) {
        viewModelScope.launch {
            val source = container.sources.source(track.sourceId) ?: return@launch
            val folder = source.load(track.parentId) as? AudioFolder ?: return@launch
            SourceKind.entries.firstOrNull { it.id == track.sourceId.value }
                ?.let(container.sources::select)
            _state.value = _state.value.copy(destination = AppDestination.LIBRARY)
            loadFolder(folder, replaceHistory = true)
        }
    }

    fun inspect(node: MediaNode) {
        viewModelScope.launch {
            val source = container.sources.source(node.sourceId) ?: return@launch
            runCatching { source.inspect(node.id) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        inspection = it,
                        inspectedNodeName = node.name,
                    )
                }
                .onFailure { _state.value = _state.value.copy(message = it.userMessage("Inspection failed")) }
        }
    }

    fun closeInspection() {
        _state.value = _state.value.copy(inspection = null, inspectedNodeName = null)
    }

    fun updateClientId(value: String) {
        _state.value = _state.value.copy(clientId = value)
        viewModelScope.launch { container.preferences.updateClientId(value) }
    }

    fun useDemoSource() {
        container.sources.select(SourceKind.DEMO)
        _state.value = _state.value.copy(sourceKind = SourceKind.DEMO, sourceName = "Demo library")
        viewModelScope.launch {
            container.preferences.updateSource(SourceKind.DEMO)
            openRoot()
        }
    }

    fun usePCloudSource() {
        if (!container.sources.select(SourceKind.PCLOUD)) {
            _state.value = _state.value.copy(message = "Connect pCloud in Settings first.")
            return
        }
        _state.value = _state.value.copy(sourceKind = SourceKind.PCLOUD, sourceName = "pCloud")
        viewModelScope.launch {
            container.preferences.updateSource(SourceKind.PCLOUD)
            openRoot()
        }
    }

    fun onPCloudAuthorized(session: PCloudSession) {
        runCatching { container.sources.installPCloud(session) }
            .onSuccess {
                _state.value = _state.value.copy(
                    sourceKind = SourceKind.PCLOUD,
                    sourceName = "pCloud",
                    pCloudConnected = true,
                    message = "pCloud connected. The access token is encrypted on this device.",
                )
                viewModelScope.launch {
                    container.preferences.updateSource(SourceKind.PCLOUD)
                    openRoot()
                }
            }
            .onFailure { _state.value = _state.value.copy(message = it.userMessage("Could not save pCloud session")) }
    }

    fun disconnectPCloud() {
        container.sources.disconnectPCloud()
        _state.value = _state.value.copy(
            sourceKind = SourceKind.DEMO,
            sourceName = "Demo library",
            pCloudConnected = false,
            message = "pCloud session removed from this device.",
        )
        viewModelScope.launch {
            container.preferences.updateSource(SourceKind.DEMO)
            openRoot()
        }
    }

    fun showMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun playPause() = playbackConnection.playPause()
    fun skipNext() = playbackConnection.skipNext()
    fun skipPrevious() = playbackConnection.skipPrevious()
    fun seekBy(deltaMillis: Long) = playbackConnection.seekBy(deltaMillis)
    fun seekTo(positionMillis: Long) = playbackConnection.seekTo(positionMillis)

    private fun loadFolder(folder: AudioFolder, replaceHistory: Boolean, refreshing: Boolean = false) {
        folderJob?.cancel()
        _state.value = _state.value.copy(
            loading = !refreshing,
            refreshing = refreshing,
            errorMessage = null,
        )
        folderJob = viewModelScope.launch {
            val source = container.sources.source(folder.sourceId) ?: container.sources.current.value
            runCatching {
                val nodes = FolderQueueBuilder.sortNodes(
                    source.list(folder.id),
                    TrackSortPolicy(listOf(_state.value.sortKey, TrackSortKey.NATURAL_FILENAME)),
                )
                val breadcrumbs = if (replaceHistory) buildBreadcrumb(source, folder) else {
                    val existing = _state.value.breadcrumbs
                    val currentIndex = existing.indexOfFirst { it.id == folder.id }
                    if (currentIndex >= 0) existing.take(currentIndex + 1) else existing + folder
                }
                Triple(nodes, breadcrumbs, source)
            }.onSuccess { (nodes, breadcrumbs, source) ->
                _state.value = _state.value.copy(
                    sourceKind = SourceKind.entries.firstOrNull { it.id == source.id.value } ?: SourceKind.DEMO,
                    sourceName = source.root.name,
                    currentFolder = folder,
                    breadcrumbs = breadcrumbs,
                    nodes = nodes,
                    loading = false,
                    refreshing = false,
                )
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    errorMessage = error.userMessage("Could not load folder"),
                )
            }
        }
    }

    private suspend fun buildBreadcrumb(
        source: dev.properpcloud.core.model.AudioSource,
        folder: AudioFolder,
    ): List<AudioFolder> {
        val reverse = mutableListOf<AudioFolder>()
        val visited = mutableSetOf<NodeId>()
        var current: AudioFolder? = folder
        while (current != null && visited.add(current.id)) {
            reverse += current
            val parentId = current.parentId
            if (current.id == source.root.id || parentId == null) break
            current = source.load(parentId) as? AudioFolder
        }
        return reverse.asReversed()
    }

    private fun applyQueue(queue: PlaybackQueue, play: Boolean) {
        _state.value = _state.value.copy(queue = queue)
        playbackConnection.setQueue(queue, play)
        viewModelScope.launch { container.preferences.saveQueue(queue) }
    }

    private suspend fun restoreQueue() {
        val stored = container.preferences.loadQueue()
        val entries = stored.entries.mapNotNull { reference ->
            val source = container.sources.source(reference.sourceId) ?: return@mapNotNull null
            val track = runCatching { source.load(reference.nodeId) as? AudioTrack }.getOrNull() ?: return@mapNotNull null
            QueueEntry(track, reference.originFolderId)
        }
        if (entries.isEmpty()) return
        val queue = PlaybackQueue(
            generation = 1,
            entries = entries,
            currentIndex = stored.currentIndex.coerceIn(0, entries.lastIndex),
        )
        _state.value = _state.value.copy(queue = queue)
        playbackConnection.setQueue(queue, play = false)
        queue.current?.track?.let { track ->
            container.preferences.loadProgress(track.sourceId, track.id)?.let { progress ->
                val normalized = ResumePolicy().normalize(progress, System.currentTimeMillis())
                playbackConnection.seekTo(normalized.positionMillis)
            }
        }
    }

    private fun synchronizeQueueSelection(queue: PlaybackQueue, mediaId: String?): PlaybackQueue {
        if (mediaId == null) return queue
        val (sourceId, nodeId) = runCatching { MediaIdentity.decode(mediaId) }.getOrNull() ?: return queue
        val index = queue.entries.indexOfFirst { it.track.sourceId == sourceId && it.track.id == nodeId }
        return if (index >= 0 && index != queue.currentIndex) queue.copy(currentIndex = index) else queue
    }

    private fun checkpointProgress(queue: PlaybackQueue, playback: dev.properpcloud.app.playback.PlaybackUiState) {
        val current = queue.current?.track ?: return
        val mediaId = playback.mediaId ?: return
        val shouldSave = mediaId != lastSavedMediaId ||
            kotlin.math.abs(playback.positionMillis - lastSavedPosition) >= 10_000 ||
            !playback.isPlaying
        if (!shouldSave) return
        lastSavedMediaId = mediaId
        lastSavedPosition = playback.positionMillis
        viewModelScope.launch {
            container.preferences.saveProgress(
                PlaybackProgress(
                    sourceId = current.sourceId,
                    nodeId = current.id,
                    positionMillis = playback.positionMillis,
                    durationMillis = playback.durationMillis.takeIf { it > 0 } ?: current.durationMillis,
                    observedAtEpochMillis = System.currentTimeMillis(),
                    completed = playback.durationMillis > 0 && playback.positionMillis >= playback.durationMillis * 0.95,
                ),
            )
        }
    }

    class Factory(
        private val application: Application,
        private val container: AppContainer,
        private val playbackConnection: PlaybackController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(application, container, playbackConnection) as T
    }
}

private fun Throwable.userMessage(prefix: String): String =
    "$prefix: ${message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()}"
