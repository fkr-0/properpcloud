package dev.properpcloud.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.properpcloud.app.AppContainer
import dev.properpcloud.app.data.SourceKind
import dev.properpcloud.app.metadata.BatchFieldDraft
import dev.properpcloud.app.metadata.LoadedMetadataItem
import dev.properpcloud.app.metadata.MetadataDraftPlanner
import dev.properpcloud.app.metadata.MetadataExportArtifact
import dev.properpcloud.app.playback.PlaybackController
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.FolderQueueAssembler
import dev.properpcloud.core.model.FolderQueueBuilder
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackCheckpointCursor
import dev.properpcloud.core.model.PlaybackCheckpointPolicy
import dev.properpcloud.core.model.PlaybackObservation
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.core.model.QueueReducer
import dev.properpcloud.core.model.ResumePolicy
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TrackSortKey
import dev.properpcloud.core.model.TrackSortPolicy
import dev.properpcloud.source.pcloud.PCloudSession
import dev.properpcloud.source.pcloud.PCloudRevocationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    private val checkpointPolicy = PlaybackCheckpointPolicy()
    private var checkpointCursor = PlaybackCheckpointCursor()
    private var lastPlaybackError: String? = null
    private var metadataJob: Job? = null
    private var singleMetadataItem: LoadedMetadataItem? = null
    private val batchMetadataItems = linkedMapOf<String, LoadedMetadataItem>()
    private var metadataExportArtifact: MetadataExportArtifact? = null

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
                val newError = playback.error?.takeIf { it != lastPlaybackError }
                lastPlaybackError = playback.error
                _state.value = _state.value.copy(
                    playback = playback,
                    queue = queue,
                    message = newError?.let { "Playback controller reported: $it" } ?: _state.value.message,
                )
                checkpointProgress(queue, playback)
            }
        }
    }

    private fun beginMetadataWorkspace(title: String, total: Int = 1) {
        metadataJob?.cancel()
        discardMetadataSources()
        metadataExportArtifact = null
        val current = _state.value
        _state.value = current.copy(
            destination = AppDestination.METADATA,
            metadataReturnDestination = current.destination.takeUnless { it == AppDestination.METADATA }
                ?: current.metadataReturnDestination,
            metadataEditor = MetadataEditorUiState.Loading(title, total = total),
        )
    }

    private fun discardMetadataSources() {
        val items = buildList {
            singleMetadataItem?.let(::add)
            addAll(batchMetadataItems.values)
        }
        container.metadata.discard(items)
        singleMetadataItem = null
        batchMetadataItems.clear()
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
        } else if (destination == AppDestination.METADATA) {
            current.copy(
                destination = destination,
                metadataReturnDestination = current.destination
                    .takeUnless { it == AppDestination.METADATA }
                    ?: current.metadataReturnDestination,
            )
        } else {
            current.copy(destination = destination)
        }
    }

    fun openMetadataEditor(track: AudioTrack) {
        beginMetadataWorkspace("Loading tags for ${track.name}")
        metadataJob = viewModelScope.launch {
            try {
                val source = requireNotNull(container.sources.source(track.sourceId)) { "audio source is unavailable" }
                val loaded = container.metadata.load(source, track)
                singleMetadataItem = loaded
                _state.value = _state.value.copy(
                    metadataEditor = MetadataEditorUiState.Single(
                        track = track,
                        original = loaded.snapshot,
                        draft = MetadataDraftPlanner.draft(loaded.snapshot),
                        sourceRevision = loaded.prepared.expectedRevision,
                        sourceHash = loaded.prepared.expectedContentHash,
                    ),
                )
            } catch (_: CancellationException) {
                Unit
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    metadataEditor = MetadataEditorUiState.Failure(track.name, error.userMessage("Could not prepare metadata editor")),
                )
            }
        }
    }

    fun toggleMetadataSelection(track: AudioTrack) {
        val current = _state.value.metadataSelection
        val selected = current.any { it.sourceId == track.sourceId && it.id == track.id }
        val updated = if (selected) {
            current.filterNot { it.sourceId == track.sourceId && it.id == track.id }
        } else {
            if (current.size >= MAX_METADATA_BATCH_ITEMS) {
                _state.value = _state.value.copy(message = "Tag batches are limited to $MAX_METADATA_BATCH_ITEMS files.")
                return
            }
            current + track
        }
        _state.value = _state.value.copy(metadataSelection = updated)
    }

    fun clearMetadataSelection() {
        _state.value = _state.value.copy(metadataSelection = emptyList())
    }

    fun openBatchMetadataEditor() {
        val tracks = _state.value.metadataSelection
        if (tracks.isEmpty()) {
            _state.value = _state.value.copy(message = "Select at least one audio file first.")
            return
        }
        beginMetadataWorkspace("Preparing ${tracks.size} files", total = tracks.size)
        metadataJob = viewModelScope.launch {
            val loaded = mutableListOf<LoadedMetadataItem>()
            val failures = mutableListOf<String>()
            tracks.forEachIndexed { index, track ->
                _state.value = _state.value.copy(
                    metadataEditor = MetadataEditorUiState.Loading(
                        title = "Preparing ${track.name}",
                        completed = index,
                        total = tracks.size,
                    ),
                )
                try {
                    val source = requireNotNull(container.sources.source(track.sourceId)) { "audio source is unavailable" }
                    loaded += container.metadata.load(source, track)
                } catch (error: CancellationException) {
                    container.metadata.discard(loaded)
                    throw error
                } catch (error: Throwable) {
                    failures += "${track.name}: ${error.message.orEmpty()}"
                }
            }
            batchMetadataItems.clear()
            loaded.forEach { batchMetadataItems[it.track.metadataKey()] = it }
            if (loaded.isEmpty()) {
                _state.value = _state.value.copy(
                    metadataEditor = MetadataEditorUiState.Failure(
                        "Batch metadata",
                        failures.firstOrNull() ?: "No selected file could be prepared.",
                    ),
                )
                return@launch
            }
            _state.value = _state.value.copy(
                metadataEditor = MetadataEditorUiState.Batch(
                    items = loaded.map { MetadataEditorUiState.BatchItem(it.track, it.snapshot) },
                    commonFields = MetadataDraftPlanner.commonBatchFields.associateWith { BatchFieldDraft() },
                    status = failures.takeIf(List<String>::isNotEmpty)?.let {
                        "Prepared ${loaded.size}; ${it.size} file(s) were skipped."
                    },
                ),
            )
        }
    }

    fun closeMetadataEditor() {
        metadataJob?.cancel()
        discardMetadataSources()
        val current = _state.value
        _state.value = current.copy(
            destination = current.metadataReturnDestination,
            metadataEditor = null,
        )
    }

    fun updateMetadataField(field: TagField, value: String) {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Single ?: return
        _state.value = _state.value.copy(
            metadataEditor = editor.copy(
                draft = editor.draft + (field to value),
                phase = MetadataPhase.READY,
                artifact = null,
                status = null,
            ),
        )
        metadataExportArtifact = null
    }

    fun resetMetadataField(field: TagField) {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Single ?: return
        updateMetadataField(field, editor.original.fields[field]?.value.orEmpty())
    }

    fun searchMetadata() {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Single ?: return
        val loaded = singleMetadataItem ?: return
        metadataJob?.cancel()
        _state.value = _state.value.copy(metadataEditor = editor.copy(phase = MetadataPhase.SEARCHING, status = null))
        metadataJob = viewModelScope.launch {
            try {
                val candidates = container.metadata.search(loaded, editor.draft)
                val current = _state.value.metadataEditor as? MetadataEditorUiState.Single ?: return@launch
                _state.value = _state.value.copy(
                    metadataEditor = current.copy(
                        phase = MetadataPhase.READY,
                        candidates = candidates,
                        selectedCandidateId = null,
                        acceptedCandidateFields = emptySet(),
                        status = if (candidates.isEmpty()) "MusicBrainz returned no matching recordings." else null,
                    ),
                )
            } catch (_: CancellationException) {
                Unit
            } catch (error: Throwable) {
                val current = _state.value.metadataEditor as? MetadataEditorUiState.Single ?: return@launch
                _state.value = _state.value.copy(
                    metadataEditor = current.copy(
                        phase = MetadataPhase.READY,
                        status = error.userMessage("MusicBrainz search failed"),
                    ),
                )
            }
        }
    }

    fun selectMetadataCandidate(candidateId: String?) {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Single ?: return
        val candidate = editor.candidates.firstOrNull { it.id == candidateId }
        _state.value = _state.value.copy(
            metadataEditor = editor.copy(
                selectedCandidateId = candidate?.id,
                acceptedCandidateFields = candidate?.fields?.keys
                    ?.intersect(MetadataDraftPlanner.onlineCandidateFields)
                    .orEmpty(),
            ),
        )
    }

    fun toggleMetadataCandidateField(field: TagField) {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Single ?: return
        val accepted = editor.acceptedCandidateFields.toMutableSet().apply {
            if (!add(field)) remove(field)
        }
        _state.value = _state.value.copy(metadataEditor = editor.copy(acceptedCandidateFields = accepted))
    }

    fun applyMetadataCandidate() {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Single ?: return
        val candidate = editor.candidates.firstOrNull { it.id == editor.selectedCandidateId } ?: return
        _state.value = _state.value.copy(
            metadataEditor = editor.copy(
                draft = MetadataDraftPlanner.applyCandidate(editor.draft, candidate, editor.acceptedCandidateFields),
                artifact = null,
                phase = MetadataPhase.READY,
                status = "Applied ${editor.acceptedCandidateFields.size} proposed field(s) to the draft.",
            ),
        )
        metadataExportArtifact = null
    }

    fun stageMetadata() {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Single ?: return
        val loaded = singleMetadataItem ?: return
        val patch = MetadataDraftPlanner.patch(editor.original, editor.draft)
        if (patch.changedFields(editor.original).isEmpty()) {
            _state.value = _state.value.copy(metadataEditor = editor.copy(status = "The draft contains no tag changes."))
            return
        }
        metadataJob?.cancel()
        _state.value = _state.value.copy(metadataEditor = editor.copy(phase = MetadataPhase.STAGING, status = null))
        metadataJob = viewModelScope.launch {
            try {
                val result = container.metadata.stage(loaded, patch)
                val artifact = container.metadata.artifact(result)
                metadataExportArtifact = artifact
                val current = _state.value.metadataEditor as? MetadataEditorUiState.Single ?: return@launch
                _state.value = _state.value.copy(
                    metadataEditor = current.copy(
                        phase = MetadataPhase.STAGED,
                        artifact = artifact.toUi(),
                        status = "Verified ${result.changedFields.size} changed field(s) on a separate candidate file.",
                    ),
                )
            } catch (_: CancellationException) {
                Unit
            } catch (error: Throwable) {
                val current = _state.value.metadataEditor as? MetadataEditorUiState.Single ?: return@launch
                _state.value = _state.value.copy(
                    metadataEditor = current.copy(
                        phase = MetadataPhase.READY,
                        status = error.userMessage("Could not stage tag changes"),
                    ),
                )
            }
        }
    }

    fun updateBatchField(field: TagField, edit: BatchFieldDraft) {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Batch ?: return
        _state.value = _state.value.copy(
            metadataEditor = editor.copy(
                commonFields = editor.commonFields + (field to edit),
                phase = MetadataPhase.READY,
                artifact = null,
            ),
        )
        metadataExportArtifact = null
    }

    fun updateBatchSequence(enabled: Boolean, start: String, includeTotal: Boolean) {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Batch ?: return
        _state.value = _state.value.copy(
            metadataEditor = editor.copy(
                sequenceTracks = enabled,
                sequenceStart = start,
                includeTrackTotal = includeTotal,
                phase = MetadataPhase.READY,
                artifact = null,
            ),
        )
        metadataExportArtifact = null
    }

    fun searchBatchMetadata() {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Batch ?: return
        metadataJob?.cancel()
        _state.value = _state.value.copy(
            metadataEditor = editor.copy(
                phase = MetadataPhase.SEARCHING,
                progressCompleted = 0,
                progressTotal = editor.items.size,
                status = "MusicBrainz receives title, artist, album, ISRC, and duration—not audio bytes.",
            ),
        )
        metadataJob = viewModelScope.launch {
            var items = editor.items
            items.forEachIndexed { index, item ->
                val loaded = batchMetadataItems[item.track.metadataKey()] ?: return@forEachIndexed
                val result = try {
                    Result.success(container.metadata.search(loaded, MetadataDraftPlanner.draft(loaded.snapshot)).take(3))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                items = items.map { current ->
                    if (current.track.metadataKey() != item.track.metadataKey()) current else current.copy(
                        candidates = result.getOrDefault(emptyList()),
                        selectedCandidateId = null,
                        acceptedCandidateFields = emptySet(),
                        status = result.exceptionOrNull()?.userMessage("Search failed")
                            ?: if (result.getOrDefault(emptyList()).isEmpty()) "No suggestion" else null,
                    )
                }
                val current = _state.value.metadataEditor as? MetadataEditorUiState.Batch ?: return@launch
                _state.value = _state.value.copy(
                    metadataEditor = current.copy(items = items, progressCompleted = index + 1),
                )
            }
            val current = _state.value.metadataEditor as? MetadataEditorUiState.Batch ?: return@launch
            _state.value = _state.value.copy(
                metadataEditor = current.copy(
                    phase = MetadataPhase.READY,
                    status = "Suggestions are review-only until selected per file.",
                ),
            )
        }
    }

    fun selectBatchCandidate(track: AudioTrack, candidateId: String?) {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Batch ?: return
        val items = editor.items.map { item ->
            if (item.track.metadataKey() != track.metadataKey()) item else {
                val candidate = item.candidates.firstOrNull { it.id == candidateId }
                item.copy(
                    selectedCandidateId = candidate?.id,
                    acceptedCandidateFields = candidate?.fields?.keys
                        ?.intersect(MetadataDraftPlanner.onlineCandidateFields)
                        .orEmpty(),
                )
            }
        }
        _state.value = _state.value.copy(metadataEditor = editor.copy(items = items, artifact = null))
        metadataExportArtifact = null
    }

    fun toggleBatchCandidateField(track: AudioTrack, field: TagField) {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Batch ?: return
        val items = editor.items.map { item ->
            if (item.track.metadataKey() != track.metadataKey()) item else item.copy(
                acceptedCandidateFields = item.acceptedCandidateFields.toMutableSet().apply {
                    if (!add(field)) remove(field)
                },
            )
        }
        _state.value = _state.value.copy(metadataEditor = editor.copy(items = items, artifact = null))
        metadataExportArtifact = null
    }

    fun stageBatchMetadata() {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Batch ?: return
        val startAt = editor.sequenceStart.toIntOrNull()
        if (editor.sequenceTracks && (startAt == null || startAt <= 0)) {
            _state.value = _state.value.copy(metadataEditor = editor.copy(status = "Track sequence must start with a positive number."))
            return
        }
        metadataJob?.cancel()
        _state.value = _state.value.copy(
            metadataEditor = editor.copy(
                phase = MetadataPhase.STAGING,
                progressCompleted = 0,
                progressTotal = editor.items.size,
                status = null,
            ),
        )
        metadataJob = viewModelScope.launch {
            val results = mutableListOf<dev.properpcloud.metadata.tags.StagedTagResult>()
            var artifact: MetadataExportArtifact? = null
            var committed = false
            try {
            var items = editor.items
            val total = startAt?.let { it + editor.items.size - 1 }
            editor.items.forEachIndexed { index, item ->
                val loaded = batchMetadataItems[item.track.metadataKey()]
                val candidate = item.candidates.firstOrNull { it.id == item.selectedCandidateId }
                val outcome = try {
                    requireNotNull(loaded) { "prepared source is unavailable" }
                    val patch = MetadataDraftPlanner.batchPatch(
                        snapshot = loaded.snapshot,
                        candidate = candidate,
                        acceptedCandidateFields = item.acceptedCandidateFields,
                        commonFields = editor.commonFields,
                        sequenceNumber = if (editor.sequenceTracks) startAt!! + index else null,
                        sequenceTotal = if (editor.sequenceTracks && editor.includeTrackTotal) total else null,
                    )
                    Result.success(
                        if (patch.changedFields(loaded.snapshot).isEmpty()) null else container.metadata.stage(loaded, patch),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                outcome.getOrNull()?.let(results::add)
                items = items.map { current ->
                    if (current.track.metadataKey() != item.track.metadataKey()) current else current.copy(
                        status = outcome.exceptionOrNull()?.userMessage("Staging failed")
                            ?: if (outcome.getOrNull() == null) "No changes" else "Verified candidate",
                    )
                }
                val current = _state.value.metadataEditor as? MetadataEditorUiState.Batch ?: return@launch
                _state.value = _state.value.copy(
                    metadataEditor = current.copy(items = items, progressCompleted = index + 1),
                )
            }
            artifact = when (results.size) {
                0 -> null
                1 -> container.metadata.artifact(results.single())
                else -> container.metadata.bundle(results)
            }
            val current = _state.value.metadataEditor as? MetadataEditorUiState.Batch ?: return@launch
            metadataExportArtifact = artifact
            _state.value = _state.value.copy(
                metadataEditor = current.copy(
                    items = items,
                    phase = if (artifact == null) MetadataPhase.READY else MetadataPhase.STAGED,
                    artifact = artifact?.toUi(),
                    status = if (artifact == null) {
                        "No selected file produced a changed candidate."
                    } else {
                        "Verified ${results.size} candidate file(s); originals and pCloud objects are unchanged."
                    },
                ),
            )
            committed = true
            } finally {
                if (!committed) {
                    artifact?.file?.delete()
                    results.forEach { it.stagedFile.delete() }
                }
            }
        }
    }

    fun currentMetadataArtifact(): MetadataExportArtifact? = metadataExportArtifact

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
        flushPlaybackProgress()
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
        flushPlaybackProgress()
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
        val hadPCloudQueue = _state.value.queue.entries.any { it.track.sourceId.value == SourceKind.PCLOUD.id }
        if (hadPCloudQueue) {
            flushPlaybackProgress()
            playbackConnection.clearQueue()
            val clearedQueue = PlaybackQueue(generation = _state.value.queue.generation + 1)
            _state.value = _state.value.copy(queue = clearedQueue)
            viewModelScope.launch { container.preferences.saveQueue(clearedQueue) }
        }
        val session = container.sources.disconnectPCloudLocally()
        _state.value = _state.value.copy(
            sourceKind = SourceKind.DEMO,
            sourceName = "Demo library",
            pCloudConnected = false,
            message = if (hadPCloudQueue) {
                "pCloud session and active cloud queue removed. Revoking provider access…"
            } else {
                "pCloud session removed from this device. Revoking provider access…"
            },
        )
        viewModelScope.launch {
            container.preferences.updateSource(SourceKind.DEMO)
            openRoot()
            val revocation = session?.let { container.pCloudSessionRevoker.revoke(it) }
            _state.value = _state.value.copy(
                message = when (revocation) {
                    PCloudRevocationResult.Revoked -> "Disconnected. pCloud confirmed that the access token was invalidated."
                    PCloudRevocationResult.AlreadyInactive -> "Disconnected. The pCloud access token was already inactive."
                    is PCloudRevocationResult.Failed -> "Disconnected locally, but pCloud could not confirm remote token invalidation. You can revoke the app from pCloud account security settings."
                    null -> "pCloud session removed from this device. No active token was available for remote invalidation."
                },
            )
        }
    }

    fun showMessage(message: String) {
        _state.value = _state.value.copy(message = message)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun playPause() = playbackConnection.playPause()
    fun skipNext() {
        flushPlaybackProgress()
        playbackConnection.skipNext()
    }

    fun skipPrevious() {
        flushPlaybackProgress()
        playbackConnection.skipPrevious()
    }
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
        flushPlaybackProgress()
        _state.value = _state.value.copy(queue = queue)
        playbackConnection.setQueue(queue, play)
        viewModelScope.launch { container.preferences.saveQueue(queue) }
    }

    private suspend fun restoreQueue() {
        val stored = container.preferences.loadQueue()
        var omitted = 0
        val entries = stored.entries.mapNotNull { reference ->
            val source = container.sources.source(reference.sourceId)
            if (source == null) {
                omitted += 1
                return@mapNotNull null
            }
            val track = runCatching { source.load(reference.nodeId) as? AudioTrack }.getOrNull()
            if (track == null) {
                omitted += 1
                return@mapNotNull null
            }
            QueueEntry(track, reference.originFolderId)
        }
        if (entries.isEmpty()) {
            if (stored.entries.isNotEmpty()) {
                val empty = PlaybackQueue(generation = 1)
                container.preferences.saveQueue(empty)
                _state.value = _state.value.copy(
                    message = "The saved queue could not be restored and was cleared. Reconnect its source or build a new queue.",
                )
            }
            return
        }
        val queue = PlaybackQueue(
            generation = 1,
            entries = entries,
            currentIndex = stored.currentIndex.coerceIn(0, entries.lastIndex),
        )
        _state.value = _state.value.copy(
            queue = queue,
            message = if (omitted > 0) {
                "Restored ${entries.size} queue item(s); $omitted unavailable item(s) were removed."
            } else {
                _state.value.message
            },
        )
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

    fun flushPlaybackProgress(): Job? =
        persistPlaybackProgress(
            queue = _state.value.queue,
            playback = _state.value.playback,
            force = true,
            scope = container.applicationScope,
        )

    private fun checkpointProgress(queue: PlaybackQueue, playback: dev.properpcloud.app.playback.PlaybackUiState) {
        persistPlaybackProgress(queue, playback, force = false, scope = viewModelScope)
    }

    private fun persistPlaybackProgress(
        queue: PlaybackQueue,
        playback: dev.properpcloud.app.playback.PlaybackUiState,
        force: Boolean,
        scope: CoroutineScope,
    ): Job? {
        val decision = checkpointPolicy.evaluate(
            queue = queue,
            observation = PlaybackObservation(
                mediaId = playback.mediaId,
                positionMillis = playback.positionMillis,
                durationMillis = playback.durationMillis.takeIf { it > 0 },
                isPlaying = playback.isPlaying,
            ),
            cursor = checkpointCursor,
            observedAtEpochMillis = System.currentTimeMillis(),
            force = force,
        )
        checkpointCursor = decision.cursor
        return decision.progress?.let { progress ->
            scope.launch { container.preferences.saveProgress(progress) }
        }
    }

    override fun onCleared() {
        flushPlaybackProgress()
        folderJob?.cancel()
        queueJob?.cancel()
        metadataJob?.cancel()
        discardMetadataSources()
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

    private companion object {
        const val MAX_METADATA_BATCH_ITEMS = 20
    }
}

private fun AudioTrack.metadataKey(): String = "${sourceId.value}:${id.value}"

private fun MetadataExportArtifact.toUi() = MetadataArtifactUi(
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = file.length(),
    itemCount = itemCount,
    sha256 = sha256,
)

private fun Throwable.userMessage(prefix: String): String =
    "$prefix: ${message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()}"
