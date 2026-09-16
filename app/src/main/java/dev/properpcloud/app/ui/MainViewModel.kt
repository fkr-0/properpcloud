package dev.properpcloud.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.properpcloud.app.AppContainer
import dev.properpcloud.app.data.SourceKind
import dev.properpcloud.app.data.StoredAudioTab
import dev.properpcloud.app.data.StoredAudioTabs
import dev.properpcloud.app.data.StoredQueue
import dev.properpcloud.app.metadata.BatchFieldDraft
import dev.properpcloud.app.metadata.LoadedMetadataItem
import dev.properpcloud.app.metadata.MetadataBundleItem
import dev.properpcloud.app.metadata.MetadataDraftPlanner
import dev.properpcloud.app.metadata.MetadataExportArtifact
import dev.properpcloud.app.playback.PlaybackController
import dev.properpcloud.core.model.AudioTabCollection
import dev.properpcloud.core.model.AudioTabDefinition
import dev.properpcloud.core.model.AudioTabId
import dev.properpcloud.core.model.AudioTabReducer
import dev.properpcloud.core.model.AudioTabSession
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.FolderQueueAssembler
import dev.properpcloud.core.model.FolderQueueBuilder
import dev.properpcloud.core.model.LibrarySearch
import dev.properpcloud.core.model.LibrarySearchRequest
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.NamedAudioPlaylist
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackCheckpointCursor
import dev.properpcloud.core.model.PlaybackCheckpointPolicy
import dev.properpcloud.core.model.PlaybackObservation
import dev.properpcloud.core.model.PlayerRepeatMode
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.QueueRestoration
import dev.properpcloud.core.model.QueueTimelineReconciliation
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.core.model.QueueReducer
import dev.properpcloud.core.model.ResumePolicy
import dev.properpcloud.core.model.SearchMatchType
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TrackSortKey
import dev.properpcloud.core.model.TrackSortPolicy
import dev.properpcloud.core.model.normalizeAudioRootPath
import dev.properpcloud.core.model.resolveFolderPath
import dev.properpcloud.metadata.tags.FolderPlaylistOrder
import dev.properpcloud.source.pcloud.PCloudSession
import dev.properpcloud.source.pcloud.PCloudAccountRegion
import dev.properpcloud.source.pcloud.PCloudDirectLoginResult
import dev.properpcloud.source.pcloud.PCloudDirectLoginRejectionReason
import dev.properpcloud.source.pcloud.PCloudRevocationResult
import dev.properpcloud.source.server.ServerCatalogAudioSource
import dev.properpcloud.source.server.ServerCatalogSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private var searchJob: Job? = null
    private var queuePersistenceJob: Job? = null
    private var tabPersistenceJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var queueMutationRevision: Long = 0
    private val checkpointPolicy = PlaybackCheckpointPolicy()
    private var checkpointCursor = PlaybackCheckpointCursor()
    private var lastPlaybackError: String? = null
    private var metadataJob: Job? = null
    private var pCloudLoginJob: Job? = null
    private var pCloudLoginGeneration: Long = 0
    private var serverConnectJob: Job? = null
    private var singleMetadataItem: LoadedMetadataItem? = null
    private val batchMetadataItems = linkedMapOf<String, LoadedMetadataItem>()
    private var metadataExportArtifact: MetadataExportArtifact? = null

    init {
        val startupQueueMutationRevision = queueMutationRevision
        viewModelScope.launch {
            val settings = container.preferences.settings.first()
            val selected = when (settings.sourceKind) {
                SourceKind.PCLOUD -> SourceKind.PCLOUD.takeIf { container.sources.hasPCloudSession() }
                SourceKind.SERVER -> SourceKind.SERVER.takeIf { container.sources.hasServerSession() }
                SourceKind.NONE -> SourceKind.NONE
            } ?: SourceKind.NONE
            container.sources.select(selected)
            val serverSession = container.serverCatalogVault.read()
            _state.value = _state.value.copy(
                sourceKind = selected,
                clientId = settings.clientId,
                sortKey = settings.sortKey,
                search = _state.value.search.copy(matchTypes = settings.searchMatchTypes),
                playbackHistoryEnabled = settings.playbackHistoryEnabled,
                playbackHistoryRetention = settings.playbackHistoryRetention,
                pCloudConnected = container.sources.hasPCloudSession(),
                serverConnected = container.sources.hasServerSession(),
                serverBaseUrl = serverSession?.normalizedBaseUrl.orEmpty(),
            )
            restoreQueue(startupQueueMutationRevision)
            openRoot()
        }
        viewModelScope.launch {
            playbackConnection.state.collect { playback ->
                val previous = _state.value
                if (previous.playback.mediaId != null && previous.playback.mediaId != playback.mediaId) {
                    persistPlaybackProgress(previous.queue, previous.playback, force = true, scope = container.applicationScope)
                }
                val queue = synchronizeQueueSelection(
                    previous.queue,
                    playback.mediaId,
                    playback.timelineMediaIds,
                )
                val queueChanged = queue != previous.queue
                val newError = playback.error?.takeIf { it != lastPlaybackError }
                lastPlaybackError = playback.error
                if (queueChanged) queueMutationRevision += 1
                val tabs = if (queueChanged) {
                    AudioTabReducer.updateActive(previous.audioTabs) { tab -> tab.copy(queue = queue) }
                } else {
                    previous.audioTabs
                }
                _state.value = previous.copy(
                    playback = playback,
                    queue = queue,
                    audioTabs = tabs,
                    message = newError?.let { "Playback controller reported: $it" } ?: previous.message,
                )
                if (queueChanged) {
                    container.preferences.saveQueue(queue)
                    container.preferences.saveAudioTabs(tabs)
                }
                checkpointProgress(queue, playback)
            }
        }
    }

    private fun persistAudioTabs(tabs: AudioTabCollection) {
        val previous = tabPersistenceJob
        tabPersistenceJob = container.applicationScope.launch {
            previous?.join()
            container.preferences.saveAudioTabs(tabs)
        }
    }

    private fun updateActiveTab(transform: (AudioTabSession) -> AudioTabSession) {
        val tabs = AudioTabReducer.updateActive(_state.value.audioTabs, transform)
        _state.value = _state.value.copy(audioTabs = tabs)
        persistAudioTabs(tabs)
    }

    private fun applyActivePlaybackSettings(tab: AudioTabSession) {
        playbackConnection.setPlaybackSpeed(tab.playbackSpeed)
        playbackConnection.setVolume(tab.volume)
        playbackConnection.setShuffle(tab.shuffle)
        playbackConnection.setRepeatMode(tab.repeatMode)
    }

    fun useServerSource() {
        if (!container.sources.select(SourceKind.SERVER)) {
            _state.value = _state.value.copy(message = "Connect the server library in Settings first.")
            return
        }
        _state.value = _state.value.copy(sourceKind = SourceKind.SERVER, sourceName = "Server library")
        viewModelScope.launch {
            container.preferences.updateSource(SourceKind.SERVER)
            openRoot()
        }
    }

    fun connectServer(baseUrl: String, apiToken: String) {
        serverConnectJob?.cancel()
        val session = runCatching {
            ServerCatalogSession(baseUrl.trim(), apiToken.trim().takeIf(String::isNotEmpty))
        }.getOrElse {
            _state.value = _state.value.copy(message = it.userMessage("Invalid server configuration"))
            return
        }
        _state.value = _state.value.copy(serverConnectInProgress = true, message = null)
        serverConnectJob = viewModelScope.launch {
            try {
                val candidate = ServerCatalogAudioSource(session)
                candidate.list(candidate.root.id)
                container.sources.installServer(session)
                _state.value = _state.value.copy(
                    sourceKind = SourceKind.SERVER,
                    sourceName = "Server library",
                    serverConnected = true,
                    serverConnectInProgress = false,
                    serverBaseUrl = session.normalizedBaseUrl,
                    message = "Server library connected. Catalog browsing and playback now use server-generated metadata.",
                )
                container.preferences.updateSource(SourceKind.SERVER)
                openRoot()
            } catch (_: CancellationException) {
                Unit
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    serverConnectInProgress = false,
                    message = error.userMessage("Could not connect server library"),
                )
            }
        }
    }

    fun disconnectServer() {
        serverConnectJob?.cancel()
        val hadServerQueue = _state.value.queue.entries.any { it.track.sourceId.value == SourceKind.SERVER.id }
        if (hadServerQueue) {
            flushPlaybackProgress()
            playbackConnection.clearQueue()
            commitQueue(PlaybackQueue(generation = _state.value.queue.generation + 1))
        }
        container.sources.disconnectServerLocally()
        val fallbackKind = container.sources.currentKind()
        _state.value = _state.value.copy(
            sourceKind = fallbackKind,
            sourceName = container.sources.current.value.root.name,
            serverConnected = false,
            serverConnectInProgress = false,
            serverBaseUrl = "",
            message = if (hadServerQueue) {
                "Server library disconnected and its active queue was cleared."
            } else {
                "Server library disconnected from this device."
            },
        )
        viewModelScope.launch {
            container.preferences.updateSource(fallbackKind)
            openRoot()
        }
    }

    fun toggleLibrarySearch() {
        val search = _state.value.search
        if (search.expanded) {
            searchJob?.cancel()
            _state.value = _state.value.copy(
                search = search.copy(expanded = false, query = "", results = emptyList(), searching = false),
            )
        } else {
            _state.value = _state.value.copy(search = search.copy(expanded = true))
        }
    }

    fun updateLibrarySearchQuery(query: String) {
        _state.value = _state.value.copy(search = _state.value.search.copy(query = query))
        scheduleLibrarySearch()
    }

    fun toggleSearchMatchType(type: SearchMatchType) {
        val search = _state.value.search
        val updated = if (type in search.matchTypes) search.matchTypes - type else search.matchTypes + type
        _state.value = _state.value.copy(search = search.copy(matchTypes = updated))
        viewModelScope.launch { container.preferences.updateSearchMatchTypes(updated) }
        scheduleLibrarySearch()
    }

    fun setPlaybackHistoryEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(playbackHistoryEnabled = enabled)
        viewModelScope.launch { container.preferences.updatePlaybackHistoryEnabled(enabled) }
    }

    fun setPlaybackHistoryRetention(retention: Int) {
        val normalized = dev.properpcloud.core.model.PlaybackHistoryPolicy.normalizeRetention(retention)
        _state.value = _state.value.copy(playbackHistoryRetention = normalized)
        viewModelScope.launch { container.preferences.updatePlaybackHistoryRetention(normalized) }
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

    fun updateBatchPlaylist(include: Boolean, order: FolderPlaylistOrder) {
        val editor = _state.value.metadataEditor as? MetadataEditorUiState.Batch ?: return
        _state.value = _state.value.copy(
            metadataEditor = editor.copy(
                includePlaylist = include,
                playlistOrder = order,
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
                // Online matches are evidence, not authority. Selecting a recording only
                // opens its field review; the user must opt each field in explicitly.
                acceptedCandidateFields = emptySet(),
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
                    acceptedCandidateFields = emptySet(),
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
            val results = mutableListOf<MetadataBundleItem>()
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
                outcome.getOrNull()?.let { staged ->
                    results += MetadataBundleItem(
                        originalFilename = loaded?.prepared?.originalFilename ?: item.track.name,
                        result = staged,
                        modifiedAtEpochMillis = item.track.modifiedAtEpochMillis,
                    )
                }
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
                1 -> if (editor.includePlaylist) {
                    container.metadata.bundle(results, includePlaylist = true, playlistOrder = editor.playlistOrder)
                } else {
                    container.metadata.artifact(results.single().result)
                }
                else -> container.metadata.bundle(
                    results,
                    includePlaylist = editor.includePlaylist,
                    playlistOrder = editor.playlistOrder,
                )
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
                        buildString {
                            append("Verified ${results.size} candidate file(s); originals and pCloud objects are unchanged.")
                            if (editor.includePlaylist) append(" The export includes a relative UTF-8 playlist.")
                        }
                    },
                ),
            )
            committed = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val current = _state.value.metadataEditor as? MetadataEditorUiState.Batch
                if (current != null) {
                    metadataExportArtifact = null
                    _state.value = _state.value.copy(
                        metadataEditor = current.copy(
                            phase = MetadataPhase.READY,
                            artifact = null,
                            status = error.userMessage("Batch metadata export failed"),
                        ),
                    )
                }
            } finally {
                if (!committed) {
                    artifact?.file?.delete()
                    results.forEach { it.result.stagedFile.delete() }
                }
            }
        }
    }

    fun currentMetadataArtifact(): MetadataExportArtifact? = metadataExportArtifact

    fun openRoot() {
        val source = container.sources.current.value
        if (source.id != SourceId(SourceKind.PCLOUD.id)) {
            loadFolder(source.root, replaceHistory = true)
            return
        }
        val tab = _state.value.audioTabs.active
        viewModelScope.launch {
            val target = tab.currentFolderId
                ?.let { nodeId -> runCatching { source.load(nodeId) as? AudioFolder }.getOrNull() }
                ?: runCatching { source.resolveFolderPath(tab.definition.rootPath) }.getOrElse { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        errorMessage = error.userMessage("Tab root '${tab.definition.rootPath}' is unavailable"),
                    )
                    return@launch
                }
            loadFolder(target, replaceHistory = true)
        }
    }

    fun switchAudioTab(tabId: AudioTabId) {
        val current = _state.value
        if (current.audioTabs.activeTabId == tabId) return
        flushPlaybackProgress()
        playbackConnection.pause()
        val snapshot = AudioTabReducer.updateActive(current.audioTabs) { tab ->
            tab.copy(queue = current.queue, playbackPositionMillis = current.playback.positionMillis.coerceAtLeast(0))
        }
        val tabs = AudioTabReducer.switch(snapshot, tabId, current.playback.positionMillis)
        val next = tabs.active
        queueMutationRevision += 1
        _state.value = current.copy(
            audioTabs = tabs,
            queue = next.queue,
            search = current.search.copy(query = "", results = emptyList(), searching = false),
            errorMessage = null,
        )
        persistAudioTabs(tabs)
        container.applicationScope.launch { container.preferences.saveQueue(next.queue) }
        if (next.queue.entries.isEmpty()) {
            playbackConnection.clearQueue()
        } else {
            playbackConnection.setQueue(next.queue, play = false, startPositionMillis = next.playbackPositionMillis)
        }
        applyActivePlaybackSettings(next)
        if (container.sources.select(SourceKind.PCLOUD)) {
            _state.value = _state.value.copy(sourceKind = SourceKind.PCLOUD, sourceName = "pCloud")
            viewModelScope.launch { container.preferences.updateSource(SourceKind.PCLOUD) }
            openRoot()
        } else {
            _state.value = _state.value.copy(
                currentFolder = null,
                breadcrumbs = emptyList(),
                nodes = emptyList(),
                loading = false,
                errorMessage = "Connect pCloud in Settings to browse ${next.definition.name}.",
            )
        }
    }

    fun addAudioTab(name: String, rootPath: String) {
        val normalizedName = name.trim()
        val definition = runCatching {
            AudioTabDefinition(
                id = AudioTabId("tab-${System.currentTimeMillis().toString(36)}"),
                name = normalizedName,
                rootPath = normalizeAudioRootPath(rootPath),
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.userMessage("Could not add tab"))
            return
        }
        val tabs = AudioTabReducer.add(_state.value.audioTabs, definition)
        _state.value = _state.value.copy(audioTabs = tabs)
        persistAudioTabs(tabs)
        switchAudioTab(definition.id)
    }

    fun updateAudioTab(tabId: AudioTabId, name: String, rootPath: String) {
        val current = _state.value.audioTabs
        val updated = runCatching {
            AudioTabReducer.updateRoot(
                AudioTabReducer.rename(current, tabId, name),
                tabId,
                rootPath,
            )
        }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.userMessage("Could not update tab"))
            return
        }
        _state.value = _state.value.copy(audioTabs = updated)
        persistAudioTabs(updated)
        if (updated.activeTabId == tabId) {
            val reset = AudioTabReducer.updateActive(updated) { it.copy(currentFolderId = null) }
            _state.value = _state.value.copy(audioTabs = reset)
            persistAudioTabs(reset)
            openRoot()
        }
    }

    fun removeAudioTab(tabId: AudioTabId) {
        val before = _state.value
        val wasActive = before.audioTabs.activeTabId == tabId
        val updated = runCatching { AudioTabReducer.remove(before.audioTabs, tabId) }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.userMessage("Could not remove tab"))
            return
        }
        _state.value = before.copy(audioTabs = updated, queue = updated.active.queue)
        persistAudioTabs(updated)
        if (wasActive) {
            playbackConnection.pause()
            val active = updated.active
            if (active.queue.entries.isEmpty()) playbackConnection.clearQueue()
            else playbackConnection.setQueue(active.queue, play = false, startPositionMillis = active.playbackPositionMillis)
            applyActivePlaybackSettings(active)
            openRoot()
        }
    }

    fun saveCurrentPlaylist(name: String) {
        val updated = runCatching {
            val current = AudioTabReducer.updateActive(_state.value.audioTabs) { it.copy(queue = _state.value.queue) }
            AudioTabReducer.savePlaylist(current, name)
        }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.userMessage("Could not save playlist"))
            return
        }
        _state.value = _state.value.copy(audioTabs = updated, message = "Saved playlist '${name.trim()}'.")
        persistAudioTabs(updated)
    }

    fun loadSavedPlaylist(name: String) {
        val updated = runCatching { AudioTabReducer.loadPlaylist(_state.value.audioTabs, name) }.getOrElse { error ->
            _state.value = _state.value.copy(message = error.userMessage("Could not load playlist"))
            return
        }
        val queue = updated.active.queue
        _state.value = _state.value.copy(audioTabs = updated, queue = queue, message = "Loaded playlist '$name'.")
        persistAudioTabs(updated)
        persistQueue(queue)
        if (queue.entries.isEmpty()) playbackConnection.clearQueue()
        else playbackConnection.setQueue(queue, play = false)
    }

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

    fun resumeTrack(track: AudioTrack) {
        viewModelScope.launch {
            val progress = _state.value.progressByNodeId[track.id]
                ?: container.preferences.loadProgress(track.sourceId, track.id)
            if (progress == null) {
                playTrack(track)
                return@launch
            }
            val queue = QueueReducer.apply(
                _state.value.queue,
                QueueOperation.REPLACE,
                listOf(QueueEntry(track)),
            )
            val resumeAt = ResumePolicy().resumePositionMillis(
                progress,
                System.currentTimeMillis(),
                track.durationMillis ?: progress.durationMillis,
            )
            flushPlaybackProgress()
            commitQueue(queue)
            playbackConnection.setQueue(queue, play = true, startPositionMillis = resumeAt)
        }
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
        commitQueue(queue)
        playbackConnection.select(index)
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
        commitQueue(queue)
        _state.value = _state.value.copy(message = "Queue cleared")
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

    fun signInWithPCloudPassword(
        email: String,
        password: CharArray,
        region: PCloudAccountRegion,
    ) {
        pCloudLoginJob?.cancel()
        serverConnectJob?.cancel()
        val generation = ++pCloudLoginGeneration
        _state.value = _state.value.copy(
            pCloudLoginInProgress = true,
            message = null,
        )
        pCloudLoginJob = viewModelScope.launch {
            try {
                val result = container.pCloudDirectLogin.signIn(email, password, region)
                if (generation != pCloudLoginGeneration) return@launch
                when (result) {
                    is PCloudDirectLoginResult.Connected -> installPCloudSession(
                        result.session,
                        "pCloud connected through fallback direct sign-in. The password was not stored; the temporary auth token is encrypted on this device.",
                    )
                    PCloudDirectLoginResult.InvalidInput -> {
                        _state.value = _state.value.copy(message = "Enter a valid pCloud email and password.")
                    }
                    PCloudDirectLoginResult.InvalidResponse -> {
                        _state.value = _state.value.copy(
                            message = "pCloud returned an incomplete direct-login response. Use OAuth when available or verify the selected account region.",
                        )
                    }
                    PCloudDirectLoginResult.NetworkFailure -> {
                        _state.value = _state.value.copy(
                            message = "Could not reach the selected pCloud regional API. Check the network and account region, then retry.",
                        )
                    }
                    is PCloudDirectLoginResult.ProviderRejected -> {
                        _state.value = _state.value.copy(
                            message = when (result.reason) {
                                PCloudDirectLoginRejectionReason.CREDENTIALS_OR_REGION ->
                                    "pCloud could not log in (code 2000), which does not identify whether the email or password was mistyped. pCloud also requires the data center where the account was created. Re-enter the credentials, verify Europe versus United States, and use OAuth when available for accounts requiring two-factor authentication."
                                PCloudDirectLoginRejectionReason.TOO_MANY_ATTEMPTS ->
                                    "pCloud temporarily blocked further login attempts (code 4000). Stop retrying and wait before trying again."
                                PCloudDirectLoginRejectionReason.PROVIDER_FAILURE ->
                                    "pCloud reported an internal login error (code 5000). Try again later."
                                PCloudDirectLoginRejectionReason.UNKNOWN ->
                                    "pCloud rejected direct sign-in (provider code ${result.providerCode}). Verify the account region and use OAuth when available."
                            },
                        )
                    }
                }
            } finally {
                password.fill('\u0000')
                if (generation == pCloudLoginGeneration) {
                    _state.value = _state.value.copy(pCloudLoginInProgress = false)
                }
            }
        }
    }

    fun onPCloudAuthorized(session: PCloudSession) {
        pCloudLoginGeneration += 1
        pCloudLoginJob?.cancel()
        installPCloudSession(
            session,
            "pCloud connected through OAuth. The access token is encrypted on this device.",
        )
    }

    private fun installPCloudSession(session: PCloudSession, successMessage: String) {
        runCatching { container.sources.installPCloud(session) }
            .onSuccess {
                _state.value = _state.value.copy(
                    sourceKind = SourceKind.PCLOUD,
                    sourceName = "pCloud",
                    pCloudConnected = true,
                    pCloudLoginInProgress = false,
                    message = successMessage,
                )
                viewModelScope.launch {
                    container.preferences.updateSource(SourceKind.PCLOUD)
                    openRoot()
                }
            }
            .onFailure { _state.value = _state.value.copy(message = it.userMessage("Could not save pCloud session")) }
    }

    fun disconnectPCloud() {
        pCloudLoginGeneration += 1
        pCloudLoginJob?.cancel()
        val hadPCloudQueue = _state.value.queue.entries.any { it.track.sourceId.value == SourceKind.PCLOUD.id }
        if (hadPCloudQueue) {
            flushPlaybackProgress()
            playbackConnection.clearQueue()
            val clearedQueue = PlaybackQueue(generation = _state.value.queue.generation + 1)
            commitQueue(clearedQueue)
        }
        val session = container.sources.disconnectPCloudLocally()
        val fallbackKind = container.sources.currentKind()
        _state.value = _state.value.copy(
            sourceKind = fallbackKind,
            sourceName = container.sources.current.value.root.name,
            pCloudConnected = false,
            message = if (hadPCloudQueue) {
                "pCloud session and active cloud queue removed. Revoking provider access…"
            } else {
                "pCloud session removed from this device. Revoking provider access…"
            },
        )
        viewModelScope.launch {
            container.preferences.updateSource(fallbackKind)
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

    fun setPlaybackSpeed(speed: Float) {
        val normalized = speed.coerceIn(0.5f, 3f)
        playbackConnection.setPlaybackSpeed(normalized)
        updateActiveTab { it.copy(playbackSpeed = normalized) }
    }

    fun setVolume(volume: Float) {
        val normalized = volume.coerceIn(0f, 1f)
        playbackConnection.setVolume(normalized)
        updateActiveTab { it.copy(volume = normalized) }
    }

    fun toggleShuffle() {
        val enabled = !_state.value.audioTabs.active.shuffle
        playbackConnection.setShuffle(enabled)
        updateActiveTab { it.copy(shuffle = enabled) }
    }

    fun cycleRepeatMode() {
        val mode = when (_state.value.audioTabs.active.repeatMode) {
            PlayerRepeatMode.OFF -> PlayerRepeatMode.ALL
            PlayerRepeatMode.ALL -> PlayerRepeatMode.ONE
            PlayerRepeatMode.ONE -> PlayerRepeatMode.OFF
        }
        playbackConnection.setRepeatMode(mode)
        updateActiveTab { it.copy(repeatMode = mode) }
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null) {
            _state.value = _state.value.copy(sleepTimerEndsAtEpochMillis = null, message = "Sleep timer cancelled.")
            return
        }
        if (minutes !in 1..720) {
            _state.value = _state.value.copy(message = "Sleep timer must be between 1 and 720 minutes.")
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(sleepTimerEndsAtEpochMillis = endsAt, message = "Sleep timer set for $minutes minutes.")
        sleepTimerJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            playbackConnection.pause()
            flushPlaybackProgress()
            _state.value = _state.value.copy(sleepTimerEndsAtEpochMillis = null, message = "Sleep timer paused playback.")
        }
    }

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
                val progress = container.preferences.loadProgress(nodes.filterIsInstance<AudioTrack>())
                val breadcrumbs = if (replaceHistory) buildBreadcrumb(source, folder) else {
                    val existing = _state.value.breadcrumbs
                    val currentIndex = existing.indexOfFirst { it.id == folder.id }
                    if (currentIndex >= 0) existing.take(currentIndex + 1) else existing + folder
                }
                FolderLoadResult(nodes, breadcrumbs, source, progress)
            }.onSuccess { result ->
                val current = _state.value
                val tabs = if (
                    result.source.id == SourceId(SourceKind.PCLOUD.id) &&
                    current.audioTabs.tabs.any { it.definition.id == current.audioTabs.activeTabId }
                ) {
                    AudioTabReducer.updateActive(current.audioTabs) { it.copy(currentFolderId = folder.id) }
                } else {
                    current.audioTabs
                }
                _state.value = current.copy(
                    sourceKind = SourceKind.entries.firstOrNull { it.id == result.source.id.value } ?: SourceKind.NONE,
                    sourceName = result.source.root.name,
                    currentFolder = folder,
                    breadcrumbs = result.breadcrumbs,
                    nodes = result.nodes,
                    progressByNodeId = result.progress,
                    audioTabs = tabs,
                    loading = false,
                    refreshing = false,
                )
                if (tabs !== current.audioTabs) persistAudioTabs(tabs)
                scheduleLibrarySearch()
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
        commitQueue(queue)
        playbackConnection.setQueue(queue, play)
    }

    private fun commitQueue(queue: PlaybackQueue) {
        queueMutationRevision += 1
        val current = _state.value
        val tabs = AudioTabReducer.updateActive(current.audioTabs) { it.copy(queue = queue) }
        _state.value = current.copy(queue = queue, audioTabs = tabs)
        persistQueue(queue)
        persistAudioTabs(tabs)
    }

    private fun persistQueue(queue: PlaybackQueue) {
        val previous = queuePersistenceJob
        queuePersistenceJob = container.applicationScope.launch {
            previous?.join()
            container.preferences.saveQueue(queue)
        }
    }

    private suspend fun restoreQueue(expectedMutationRevision: Long) {
        val storedTabs = container.preferences.loadAudioTabs()
        if (storedTabs != null) {
            val restoration = restoreAudioTabs(storedTabs)
            if (queueMutationRevision != expectedMutationRevision) return
            val active = restoration.collection.active
            _state.value = _state.value.copy(
                audioTabs = restoration.collection,
                queue = active.queue,
                message = when {
                    restoration.unavailableSourceCount > 0 ->
                        "Some saved tab queues are waiting for their source to reconnect; their stable references were preserved."
                    restoration.omittedCount > 0 ->
                        "Restored audio tabs with ${restoration.omittedCount} unavailable item(s) omitted."
                    else -> _state.value.message
                },
            )
            persistQueue(active.queue)
            if (restoration.unavailableSourceCount == 0 && restoration.requiresRewrite) {
                persistAudioTabs(restoration.collection)
            }
            if (active.queue.entries.isNotEmpty()) {
                playbackConnection.setQueue(active.queue, play = false, startPositionMillis = active.playbackPositionMillis)
            }
            applyActivePlaybackSettings(active)
            return
        }

        val stored = container.preferences.loadQueue()
        val restoration = restoreStoredQueue(stored)
        val queue = restoration.queue
        // Startup restoration must never overwrite a queue the user already mutated while
        // storage/source resolution was in flight.
        if (queueMutationRevision != expectedMutationRevision) return
        if (queue.entries.isEmpty()) {
            if (stored.entries.isNotEmpty()) {
                container.preferences.saveQueue(queue)
                _state.value = _state.value.copy(
                    message = "The saved queue could not be restored and was cleared. Reconnect its source or build a new queue.",
                )
            }
            val migrated = AudioTabReducer.updateActive(_state.value.audioTabs) { it.copy(queue = queue) }
            _state.value = _state.value.copy(audioTabs = migrated)
            persistAudioTabs(migrated)
            return
        }
        if (restoration.requiresRewrite) container.preferences.saveQueue(queue)
        val startPositionMillis = queue.current?.track?.let { track ->
            container.preferences.loadProgress(track.sourceId, track.id)?.let { progress ->
                ResumePolicy().resumePositionMillis(
                    progress,
                    System.currentTimeMillis(),
                    track.durationMillis ?: progress.durationMillis,
                )
            }
        } ?: 0
        val migrated = AudioTabReducer.updateActive(_state.value.audioTabs) {
            it.copy(queue = queue, playbackPositionMillis = startPositionMillis)
        }
        _state.value = _state.value.copy(
            queue = queue,
            audioTabs = migrated,
            message = if (restoration.omittedCount > 0) {
                "Restored ${queue.entries.size} queue item(s); ${restoration.omittedCount} unavailable item(s) were removed."
            } else {
                _state.value.message
            },
        )
        persistAudioTabs(migrated)
        playbackConnection.setQueue(queue, play = false, startPositionMillis = startPositionMillis)
    }

    private suspend fun restoreAudioTabs(stored: StoredAudioTabs): RestoredAudioTabs {
        var omittedCount = 0
        var unavailableSourceCount = 0
        var requiresRewrite = false
        val sessions = stored.tabs.map { tab ->
            val restored = restoreStoredQueue(tab.queue)
            omittedCount += restored.omittedCount
            unavailableSourceCount += restored.unavailableSourceCount
            requiresRewrite = requiresRewrite || restored.requiresRewrite
            AudioTabSession(
                definition = AudioTabDefinition(tab.id, tab.name, tab.rootPath, tab.icon, tab.color),
                queue = restored.queue,
                currentFolderId = tab.currentFolderId,
                playbackPositionMillis = tab.playbackPositionMillis,
                playbackSpeed = tab.playbackSpeed,
                volume = tab.volume,
                shuffle = tab.shuffle,
                repeatMode = tab.repeatMode,
            )
        }
        val playlists = stored.playlists.map { playlist ->
            val restored = restoreStoredQueue(
                StoredQueue(
                    entries = playlist.entries,
                    currentIndex = if (playlist.entries.isEmpty()) -1 else 0,
                ),
            )
            omittedCount += restored.omittedCount
            unavailableSourceCount += restored.unavailableSourceCount
            requiresRewrite = requiresRewrite || restored.requiresRewrite
            NamedAudioPlaylist(playlist.name, restored.queue.entries)
        }
        return RestoredAudioTabs(
            collection = AudioTabCollection(sessions, stored.activeTabId, playlists),
            omittedCount = omittedCount,
            unavailableSourceCount = unavailableSourceCount,
            requiresRewrite = requiresRewrite,
        )
    }

    private suspend fun restoreStoredQueue(stored: StoredQueue): StoredQueueRestoration {
        var unavailableSourceCount = 0
        val restoredEntries = stored.entries.map { reference ->
            val source = container.sources.source(reference.sourceId)
            if (source == null) {
                unavailableSourceCount += 1
                return@map null
            }
            val track = runCatching { source.load(reference.nodeId) as? AudioTrack }.getOrNull()
                ?: return@map null
            QueueEntry(track, reference.originFolderId)
        }
        val result = QueueRestoration.repair(restoredEntries, stored.currentIndex)
        return StoredQueueRestoration(
            queue = result.queue,
            omittedCount = result.omittedCount,
            unavailableSourceCount = unavailableSourceCount,
            requiresRewrite = result.requiresRewrite,
        )
    }

    private fun scheduleLibrarySearch() {
        searchJob?.cancel()
        val snapshot = _state.value
        val request = LibrarySearchRequest(snapshot.search.query, snapshot.search.matchTypes)
        if (request.query.trim().length < LibrarySearch.MIN_QUERY_LENGTH) {
            _state.value = snapshot.copy(search = snapshot.search.copy(results = emptyList(), searching = false))
            return
        }
        _state.value = snapshot.copy(search = snapshot.search.copy(searching = true))
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            val current = _state.value
            if (current.search.query != request.query || current.search.matchTypes != request.matchTypes) return@launch
            try {
                val candidates = if (current.sourceKind == SourceKind.PCLOUD) {
                    val source = container.sources.source(SourceId(SourceKind.PCLOUD.id))
                    if (source != null) {
                        val root = source.resolveFolderPath(current.audioTabs.active.definition.rootPath)
                        collectSearchNodes(source, root.id)
                    } else {
                        emptyList()
                    }
                } else {
                    current.nodes
                }
                val latest = _state.value
                if (latest.search.query != request.query || latest.search.matchTypes != request.matchTypes) return@launch
                val results = LibrarySearch.matches(candidates, request)
                _state.value = latest.copy(search = latest.search.copy(results = results, searching = false))
            } catch (_: CancellationException) {
                Unit
            } catch (error: Throwable) {
                val latest = _state.value
                if (latest.search.query == request.query) {
                    _state.value = latest.copy(
                        search = latest.search.copy(results = emptyList(), searching = false),
                        message = error.userMessage("Tab search failed"),
                    )
                }
            }
        }
    }

    private suspend fun collectSearchNodes(source: AudioSource, rootId: NodeId): List<MediaNode> {
        val pending = ArrayDeque<NodeId>().apply { add(rootId) }
        val visited = mutableSetOf<NodeId>()
        val nodes = mutableListOf<MediaNode>()
        while (pending.isNotEmpty() && visited.size < MAX_SEARCH_FOLDERS) {
            currentCoroutineContext().ensureActive()
            val folderId = pending.removeFirst()
            if (!visited.add(folderId)) continue
            val children = source.list(folderId)
            nodes += children
            children.filterIsInstance<AudioFolder>().forEach { pending.addLast(it.id) }
        }
        return nodes
    }

    private fun synchronizeQueueSelection(
        queue: PlaybackQueue,
        mediaId: String?,
        timelineMediaIds: List<String>,
    ): PlaybackQueue = QueueTimelineReconciliation.reconcile(queue, timelineMediaIds, mediaId)

    fun flushPlaybackProgress(): Job? {
        val current = _state.value
        val tabs = AudioTabReducer.updateActive(current.audioTabs) { tab ->
            tab.copy(queue = current.queue, playbackPositionMillis = current.playback.positionMillis.coerceAtLeast(0))
        }
        _state.value = current.copy(audioTabs = tabs)
        persistAudioTabs(tabs)
        return persistPlaybackProgress(
            queue = _state.value.queue,
            playback = _state.value.playback,
            force = true,
            scope = container.applicationScope,
        )
    }

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
            val current = _state.value
            val tabs = AudioTabReducer.updateActive(current.audioTabs) { tab ->
                tab.copy(playbackPositionMillis = progress.positionMillis, playbackSpeed = progress.playbackSpeed)
            }
            _state.value = current.copy(
                audioTabs = tabs,
                progressByNodeId = current.progressByNodeId + (progress.nodeId to progress),
            )
            persistAudioTabs(tabs)
            scope.launch { container.preferences.saveProgress(progress) }
        }
    }

    override fun onCleared() {
        flushPlaybackProgress()
        folderJob?.cancel()
        queueJob?.cancel()
        searchJob?.cancel()
        metadataJob?.cancel()
        pCloudLoginJob?.cancel()
        serverConnectJob?.cancel()
        sleepTimerJob?.cancel()
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
        const val SEARCH_DEBOUNCE_MILLIS = 200L
        const val MAX_SEARCH_FOLDERS = 1_500
    }
}

private data class FolderLoadResult(
    val nodes: List<MediaNode>,
    val breadcrumbs: List<AudioFolder>,
    val source: AudioSource,
    val progress: Map<NodeId, dev.properpcloud.core.model.PlaybackProgress>,
)

private data class StoredQueueRestoration(
    val queue: PlaybackQueue,
    val omittedCount: Int,
    val unavailableSourceCount: Int,
    val requiresRewrite: Boolean,
)

private data class RestoredAudioTabs(
    val collection: AudioTabCollection,
    val omittedCount: Int,
    val unavailableSourceCount: Int,
    val requiresRewrite: Boolean,
)

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
