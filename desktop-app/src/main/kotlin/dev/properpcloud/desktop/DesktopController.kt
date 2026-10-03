package dev.properpcloud.desktop

import com.google.gson.Gson
import dev.properpcloud.core.model.ApplyResultStatus
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.AudioTabCollection
import dev.properpcloud.core.model.AudioTabDefinition
import dev.properpcloud.core.model.AudioTabDefaults
import dev.properpcloud.core.model.AudioTabId
import dev.properpcloud.core.model.AudioTabReducer
import dev.properpcloud.core.model.AudioTabSession
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.FileApplyResult
import dev.properpcloud.core.model.FolderQueueAssembler
import dev.properpcloud.core.model.FolderQueueBuilder
import dev.properpcloud.core.model.LibraryFile
import dev.properpcloud.core.model.LibrarySearch
import dev.properpcloud.core.model.LibrarySearchRequest
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.NamedAudioPlaylist
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackCheckpointCursor
import dev.properpcloud.core.model.PlaybackCheckpointPolicy
import dev.properpcloud.core.model.PlaybackObservation
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.PlayerRepeatMode
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.core.model.QueueReducer
import dev.properpcloud.core.model.QueueRestoration
import dev.properpcloud.core.model.ResumePolicy
import dev.properpcloud.core.model.SearchMatchType
import dev.properpcloud.core.model.SignedLinkRetryGate
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TrackSortKey
import dev.properpcloud.core.model.TrackSortPolicy
import dev.properpcloud.core.model.normalizeAudioRootPath
import dev.properpcloud.core.model.resolveFolderPath
import dev.properpcloud.desktop.data.DesktopDemoAudioSource
import dev.properpcloud.desktop.data.DesktopLocalFolderAudioSource
import dev.properpcloud.desktop.data.SqliteStateRepository
import dev.properpcloud.desktop.data.StoredAudioTabs
import dev.properpcloud.desktop.data.StoredQueue
import dev.properpcloud.desktop.metadata.DesktopLocalFolderBinding
import dev.properpcloud.desktop.mpris.MprisActions
import dev.properpcloud.desktop.mpris.MprisService
import dev.properpcloud.desktop.mpris.MprisSnapshot
import dev.properpcloud.desktop.platform.LocalFolderSelection
import dev.properpcloud.desktop.platform.LocalFolderSelector
import dev.properpcloud.desktop.platform.LogindSleepMonitor
import dev.properpcloud.desktop.platform.NativeLocalFolderSelector
import dev.properpcloud.desktop.platform.SleepTransitionPolicy
import dev.properpcloud.desktop.platform.XdgPaths
import dev.properpcloud.desktop.playback.MpvController
import dev.properpcloud.desktop.playback.MpvState
import dev.properpcloud.desktop.security.SecretServiceVault
import dev.properpcloud.desktop.security.PCloudSessionRestorePolicy
import dev.properpcloud.source.pcloud.PCloudAccountRegion
import dev.properpcloud.source.pcloud.PCloudDirectLoginClient
import dev.properpcloud.source.pcloud.PCloudDirectLoginRejectionReason
import dev.properpcloud.source.pcloud.PCloudDirectLoginResult
import dev.properpcloud.source.pcloud.PCloudSession
import dev.properpcloud.source.pcloud.PCloudRevocationResult
import dev.properpcloud.source.pcloud.PCloudSessionRevoker
import dev.properpcloud.source.pcloud.PCloudSourceFactory
import dev.properpcloud.metadata.tags.ApproveLocalProposalsCommand
import dev.properpcloud.metadata.tags.FolderPlaylistReviewProjection
import dev.properpcloud.metadata.tags.FolderPlaylistOrder
import dev.properpcloud.metadata.tags.FolderTagReviewProjection
import dev.properpcloud.metadata.tags.FolderTreeTagPreview
import dev.properpcloud.metadata.tags.LocalFolderWorkbenchStatus
import dev.properpcloud.metadata.tags.LocalFolderWorkbenchWatchState
import dev.properpcloud.metadata.tags.ReviewedFolderPlaylist
import dev.properpcloud.metadata.tags.ReviewedFolderPlaylistBatch
import dev.properpcloud.metadata.tags.ReviewedFolderTagBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

data class DesktopLocalTagProposal(
    val nodeId: NodeId,
    val filename: String,
    val field: TagField,
    val ruleId: String,
    val currentValue: String?,
    val proposedValue: String?,
    val confidence: Double,
    val autoPreselected: Boolean,
    val warnings: List<String> = emptyList(),
)

data class DesktopLocalTagOutcome(
    val filename: String,
    val status: ApplyResultStatus,
    val message: String,
    val rollbackAvailable: Boolean,
)

data class DesktopLocalWorkbenchUiState(
    val active: Boolean = false,
    val recursiveScope: Boolean = false,
    val hostState: LocalFolderWorkbenchWatchState = LocalFolderWorkbenchWatchState.CLOSED,
    val sessionRevision: Long = 0,
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    val proposals: List<DesktopLocalTagProposal> = emptyList(),
    val reviewedTagCount: Int = 0,
    val tagReview: FolderTagReviewProjection? = null,
    val tagDryRunReady: Boolean = false,
    val playlistReview: FolderPlaylistReviewProjection? = null,
    val operationLabel: String? = null,
    val operationCompleted: Int = 0,
    val operationTotal: Int = 0,
    val tagOutcomes: List<DesktopLocalTagOutcome> = emptyList(),
    val rollbackAvailableCount: Int = 0,
    val recoveryRequired: Boolean = false,
    val message: String = "Choose a local folder to open the workbench.",
)

data class DesktopUiState(
    val sourceName: String = "Demo library",
    val connectedToPCloud: Boolean = false,
    val currentFolder: AudioFolder? = null,
    val breadcrumbs: List<AudioFolder> = emptyList(),
    val nodes: List<MediaNode> = emptyList(),
    val searchExpanded: Boolean = false,
    val searchQuery: String = "",
    val searchMatchTypes: Set<SearchMatchType> = SearchMatchType.entries.toSet(),
    val searchResults: List<MediaNode> = emptyList(),
    val searchBusy: Boolean = false,
    val sortKey: TrackSortKey = TrackSortKey.NATURAL_FILENAME,
    val playbackHistoryEnabled: Boolean = false,
    val playbackHistoryRetention: Int = dev.properpcloud.core.model.PlaybackHistoryPolicy.DEFAULT_RETENTION,
    val queue: PlaybackQueue = PlaybackQueue(),
    val audioTabs: AudioTabCollection = AudioTabDefaults.collection(),
    val progressByNodeId: Map<NodeId, PlaybackProgress> = emptyMap(),
    val playback: MpvState = MpvState(),
    val playbackLoading: Boolean = false,
    val sleepTimerEndsAtEpochMillis: Long? = null,
    val status: String = "Starting…",
    val busy: Boolean = false,
    val inspection: Map<String, String> = emptyMap(),
    val localWorkbench: DesktopLocalWorkbenchUiState = DesktopLocalWorkbenchUiState(),
    val requestAttention: Long = 0,
)

class DesktopController(
    private val paths: XdgPaths = XdgPaths.resolve().create(),
    private val sessionRevoker: PCloudSessionRevoker = PCloudSessionRevoker(),
    private val localFolderSelector: LocalFolderSelector = NativeLocalFolderSelector(),
    private val localBindingFactory: (File, Boolean) -> DesktopLocalFolderBinding = { file, recursive ->
        DesktopLocalFolderBinding.createSelected(file, recursive)
    },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()
    private val repository = SqliteStateRepository.openResilient(paths.data.resolve("properpcloud.db"))
    private val vault = SecretServiceVault()
    private val mpv = MpvController(paths.runtime, scope)
    private val demoSource = DesktopDemoAudioSource(paths.cache.resolve("demo-media"))
    private val sources = linkedMapOf<dev.properpcloud.core.model.SourceId, AudioSource>(demoSource.id to demoSource)
    private var source: AudioSource = demoSource
    private val mutableState = MutableStateFlow(
        DesktopUiState(
            searchMatchTypes = decodeDesktopSearchTypes(repository.setting(SqliteStateRepository.SEARCH_MATCH_TYPES_KEY)),
            playbackHistoryEnabled = repository.setting(SqliteStateRepository.HISTORY_ENABLED_KEY)?.toBooleanStrictOrNull() ?: false,
            playbackHistoryRetention = dev.properpcloud.core.model.PlaybackHistoryPolicy.normalizeRetention(
                repository.setting(SqliteStateRepository.HISTORY_RETENTION_KEY)?.toIntOrNull()
                    ?: dev.properpcloud.core.model.PlaybackHistoryPolicy.DEFAULT_RETENTION,
            ),
        ),
    )
    val state: StateFlow<DesktopUiState> = mutableState.asStateFlow()
    private val closing = AtomicBoolean(false)
    private var mpris: MprisService? = null
    private var sleepMonitor: LogindSleepMonitor? = null
    private val sleepTransitionPolicy = SleepTransitionPolicy()
    private val checkpointPolicy = PlaybackCheckpointPolicy()
    private val streamRetryGate = SignedLinkRetryGate()
    private var checkpointCursor = PlaybackCheckpointCursor()
    private var streamRefreshJob: Job? = null
    private var searchJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var completionHandledFor: String? = null
    private var streamRefreshGeneration = 0L
    private var playbackRequestGeneration = 0L
    private var playerRecoveryJob: Job? = null
    private var playerRecoveryGeneration = 0L
    private val playbackLoadMutex = Mutex()
    private var pCloudConnectJob: Job? = null
    private var pCloudConnectGeneration = 0L
    private var pCloudRestoreJob: Job? = null
    private var pCloudRestoreGeneration = 0L
    private var pCloudSession: PCloudSession? = null
    private var localBinding: DesktopLocalFolderBinding? = null
    private var localStatusJob: Job? = null
    private var reviewedLocalTags: ReviewedFolderTagBatch? = null
    private var localTagDryRunReady = false
    private val localTagRecoveryResults = mutableListOf<FileApplyResult>()
    private var reviewedLocalPlaylist: ReviewedFolderPlaylist? = null
    private var reviewedLocalPlaylistBatch: ReviewedFolderPlaylistBatch? = null

    init {
        val selectedSource = repository.setting("source")
        restorePCloudSession(selectedSource)
        mpris = runCatching { MprisService(mprisActions()) }.getOrNull()
        sleepMonitor = runCatching { LogindSleepMonitor(::onPrepareForSleep) }.getOrNull()
        scope.launch {
            mpv.state.collect { playback ->
                val current = mutableState.value
                val tabs = AudioTabReducer.updateActive(current.audioTabs) { tab ->
                    tab.copy(
                        queue = current.queue,
                        playbackPositionMillis = playback.positionMillis.coerceAtLeast(0),
                        playbackSpeed = playback.speed,
                        volume = playback.volume,
                    )
                }
                mutableState.value = current.copy(playback = playback, audioTabs = tabs)
                updateMpris()
                checkpoint(playback)
                if (playback.unexpectedExit) schedulePlayerCrashRecovery(playback)
                if (playback.streamFailure) refreshStreamAfterFailure(playback)
                if (playback.eofReached && playback.idle) handlePlaybackCompletion()
            }
        }
        scope.launch {
            restorePlaybackState()
            if (source.id.value == "pcloud") loadActiveTabFolder() else loadFolder(source.root.id, resetBreadcrumbs = true)
        }
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val snapshot = mutableState.value
        val request = LibrarySearchRequest(snapshot.searchQuery, snapshot.searchMatchTypes)
        if (request.query.trim().length < LibrarySearch.MIN_QUERY_LENGTH) {
            mutableState.value = snapshot.copy(searchResults = emptyList(), searchBusy = false)
            return
        }
        mutableState.value = snapshot.copy(searchBusy = true)
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            val current = mutableState.value
            if (current.searchQuery != request.query || current.searchMatchTypes != request.matchTypes) return@launch
            try {
                val candidates = if (source.id.value == "pcloud") {
                    val root = source.resolveFolderPath(current.audioTabs.active.definition.rootPath)
                    collectSearchNodes(source, root.id)
                } else {
                    current.nodes
                }
                val latest = mutableState.value
                if (latest.searchQuery != request.query || latest.searchMatchTypes != request.matchTypes) return@launch
                mutableState.value = latest.copy(
                    searchResults = LibrarySearch.matches(candidates, request),
                    searchBusy = false,
                )
            } catch (_: CancellationException) {
                Unit
            } catch (_: Throwable) {
                val latest = mutableState.value
                if (latest.searchQuery == request.query) {
                    mutableState.value = latest.copy(searchResults = emptyList(), searchBusy = false, status = "Tab search failed")
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

    fun useDemo() {
        cancelPCloudRestore()
        detachLocalBinding()
        scope.launch {
            source = demoSource
            repository.setSetting("source", "demo")
            loadFolder(source.root.id, resetBreadcrumbs = true)
            mutableState.value = mutableState.value.copy(sourceName = source.root.name, status = "Using the deterministic offline demo")
        }
    }

    fun usePCloud() {
        scope.launch {
            val pcloud = sources.values.firstOrNull { it.id.value == "pcloud" }
            if (pcloud == null) {
                mutableState.value = mutableState.value.copy(status = "Connect a pCloud account first")
            } else {
                detachLocalBinding()
                source = pcloud
                repository.setSetting("source", "pcloud")
                restorePlaybackState()
                loadActiveTabFolder()
            }
        }
    }

    fun chooseLocalFolder(recursive: Boolean = false) {
        cancelPCloudRestore()
        mutableState.value = mutableState.value.copy(busy = true, status = "Choose a local audio folder…")
        scope.launch {
            when (val selection = withContext(Dispatchers.IO) { localFolderSelector.selectDirectory() }) {
                LocalFolderSelection.Cancelled -> {
                    mutableState.value = mutableState.value.copy(busy = false, status = "Local folder selection cancelled")
                }
                is LocalFolderSelection.Unavailable -> {
                    mutableState.value = mutableState.value.copy(busy = false, status = selection.reason)
                }
                is LocalFolderSelection.Selected -> openSelectedLocalFolder(selection.directory, recursive)
            }
        }
    }

    private suspend fun openSelectedLocalFolder(directory: File, recursive: Boolean) {
        mutableState.value = mutableState.value.copy(busy = true, status = "Validating the selected local root…")
        val candidate = runCatching { localBindingFactory(directory, recursive) }.getOrElse {
            mutableState.value = mutableState.value.copy(
                busy = false,
                status = "Local folder rejected: the selected directory did not satisfy the readable/writable non-symlink atomic-replacement capability.",
            )
            return
        }
        val opened = runCatching { candidate.open() }.getOrElse {
            candidate.close()
            mutableState.value = mutableState.value.copy(
                busy = false,
                status = "Local folder observer failed; reselect the directory after checking its filesystem access.",
            )
            return
        }
        if (!opened.succeeded) {
            candidate.close()
            mutableState.value = mutableState.value.copy(busy = false, status = localUserMessage(candidate, opened.message))
            return
        }

        detachLocalBinding()
        localBinding = candidate
        sources[candidate.source.id] = candidate.source
        source = candidate.source
        // The native local root is intentionally session-scoped for now. No private path is
        // persisted, and restart falls back to the existing demo/provider selection contract.
        repository.setSetting("source", "demo")
        clearLocalReviews()
        projectLocalPreview(candidate, opened.value!!, candidate.status.value)
        syncDurableLocalRecovery(candidate)
        localStatusJob = scope.launch {
            candidate.status.collect { hostStatus -> projectLocalStatus(candidate, hostStatus) }
        }
        loadFolder(candidate.source.root.id, resetBreadcrumbs = true)
        mutableState.value = mutableState.value.copy(busy = false, status = localUserMessage(candidate, opened.message))
    }

    fun refreshLocalWorkbench() = scope.launch {
        val binding = localBinding ?: return@launch
        mutableState.value = mutableState.value.copy(busy = true, status = "Reconciling the local folder…")
        val result = binding.reconcileNow()
        result.value?.let { projectLocalPreview(binding, it, binding.status.value) }
        mutableState.value = mutableState.value.copy(busy = false, status = localUserMessage(binding, result.message))
        syncDurableLocalRecovery(binding)
    }

    fun reviewLocalTags(
        selected: Set<DesktopLocalTagProposal>,
        expectedRevision: Long,
        recursiveTagOptIn: Boolean,
    ) = scope.launch {
        val binding = localBinding ?: return@launch
        val hostStatus = binding.status.value
        if (hostStatus.state != LocalFolderWorkbenchWatchState.LIVE || hostStatus.sessionRevision != expectedRevision) {
            clearLocalReviews()
            mutableState.value = mutableState.value.copy(status = "The local review changed; use the fresh watcher-stable preview.")
            return@launch
        }
        if (selected.isEmpty()) {
            mutableState.value = mutableState.value.copy(status = "Select at least one proposed field to review.")
            return@launch
        }
        if (selected.groupBy { it.nodeId to it.field }.values.any { it.size > 1 }) {
            mutableState.value = mutableState.value.copy(
                status = "Choose only one proposal for each file and tag field before review.",
            )
            return@launch
        }
        val preview = binding.currentPreview()
        if (preview == null) {
            mutableState.value = mutableState.value.copy(status = "The local preview is stale; reconcile before reviewing tags.")
            return@launch
        }
        val approvals = mutableListOf<dev.properpcloud.metadata.tags.ReviewedFolderApproval>()
        for ((nodeId, rows) in selected.groupBy(DesktopLocalTagProposal::nodeId)) {
            val snapshot = preview.snapshots.firstOrNull { it.findByNodeId(nodeId) != null }
            if (snapshot == null) {
                mutableState.value = mutableState.value.copy(status = "A selected tag row no longer exists; reconcile and review again.")
                return@launch
            }
            val approval = binding.approveLocal(
                ApproveLocalProposalsCommand(
                    snapshot = snapshot,
                    nodeId = nodeId,
                    acceptedRuleByField = rows.associate { it.field to it.ruleId },
                ),
            )
            if (!approval.succeeded) {
                mutableState.value = mutableState.value.copy(status = localUserMessage(binding, approval.message))
                return@launch
            }
            approvals += approval.value!!
        }
        val reviewed = binding.reviewTags(approvals, recursiveTagOptIn)
        reviewedLocalTags = reviewed.value
        localTagDryRunReady = false
        val reviewedMessage = localUserMessage(binding, reviewed.message)
        mutableState.value = mutableState.value.copy(
            status = reviewedMessage,
            localWorkbench = mutableState.value.localWorkbench.copy(
                reviewedTagCount = reviewed.value?.plan?.items?.size ?: 0,
                tagReview = reviewed.value?.projection,
                tagDryRunReady = false,
                operationLabel = null,
                operationCompleted = 0,
                operationTotal = 0,
                message = reviewedMessage,
            ),
        )
    }

    fun dryRunReviewedLocalTags() = scope.launch {
        val binding = localBinding ?: return@launch
        val review = reviewedLocalTags ?: run {
            mutableState.value = mutableState.value.copy(status = "Review local tag proposals before running preflight.")
            return@launch
        }
        projectLocalOperationProgress("Tag dry run", 0, review.plan.items.size)
        mutableState.value = mutableState.value.copy(busy = true, status = "Dry-running reviewed local tag changes…")
        val result = binding.executeTags(review, dryRun = true) { progress ->
            projectLocalOperationProgress("Tag dry run", progress.completed, progress.total)
        }
        localTagDryRunReady = result.succeeded && !result.reconciliationRequired && result.value?.preflight?.all { it.ready } == true
        val resultMessage = localUserMessage(binding, result.message)
        mutableState.value = mutableState.value.copy(
            busy = false,
            status = resultMessage,
            localWorkbench = mutableState.value.localWorkbench.copy(
                tagDryRunReady = localTagDryRunReady,
                message = resultMessage,
            ),
        )
    }

    fun applyReviewedLocalTags(confirmWrite: Boolean) = scope.launch {
        val binding = localBinding ?: return@launch
        val review = reviewedLocalTags ?: return@launch
        if (!confirmWrite || !localTagDryRunReady) {
            mutableState.value = mutableState.value.copy(status = "A successful dry run and explicit replacement confirmation are required.")
            return@launch
        }
        val total = review.plan.items.size
        projectLocalOperationProgress("Tag apply", 0, total)
        mutableState.value = mutableState.value.copy(busy = true, status = "Applying reviewed local tags…")
        val result = binding.executeTags(review, dryRun = false, confirmWrite = true) { progress ->
            projectLocalOperationProgress("Tag apply", progress.completed, progress.total)
        }
        val results = result.value?.results.orEmpty()
        val completed = results.size.takeIf { it > 0 } ?: mutableState.value.localWorkbench.operationCompleted
        localTagRecoveryResults += results.filter { applyResult ->
            applyResult.status == ApplyResultStatus.INDETERMINATE ||
                (applyResult.status == ApplyResultStatus.VERIFIED && applyResult.rollbackFile?.isFile == true)
        }
        clearLocalReviews()
        val message = localUserMessage(binding, result.message)
        val outcomes = results.map { applyResult ->
            DesktopLocalTagOutcome(
                filename = applyResult.identity.filename,
                status = applyResult.status,
                message = localUserMessage(binding, applyResult.message),
                rollbackAvailable = canRollbackLocalTagResult(applyResult),
            )
        }
        mutableState.value = mutableState.value.copy(
            busy = false,
            status = message,
            localWorkbench = mutableState.value.localWorkbench.copy(
                operationLabel = "Tag apply",
                operationCompleted = completed,
                operationTotal = total,
                tagOutcomes = outcomes,
                message = message,
            ),
        )
        syncDurableLocalRecovery(binding, outcomes)
    }

    fun rollbackLatestLocalTag(confirmRollback: Boolean) = scope.launch {
        val binding = localBinding ?: return@launch
        if (!confirmRollback) return@launch
        val targetIndex = localTagRecoveryResults.indexOfLast(::canRollbackLocalTagResult)
        if (targetIndex < 0) {
            val message = if (localTagRecoveryResults.any { it.status == ApplyResultStatus.INDETERMINATE }) {
                "No guarded rollback is available for the unresolved tag outcome; preserve recovery evidence and resolve it manually before more metadata writes."
            } else {
                "No verified local tag rollback is available."
            }
            mutableState.value = mutableState.value.copy(status = message)
            return@launch
        }
        val target = localTagRecoveryResults[targetIndex]
        projectLocalOperationProgress("Tag rollback", 0, 1)
        mutableState.value = mutableState.value.copy(busy = true, status = "Verifying guarded local tag rollback…")
        val rollback = binding.rollbackTag(target)
        val outcome = rollback.value
        if (outcome?.status == ApplyResultStatus.VERIFIED) {
            localTagRecoveryResults.removeAt(targetIndex)
        }
        projectLocalOperationProgress("Tag rollback", 1, 1)
        val message = localUserMessage(binding, rollback.message)
        val presented = outcome?.let { applyResult ->
            DesktopLocalTagOutcome(
                filename = applyResult.identity.filename,
                status = applyResult.status,
                message = localUserMessage(binding, applyResult.message),
                rollbackAvailable = canRollbackLocalTagResult(applyResult),
            )
        }?.let(::listOf).orEmpty()
        mutableState.value = mutableState.value.copy(
            busy = false,
            status = message,
            localWorkbench = mutableState.value.localWorkbench.copy(
                operationLabel = "Tag rollback",
                operationCompleted = 1,
                operationTotal = 1,
                tagOutcomes = presented.ifEmpty { mutableState.value.localWorkbench.tagOutcomes },
                message = message,
            ),
        )
        syncDurableLocalRecovery(binding, presented.takeIf { it.isNotEmpty() })
    }

    fun reviewLocalPlaylist(
        recursivePlaylistOptIn: Boolean,
        onePlaylistPerAlbum: Boolean,
        order: FolderPlaylistOrder,
    ) = scope.launch {
        val binding = localBinding ?: return@launch
        val resultMessage: String
        if (binding.recursive) {
            val review = binding.reviewPlaylistBatch(recursivePlaylistOptIn, onePlaylistPerAlbum, order)
            reviewedLocalPlaylist = null
            reviewedLocalPlaylistBatch = review.value
            resultMessage = localUserMessage(binding, review.message)
            mutableState.value = mutableState.value.copy(
                localWorkbench = mutableState.value.localWorkbench.copy(
                    playlistReview = review.value?.projection,
                    operationLabel = null,
                    operationCompleted = 0,
                    operationTotal = 0,
                    message = resultMessage,
                ),
            )
        } else {
            val review = binding.reviewDirectPlaylist(order)
            reviewedLocalPlaylist = review.value
            reviewedLocalPlaylistBatch = null
            resultMessage = localUserMessage(binding, review.message)
            mutableState.value = mutableState.value.copy(
                localWorkbench = mutableState.value.localWorkbench.copy(
                    playlistReview = review.value?.projection,
                    operationLabel = null,
                    operationCompleted = 0,
                    operationTotal = 0,
                    message = resultMessage,
                ),
            )
        }
        mutableState.value = mutableState.value.copy(status = resultMessage)
    }

    fun materializeReviewedLocalPlaylist(confirmWrite: Boolean) = scope.launch {
        val binding = localBinding ?: return@launch
        if (!confirmWrite) return@launch
        val expectedTotal = when {
            reviewedLocalPlaylist != null -> 1
            reviewedLocalPlaylistBatch != null -> reviewedLocalPlaylistBatch!!.plan.playlists.size
            else -> 0
        }
        projectLocalOperationProgress("Playlist write", 0, expectedTotal)
        val result = when {
            reviewedLocalPlaylist != null -> binding.materializePlaylist(reviewedLocalPlaylist!!, true)
            reviewedLocalPlaylistBatch != null -> binding.materializePlaylistBatch(reviewedLocalPlaylistBatch!!, true) { progress ->
                projectLocalOperationProgress("Playlist write", progress.completed, progress.total)
            }
            else -> null
        }
        if (reviewedLocalPlaylist != null && result?.succeeded == true) {
            projectLocalOperationProgress("Playlist write", 1, 1)
        }
        reviewedLocalPlaylist = null
        reviewedLocalPlaylistBatch = null
        val message = result?.message?.let { localUserMessage(binding, it) }
            ?: "Review a local playlist before materializing it."
        mutableState.value = mutableState.value.copy(
            status = message,
            localWorkbench = mutableState.value.localWorkbench.copy(playlistReview = null, message = message),
        )
    }

    fun connectPCloud(email: String, password: CharArray, region: PCloudAccountRegion) {
        cancelPCloudRestore()
        pCloudConnectJob?.cancel()
        val generation = ++pCloudConnectGeneration
        mutableState.value = mutableState.value.copy(busy = true, status = "Connecting to pCloud ${region.displayName}…")
        pCloudConnectJob = scope.launch {
            when (val result = PCloudDirectLoginClient().signIn(email, password, region)) {
                is PCloudDirectLoginResult.Connected -> {
                    if (generation != pCloudConnectGeneration) return@launch
                    val serialized = gson.toJson(result.session).toCharArray()
                    val storageFailure = runCatching { vault.store(PCLOUD_SESSION_KEY, serialized) }.exceptionOrNull()
                    if (storageFailure != null) {
                        updatePCloudConnectState(generation) {
                            it.copy(busy = false, status = "Secret Service storage failed: ${storageFailure.message}")
                        }
                        return@launch
                    }
                    if (generation != pCloudConnectGeneration) {
                        runCatching { vault.clear(PCLOUD_SESSION_KEY) }
                        return@launch
                    }
                    attachPCloud(result.session)
                    detachLocalBinding()
                    source = sources.getValue(result.session.let { dev.properpcloud.core.model.SourceId("pcloud") })
                    repository.setSetting("source", "pcloud")
                    restorePlaybackState()
                    loadActiveTabFolder()
                    updatePCloudConnectState(generation) {
                        it.copy(busy = false, connectedToPCloud = true, status = "Connected to pCloud")
                    }
                }
                is PCloudDirectLoginResult.ProviderRejected -> updatePCloudConnectState(generation) {
                    it.copy(
                        busy = false,
                        status = when (result.reason) {
                            PCloudDirectLoginRejectionReason.CREDENTIALS_OR_REGION ->
                                "pCloud login failed (code 2000): re-enter the credentials and verify the account's Europe/US data center"
                            PCloudDirectLoginRejectionReason.TOO_MANY_ATTEMPTS ->
                                "pCloud blocked further login attempts (code 4000); wait before retrying"
                            PCloudDirectLoginRejectionReason.PROVIDER_FAILURE ->
                                "pCloud reported an internal login error (code 5000); try again later"
                            PCloudDirectLoginRejectionReason.UNKNOWN ->
                                "pCloud rejected sign-in (code ${result.providerCode})"
                        },
                    )
                }
                PCloudDirectLoginResult.InvalidInput -> updatePCloudConnectState(generation) {
                    it.copy(busy = false, status = "Email or password is invalid")
                }
                PCloudDirectLoginResult.InvalidResponse -> updatePCloudConnectState(generation) {
                    it.copy(busy = false, status = "pCloud returned an invalid response")
                }
                PCloudDirectLoginResult.NetworkFailure -> updatePCloudConnectState(generation) {
                    it.copy(busy = false, status = "Could not reach pCloud")
                }
            }
        }
    }

    fun disconnectPCloud() {
        cancelPCloudRestore()
        detachLocalBinding()
        pCloudConnectGeneration += 1
        streamRefreshGeneration += 1
        streamRefreshJob?.cancel()
        pCloudConnectJob?.cancel()
        pCloudConnectJob = null
        val session = pCloudSession
        pCloudSession = null
        sources.entries.removeIf { it.key.value == "pcloud" }
        source = demoSource
        val localPersistence = runCatching {
            checkpoint(mutableState.value.playback, force = true)
            repository.setSetting("source", "demo")
            repository.setSetting(PCloudSessionRestorePolicy.SETTING_KEY, PCloudSessionRestorePolicy.DISCONNECTED)
        }
        mutableState.value = mutableState.value.copy(
            connectedToPCloud = false,
            status = if (localPersistence.isSuccess) {
                "pCloud disconnected locally; clearing the credential and revoking the remote session…"
            } else {
                "pCloud disconnected for this process, but durable local state could not be updated"
            },
        )
        scope.launch {
            runCatching { mpv.stop() }
            loadFolder(source.root.id, resetBreadcrumbs = true)
            val localClear = withContext(Dispatchers.IO) { runCatching { vault.clear(PCLOUD_SESSION_KEY) } }
            val revocation = session?.let { sessionRevoker.revoke(it) }
            mutableState.value = mutableState.value.copy(
                connectedToPCloud = false,
                status = when {
                    localPersistence.isFailure && localClear.isFailure ->
                        "pCloud is disconnected for this process, but durable state and Secret Service cleanup failed; retry disconnect"
                    localPersistence.isFailure ->
                        "pCloud credential cleared, but durable local state could not be updated"
                    localClear.isFailure -> "pCloud playback disconnected, but Secret Service removal failed; retry disconnect"
                    revocation == PCloudRevocationResult.Revoked -> "pCloud disconnected locally and the remote session was revoked"
                    revocation == PCloudRevocationResult.AlreadyInactive -> "pCloud disconnected; the remote session was already inactive"
                    revocation is PCloudRevocationResult.Failed -> "pCloud disconnected locally; remote revocation could not be confirmed"
                    else -> "pCloud disconnected locally"
                },
            )
        }
    }

    fun open(node: MediaNode) = when (node) {
        is AudioFolder -> scope.launch { loadFolder(node.id) }
        is AudioTrack -> play(node)
        is LibraryFile -> inspect(node)
    }

    fun toggleSearch() {
        val current = mutableState.value
        if (current.searchExpanded) {
            searchJob?.cancel()
            mutableState.value = current.copy(
                searchExpanded = false,
                searchQuery = "",
                searchResults = emptyList(),
                searchBusy = false,
            )
        } else {
            mutableState.value = current.copy(searchExpanded = true)
        }
    }

    fun updateSearchQuery(query: String) {
        mutableState.value = mutableState.value.copy(searchQuery = query)
        scheduleSearch()
    }

    fun toggleSearchMatchType(type: SearchMatchType) {
        val current = mutableState.value
        val types = if (type in current.searchMatchTypes) current.searchMatchTypes - type else current.searchMatchTypes + type
        mutableState.value = current.copy(searchMatchTypes = types)
        repository.setSetting(
            SqliteStateRepository.SEARCH_MATCH_TYPES_KEY,
            types.sortedBy { it.ordinal }.joinToString(",") { it.name },
        )
        scheduleSearch()
    }

    fun setSort(key: TrackSortKey) {
        mutableState.value = mutableState.value.copy(sortKey = key)
        mutableState.value.currentFolder?.let { folder -> scope.launch { loadFolder(folder.id) } }
    }

    fun switchAudioTab(tabId: AudioTabId) = scope.launch {
        val current = mutableState.value
        if (current.audioTabs.activeTabId == tabId) return@launch
        checkpoint(current.playback, force = true)
        invalidatePlaybackRequests()
        if (current.playback.running) runCatching { mpv.stop() }
        val snapshot = AudioTabReducer.updateActive(current.audioTabs) { tab ->
            tab.copy(
                queue = current.queue,
                playbackPositionMillis = current.playback.positionMillis.coerceAtLeast(0),
                playbackSpeed = current.playback.speed,
                volume = current.playback.volume,
            )
        }
        val tabs = AudioTabReducer.switch(snapshot, tabId, current.playback.positionMillis)
        val next = tabs.active
        completionHandledFor = null
        mutableState.value = current.copy(
            audioTabs = tabs,
            queue = next.queue,
            searchQuery = "",
            searchResults = emptyList(),
            searchBusy = false,
            status = "Switched to ${next.definition.name}; press play to resume.",
        )
        persistTabs(tabs)
        repository.saveQueue(next.queue)
        val pcloud = sources.values.firstOrNull { it.id.value == "pcloud" }
        if (pcloud == null) {
            mutableState.value = mutableState.value.copy(
                currentFolder = null,
                breadcrumbs = emptyList(),
                nodes = emptyList(),
                status = "Connect pCloud to browse ${next.definition.name}.",
            )
            return@launch
        }
        detachLocalBinding()
        source = pcloud
        repository.setSetting("source", "pcloud")
        loadActiveTabFolder()
        if (next.queue.entries.isNotEmpty()) {
            playCurrent(play = false, resumeOverrideMillis = next.playbackPositionMillis)
        }
    }

    fun moveAudioTab(tabId: AudioTabId, delta: Int) {
        val current = mutableState.value
        val sourceIndex = current.audioTabs.tabs.indexOfFirst { it.definition.id == tabId }
        if (sourceIndex < 0) return
        val targetIndex = (sourceIndex + delta).coerceIn(0, current.audioTabs.tabs.lastIndex)
        if (targetIndex == sourceIndex) return
        val tabs = AudioTabReducer.move(current.audioTabs, tabId, targetIndex)
        mutableState.value = current.copy(audioTabs = tabs)
        persistTabs(tabs)
    }

    fun addAudioTab(name: String, rootPath: String) {
        val definition = runCatching {
            AudioTabDefinition(
                id = AudioTabId("tab-${System.currentTimeMillis().toString(36)}"),
                name = name.trim(),
                rootPath = normalizeAudioRootPath(rootPath),
            )
        }.getOrElse {
            mutableState.value = mutableState.value.copy(status = "Could not add tab: ${it.message ?: "invalid tab"}")
            return
        }
        val tabs = AudioTabReducer.add(mutableState.value.audioTabs, definition)
        mutableState.value = mutableState.value.copy(audioTabs = tabs)
        persistTabs(tabs)
        switchAudioTab(definition.id)
    }

    fun updateAudioTab(tabId: AudioTabId, name: String, rootPath: String) {
        val current = mutableState.value
        val tabs = runCatching {
            AudioTabReducer.updateRoot(AudioTabReducer.rename(current.audioTabs, tabId, name), tabId, rootPath)
        }.getOrElse {
            mutableState.value = current.copy(status = "Could not update tab: ${it.message ?: "invalid tab"}")
            return
        }
        val reset = if (tabs.activeTabId == tabId) AudioTabReducer.updateActive(tabs) { it.copy(currentFolderId = null) } else tabs
        mutableState.value = current.copy(audioTabs = reset)
        persistTabs(reset)
        if (reset.activeTabId == tabId && source.id.value == "pcloud") scope.launch { loadActiveTabFolder() }
    }

    fun removeAudioTab(tabId: AudioTabId) = scope.launch {
        val current = mutableState.value
        val wasActive = current.audioTabs.activeTabId == tabId
        val tabs = runCatching { AudioTabReducer.remove(current.audioTabs, tabId) }.getOrElse {
            mutableState.value = current.copy(status = "Could not remove tab: ${it.message ?: "invalid tab"}")
            return@launch
        }
        if (wasActive) {
            checkpoint(current.playback, force = true)
            invalidatePlaybackRequests()
            if (current.playback.running) runCatching { mpv.stop() }
        }
        val queue = if (wasActive) tabs.active.queue else current.queue
        mutableState.value = current.copy(audioTabs = tabs, queue = queue)
        persistTabs(tabs)
        if (wasActive) {
            repository.saveQueue(queue)
            if (source.id.value == "pcloud") loadActiveTabFolder()
            if (queue.entries.isNotEmpty()) playCurrent(play = false, resumeOverrideMillis = tabs.active.playbackPositionMillis)
            else mutableState.value = mutableState.value.copy(status = "Closed the active tab and stopped playback.")
        }
    }

    fun saveCurrentPlaylist(name: String) {
        val current = mutableState.value
        val tabs = runCatching {
            AudioTabReducer.savePlaylist(
                AudioTabReducer.updateActive(current.audioTabs) { it.copy(queue = current.queue) },
                name,
            )
        }.getOrElse {
            mutableState.value = current.copy(status = "Could not save playlist: ${it.message ?: "invalid playlist"}")
            return
        }
        mutableState.value = current.copy(audioTabs = tabs, status = "Saved playlist '${name.trim()}'.")
        persistTabs(tabs)
    }

    fun loadSavedPlaylist(name: String) = scope.launch {
        val current = mutableState.value
        val tabs = runCatching { AudioTabReducer.loadPlaylist(current.audioTabs, name) }.getOrElse {
            mutableState.value = current.copy(status = "Could not load playlist: ${it.message ?: "playlist unavailable"}")
            return@launch
        }
        val queue = tabs.active.queue
        checkpoint(current.playback, force = true)
        invalidatePlaybackRequests()
        if (current.playback.running) runCatching { mpv.stop() }
        mutableState.value = current.copy(audioTabs = tabs, queue = queue, status = "Loaded playlist '$name'; press play to start.")
        persistTabs(tabs)
        repository.saveQueue(queue)
        if (queue.entries.isNotEmpty()) playCurrent(play = false, resumeOverrideMillis = 0)
    }

    fun setPlaybackHistoryEnabled(enabled: Boolean) {
        repository.setSetting(SqliteStateRepository.HISTORY_ENABLED_KEY, enabled.toString())
        mutableState.value = mutableState.value.copy(playbackHistoryEnabled = enabled)
    }

    fun setPlaybackHistoryRetention(retention: Int) {
        val normalized = dev.properpcloud.core.model.PlaybackHistoryPolicy.normalizeRetention(retention)
        repository.setSetting(SqliteStateRepository.HISTORY_RETENTION_KEY, normalized.toString())
        mutableState.value = mutableState.value.copy(playbackHistoryRetention = normalized)
    }

    fun navigateTo(folder: AudioFolder) = scope.launch { loadFolder(folder.id, truncateTo = folder.id) }

    fun inspect(node: MediaNode) = scope.launch {
        runCatching { sourceFor(node).inspect(node.id).fields }
            .onSuccess { mutableState.value = mutableState.value.copy(inspection = it, status = "Inspection: ${node.name}") }
            .onFailure {
                mutableState.value = mutableState.value.copy(
                    status = "Inspection failed: ${localSourceMessage(it.message ?: "unavailable")}",
                )
            }
    }

    fun enqueue(track: AudioTrack, operation: QueueOperation = QueueOperation.APPEND) = scope.launch {
        updateQueue(QueueReducer.apply(mutableState.value.queue, operation, listOf(QueueEntry(track))))
    }

    fun enqueueFolder(folder: AudioFolder, recursive: Boolean, operation: QueueOperation) = scope.launch {
        mutableState.value = mutableState.value.copy(busy = true, status = "Scanning ${folder.name}…")
        val result = FolderQueueAssembler(sourceFor(folder)).build(folder.id, recursive)
        updateQueue(QueueReducer.apply(mutableState.value.queue, operation, result.entries))
        mutableState.value = mutableState.value.copy(
            busy = false,
            status = if (result.isPartial) "Queued ${result.entries.size} tracks with ${result.omissions.size} omissions" else "Queued ${result.entries.size} tracks",
        )
        if (operation == QueueOperation.REPLACE && result.entries.isNotEmpty()) playIndex(0)
    }

    fun play(track: AudioTrack) = scope.launch {
        val queue = QueueReducer.apply(mutableState.value.queue, QueueOperation.REPLACE, listOf(QueueEntry(track)))
        updateQueue(queue)
        playCurrent()
    }

    fun playIndex(index: Int) = scope.launch {
        updateQueue(QueueReducer.select(mutableState.value.queue, index))
        playCurrent()
    }

    fun removeQueue(index: Int) = scope.launch {
        val before = mutableState.value
        if (index !in before.queue.entries.indices) return@launch
        val removedCurrent = index == before.queue.currentIndex
        val wasPlaying = before.playback.running && !before.playback.paused && !before.playback.idle
        val queue = QueueReducer.remove(before.queue, index)
        updateQueue(queue)
        if (removedCurrent) {
            invalidatePlaybackRequests()
            if (before.playback.running) runCatching { mpv.stop() }
            if (queue.current != null) playCurrent(play = wasPlaying, resumeOverrideMillis = 0)
        }
    }
    fun moveQueue(index: Int, delta: Int) = scope.launch { updateQueue(QueueReducer.move(mutableState.value.queue, index, index + delta)) }
    fun playPause() = scope.launch {
        val playback = mutableState.value.playback
        if (playback.unexpectedExit || playback.restartAvailable && !playback.streamFailure) {
            val track = mutableState.value.queue.current?.track ?: return@launch
            val resumeMillis = playback.positionMillis.coerceAtLeast(0)
            invalidatePlaybackRequests()
            mutableState.value = mutableState.value.copy(status = "Restarting the player for ${track.name}…")
            playCurrent(resetRetryBudget = false, play = true, resumeOverrideMillis = resumeMillis)
        } else if (playback.streamFailure) {
            val track = mutableState.value.queue.current?.track
            if (track != null) streamRetryGate.reset(MediaIdentity.encode(track.sourceId, track.id))
            refreshStreamAfterFailure(playback)
        } else {
            runCatching {
                if (playback.paused) mpv.reloadAudioOutput()
                mpv.togglePause()
            }.onFailure(::playbackFailure)
        }
    }
    fun pause() = scope.launch { runCatching { mpv.pause(true) }.onFailure(::playbackFailure) }
    fun resume() = scope.launch {
        runCatching {
            mpv.reloadAudioOutput()
            mpv.pause(false)
        }.onFailure(::playbackFailure)
    }
    fun stop() = scope.launch {
        checkpoint(mutableState.value.playback, force = true)
        invalidatePlaybackRequests()
        runCatching { mpv.stop() }.onFailure(::playbackFailure)
    }
    fun seek(offsetMillis: Long) = scope.launch { runCatching { mpv.seekRelative(offsetMillis) }.onFailure(::playbackFailure) }
    fun seekAbsolute(positionMillis: Long) = scope.launch { runCatching { mpv.seekAbsolute(positionMillis) }.onFailure(::playbackFailure) }
    fun setPlaybackSpeed(speed: Float) {
        val normalized = speed.coerceIn(0.5f, 3f)
        updateActiveTab { it.copy(playbackSpeed = normalized) }
        if (mutableState.value.playback.running) {
            scope.launch { runCatching { mpv.setSpeed(normalized) }.onFailure(::playbackFailure) }
        }
    }

    fun setVolume(volume: Float) {
        val normalized = volume.coerceIn(0f, 1f)
        updateActiveTab { it.copy(volume = normalized) }
        if (mutableState.value.playback.running) {
            scope.launch { runCatching { mpv.setVolume(normalized) }.onFailure(::playbackFailure) }
        }
    }

    fun adjustVolume(delta: Float) = setVolume(mutableState.value.audioTabs.active.volume + delta)

    fun toggleShuffle() {
        val enabled = !mutableState.value.audioTabs.active.shuffle
        updateActiveTab { it.copy(shuffle = enabled) }
        mutableState.value = mutableState.value.copy(status = "Shuffle ${if (enabled) "on" else "off"} for ${mutableState.value.audioTabs.active.definition.name}.")
    }

    fun cycleRepeatMode() {
        val next = when (mutableState.value.audioTabs.active.repeatMode) {
            PlayerRepeatMode.OFF -> PlayerRepeatMode.ALL
            PlayerRepeatMode.ALL -> PlayerRepeatMode.ONE
            PlayerRepeatMode.ONE -> PlayerRepeatMode.OFF
        }
        updateActiveTab { it.copy(repeatMode = next) }
        mutableState.value = mutableState.value.copy(status = "Repeat ${next.name.lowercase()} for ${mutableState.value.audioTabs.active.definition.name}.")
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null) {
            mutableState.value = mutableState.value.copy(sleepTimerEndsAtEpochMillis = null, status = "Sleep timer cancelled.")
            return
        }
        if (minutes !in 1..720) {
            mutableState.value = mutableState.value.copy(status = "Sleep timer must be between 1 and 720 minutes.")
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        mutableState.value = mutableState.value.copy(sleepTimerEndsAtEpochMillis = endsAt, status = "Sleep timer set for $minutes minutes.")
        sleepTimerJob = scope.launch {
            delay(minutes * 60_000L)
            checkpoint(mutableState.value.playback, force = true)
            if (mutableState.value.playback.running) runCatching { mpv.pause(true) }
            mutableState.value = mutableState.value.copy(sleepTimerEndsAtEpochMillis = null, status = "Sleep timer paused playback.")
        }
    }

    fun restartPlayer() = scope.launch {
        val current = mutableState.value.queue.current?.track
        if (current == null) {
            mutableState.value = mutableState.value.copy(status = "Choose a track before restarting the player")
            return@launch
        }
        val playback = mutableState.value.playback
        checkpoint(playback, force = true)
        val resumeMillis = playback.positionMillis.coerceAtLeast(0)
        invalidatePlaybackRequests()
        mutableState.value = mutableState.value.copy(status = "Restarting mpv and restoring ${current.name}…")
        playCurrent(resetRetryBudget = false, play = true, resumeOverrideMillis = resumeMillis)
    }

    fun next() = scope.launch {
        val queue = mutableState.value.queue
        if (queue.currentIndex < queue.entries.lastIndex) { updateQueue(QueueReducer.select(queue, queue.currentIndex + 1)); playCurrent() }
    }

    fun previous() = scope.launch {
        val queue = mutableState.value.queue
        if (queue.currentIndex > 0) { updateQueue(QueueReducer.select(queue, queue.currentIndex - 1)); playCurrent() }
    }

    fun revealContainingFolder() = scope.launch {
        val current = mutableState.value.queue.current?.track ?: return@launch
        val sourceForTrack = sourceFor(current)
        source = sourceForTrack
        loadFolder(current.parentId, resetBreadcrumbs = true)
    }

    private suspend fun loadFolder(folderId: NodeId, resetBreadcrumbs: Boolean = false, truncateTo: NodeId? = null) {
        mutableState.value = mutableState.value.copy(busy = true)
        runCatching {
            val folder = source.load(folderId) as AudioFolder
            val sortKey = mutableState.value.sortKey
            val nodes = FolderQueueBuilder.sortNodes(
                source.list(folderId),
                TrackSortPolicy(listOf(sortKey, TrackSortKey.NATURAL_FILENAME)),
            )
            val progress = nodes.filterIsInstance<AudioTrack>().mapNotNull { track ->
                repository.loadProgress(track.sourceId, track.id)?.let { track.id to it }
            }.toMap()
            val previous = mutableState.value.breadcrumbs
            val breadcrumbs = when {
                resetBreadcrumbs -> listOf(folder)
                truncateTo != null -> previous.takeWhile { it.id != truncateTo } + folder
                previous.lastOrNull()?.id == folder.id -> previous
                else -> previous + folder
            }
            val current = mutableState.value
            val tabs = if (source.id.value == "pcloud") {
                AudioTabReducer.updateActive(current.audioTabs) { it.copy(currentFolderId = folder.id) }
            } else {
                current.audioTabs
            }
            mutableState.value = current.copy(
                sourceName = source.root.name,
                connectedToPCloud = sources.keys.any { it.value == "pcloud" },
                currentFolder = folder,
                breadcrumbs = breadcrumbs,
                nodes = nodes,
                progressByNodeId = progress,
                audioTabs = tabs,
                busy = false,
                status = "${nodes.size} items in ${folder.name}",
            )
            if (source.id.value == "pcloud") persistTabs(tabs)
            scheduleSearch()
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                busy = false,
                status = "Folder load failed: ${localSourceMessage(it.message ?: "unavailable")}",
            )
        }
    }

    private suspend fun playCurrent(
        resetRetryBudget: Boolean = true,
        play: Boolean = true,
        resumeOverrideMillis: Long? = null,
    ) {
        val track = mutableState.value.queue.current?.track ?: return
        val sourceForTrack = sourceFor(track)
        val mediaId = MediaIdentity.encode(track.sourceId, track.id)
        val requestGeneration = ++playbackRequestGeneration
        if (resetRetryBudget) {
            streamRefreshGeneration += 1
            streamRefreshJob?.cancel()
            streamRetryGate.reset(mediaId)
        }
        val progress = resumeOverrideMillis ?: repository.loadProgress(track.sourceId, track.id)?.let {
            ResumePolicy().resumePositionMillis(it, System.currentTimeMillis(), track.durationMillis ?: it.durationMillis)
        } ?: 0
        val tabSettings = mutableState.value.audioTabs.active
        mutableState.value = mutableState.value.copy(status = "Resolving ${track.name}…", playbackLoading = true)
        completionHandledFor = null
        runCatching { sourceForTrack.resolveStream(track.id) }
            .mapCatching { handle ->
                playbackLoadMutex.withLock {
                    if (!isCurrentPlaybackRequest(requestGeneration, mediaId)) return@withLock false
                    // Preparing paused first prevents a stale request from becoming audible if a
                    // newer tab/queue intent arrives while mpv is accepting the load commands.
                    mpv.load(handle, progress, play = false)
                    mpv.setSpeed(tabSettings.playbackSpeed)
                    mpv.setVolume(tabSettings.volume)
                    if (!isCurrentPlaybackRequest(requestGeneration, mediaId)) {
                        mpv.stop()
                        return@withLock false
                    }
                    if (play) {
                        mpv.reloadAudioOutput()
                        mpv.pause(false)
                    }
                    true
                }
            }
            .onSuccess {
                if (!it) return@onSuccess
                mutableState.value = mutableState.value.copy(
                    status = if (play) "Playing ${track.name}" else "Ready to resume ${track.name}",
                    playbackLoading = false,
                )
            }
            .onFailure { failure ->
                if (requestGeneration == playbackRequestGeneration) {
                    mutableState.value = mutableState.value.copy(playbackLoading = false)
                    playbackFailure(failure)
                }
            }
        if (requestGeneration == playbackRequestGeneration && mutableState.value.playbackLoading) {
            mutableState.value = mutableState.value.copy(playbackLoading = false)
        }
        updateMpris()
    }

    private fun refreshStreamAfterFailure(playback: MpvState) {
        val track = mutableState.value.queue.current?.track ?: return
        val failedSource = runCatching { sourceFor(track) }.getOrNull()
        if (failedSource == demoSource || failedSource is DesktopLocalFolderAudioSource) {
            mutableState.value = mutableState.value.copy(
                status = if (failedSource is DesktopLocalFolderAudioSource) {
                    "Playback failed. Check the selected local file and mpv; local file handles are not temporary provider links."
                } else {
                    "Playback failed. Restart the player after checking the local file and mpv."
                },
            )
            return
        }
        val mediaId = MediaIdentity.encode(track.sourceId, track.id)
        if (!streamRetryGate.acquire(mediaId, System.currentTimeMillis())) {
            mutableState.value = mutableState.value.copy(
                status = "Playback failed again; automatic link refresh is cooling down.",
            )
            return
        }
        val generation = ++streamRefreshGeneration
        val requestGeneration = ++playbackRequestGeneration
        streamRefreshJob?.cancel()
        checkpoint(playback, force = true)
        val resumeMillis = playback.positionMillis.coerceAtLeast(0)
        mutableState.value = mutableState.value.copy(
            status = "Refreshing the temporary stream link and resuming ${track.name}…",
        )
        streamRefreshJob = scope.launch {
            runCatching { sourceFor(track).resolveStream(track.id) }
                .onSuccess { refreshed ->
                    val currentIdentity = mutableState.value.queue.current?.track?.let { current ->
                        MediaIdentity.encode(current.sourceId, current.id)
                    }
                    if (generation != streamRefreshGeneration || currentIdentity != mediaId ||
                        requestGeneration != playbackRequestGeneration
                    ) return@onSuccess
                    runCatching {
                        playbackLoadMutex.withLock {
                            if (!isCurrentPlaybackRequest(requestGeneration, mediaId)) return@withLock
                            mpv.load(refreshed, resumeMillis, play = false)
                            val tab = mutableState.value.audioTabs.active
                            mpv.setSpeed(tab.playbackSpeed)
                            mpv.setVolume(tab.volume)
                            if (!isCurrentPlaybackRequest(requestGeneration, mediaId)) {
                                mpv.stop()
                                return@withLock
                            }
                            mpv.reloadAudioOutput()
                            mpv.pause(false)
                        }
                    }
                        .onSuccess {
                            if (generation == streamRefreshGeneration) {
                                mutableState.value = mutableState.value.copy(
                                    status = "Refreshed the temporary stream link for ${track.name}",
                                )
                            }
                        }
                        .onFailure { failure ->
                            if (generation == streamRefreshGeneration && failure !is CancellationException) {
                                playbackFailure(failure)
                            }
                        }
                }
                .onFailure { failure ->
                    if (generation == streamRefreshGeneration && failure !is CancellationException) {
                        playbackFailure(failure)
                    }
                }
        }
    }

    private fun schedulePlayerCrashRecovery(playback: MpvState) {
        if (closing.get() || playerRecoveryJob?.isActive == true) return
        val track = mutableState.value.queue.current?.track ?: return
        val mediaId = MediaIdentity.encode(track.sourceId, track.id)
        val generation = ++playerRecoveryGeneration
        checkpoint(playback, force = true)
        val resumeMillis = playback.positionMillis.coerceAtLeast(0)
        val shouldResume = playback.resumeAfterRestart
        mutableState.value = mutableState.value.copy(
            playbackLoading = true,
            status = "mpv exited unexpectedly; restarting the player…",
        )
        playerRecoveryJob = scope.launch {
            delay(PLAYER_CRASH_RESTART_DELAY_MILLIS)
            val currentIdentity = mutableState.value.queue.current?.track?.let { current ->
                MediaIdentity.encode(current.sourceId, current.id)
            }
            if (closing.get() || generation != playerRecoveryGeneration || currentIdentity != mediaId) return@launch
            playCurrent(
                resetRetryBudget = false,
                play = shouldResume,
                resumeOverrideMillis = resumeMillis,
            )
            if (generation == playerRecoveryGeneration && mpv.state.value.restartAvailable) {
                mutableState.value = mutableState.value.copy(
                    playbackLoading = false,
                    status = "Automatic player restart failed; use Restart player to retry.",
                )
            }
        }
    }

    private fun invalidatePlaybackRequests() {
        playbackRequestGeneration += 1
        streamRefreshGeneration += 1
        streamRefreshJob?.cancel()
        playerRecoveryGeneration += 1
        playerRecoveryJob?.cancel()
        mutableState.value = mutableState.value.copy(playbackLoading = false)
    }

    private fun isCurrentPlaybackRequest(generation: Long, mediaId: String): Boolean {
        if (closing.get() || generation != playbackRequestGeneration) return false
        val current = mutableState.value.queue.current?.track ?: return false
        return MediaIdentity.encode(current.sourceId, current.id) == mediaId
    }

    private suspend fun restorePlaybackState() {
        val storedTabs = repository.loadAudioTabs()
        if (storedTabs != null) {
            val restoration = restoreAudioTabs(storedTabs)
            val active = restoration.collection.active
            mutableState.value = mutableState.value.copy(
                audioTabs = restoration.collection,
                queue = active.queue,
                status = when {
                    restoration.unavailableSourceCount > 0 ->
                        "Saved tab queues are waiting for a disconnected source; their stable references are preserved."
                    restoration.omittedCount > 0 ->
                        "Restored tabs with ${restoration.omittedCount} unavailable item(s) omitted."
                    else -> "Restored ${restoration.collection.tabs.size} audio tabs."
                },
            )
            repository.saveQueue(active.queue)
            if (restoration.unavailableSourceCount == 0 && restoration.requiresRewrite) persistTabs(restoration.collection)
            if (active.queue.entries.isNotEmpty()) {
                playCurrent(play = false, resumeOverrideMillis = active.playbackPositionMillis)
            }
            updateMpris()
            return
        }

        val stored = repository.loadQueue()
        val restoration = restoreStoredQueue(stored)
        val queue = restoration.queue
        if (restoration.requiresRewrite && restoration.unavailableSourceCount == 0) repository.saveQueue(queue)
        val migrated = AudioTabReducer.updateActive(mutableState.value.audioTabs) { tab ->
            tab.copy(queue = queue)
        }
        mutableState.value = mutableState.value.copy(
            queue = queue,
            audioTabs = migrated,
            status = when {
                restoration.unavailableSourceCount > 0 ->
                    "The saved queue is waiting for its source to reconnect."
                restoration.omittedCount > 0 ->
                    "Restored ${queue.entries.size} queued tracks; ${restoration.omittedCount} unavailable item(s) were removed."
                queue.entries.isNotEmpty() -> "Restored ${queue.entries.size} queued tracks."
                else -> mutableState.value.status
            },
        )
        if (restoration.unavailableSourceCount == 0) persistTabs(migrated)
        if (queue.entries.isNotEmpty()) playCurrent(play = false)
        updateMpris()
    }

    private suspend fun restoreAudioTabs(stored: StoredAudioTabs): RestoredDesktopAudioTabs {
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
                StoredQueue(playlist.entries, if (playlist.entries.isEmpty()) -1 else 0),
            )
            omittedCount += restored.omittedCount
            unavailableSourceCount += restored.unavailableSourceCount
            requiresRewrite = requiresRewrite || restored.requiresRewrite
            NamedAudioPlaylist(playlist.name, restored.queue.entries)
        }
        return RestoredDesktopAudioTabs(
            collection = AudioTabCollection(sessions, stored.activeTabId, playlists),
            omittedCount = omittedCount,
            unavailableSourceCount = unavailableSourceCount,
            requiresRewrite = requiresRewrite,
        )
    }

    private suspend fun restoreStoredQueue(stored: StoredQueue): DesktopStoredQueueRestoration {
        var unavailableSourceCount = 0
        val restoredEntries = stored.entries.map { reference ->
            val selectedSource = sources[reference.sourceId]
            if (selectedSource == null) {
                unavailableSourceCount += 1
                return@map null
            }
            runCatching { selectedSource.load(reference.nodeId) as? AudioTrack }.getOrNull()
                ?.let { QueueEntry(it, reference.originFolderId) }
        }
        val restoration = QueueRestoration.repair(restoredEntries, stored.currentIndex)
        return DesktopStoredQueueRestoration(
            queue = restoration.queue,
            omittedCount = restoration.omittedCount,
            unavailableSourceCount = unavailableSourceCount,
            requiresRewrite = restoration.requiresRewrite,
        )
    }

    private fun updateQueue(queue: PlaybackQueue) {
        checkpoint(mutableState.value.playback, force = true)
        val before = mutableState.value
        val previousIdentity = before.queue.current?.track?.let { MediaIdentity.encode(it.sourceId, it.id) }
        val nextIdentity = queue.current?.track?.let { MediaIdentity.encode(it.sourceId, it.id) }
        if (previousIdentity != nextIdentity) invalidatePlaybackRequests()
        val current = mutableState.value
        val tabs = AudioTabReducer.updateActive(current.audioTabs) { it.copy(queue = queue) }
        mutableState.value = current.copy(queue = queue, audioTabs = tabs)
        repository.saveQueue(queue)
        persistTabs(tabs)
        updateMpris()
    }

    private fun persistTabs(tabs: AudioTabCollection = mutableState.value.audioTabs) {
        repository.saveAudioTabs(tabs)
    }

    private fun updateActiveTab(transform: (AudioTabSession) -> AudioTabSession) {
        val tabs = AudioTabReducer.updateActive(mutableState.value.audioTabs, transform)
        mutableState.value = mutableState.value.copy(audioTabs = tabs)
        persistTabs(tabs)
    }

    private suspend fun loadActiveTabFolder() {
        if (source.id.value != "pcloud") return
        val tab = mutableState.value.audioTabs.active
        val root = runCatching { source.resolveFolderPath(tab.definition.rootPath) }.getOrElse {
            mutableState.value = mutableState.value.copy(
                busy = false,
                currentFolder = null,
                breadcrumbs = emptyList(),
                nodes = emptyList(),
                status = "Tab root '${tab.definition.rootPath}' is unavailable.",
            )
            return
        }
        val requested = tab.currentFolderId?.takeIf { folderWithinRoot(source, it, root.id) }
        loadFolder(requested ?: root.id, resetBreadcrumbs = true)
    }

    private suspend fun folderWithinRoot(source: AudioSource, candidateId: NodeId, rootId: NodeId): Boolean {
        var currentId: NodeId? = candidateId
        repeat(MAX_FOLDER_ANCESTORS) {
            val id = currentId ?: return false
            if (id == rootId) return true
            val folder = runCatching { source.load(id) as? AudioFolder }.getOrNull() ?: return false
            currentId = folder.parentId
        }
        return false
    }

    private fun handlePlaybackCompletion() {
        val state = mutableState.value
        val track = state.queue.current?.track ?: return
        val mediaId = MediaIdentity.encode(track.sourceId, track.id)
        if (completionHandledFor == mediaId) return
        completionHandledFor = mediaId
        scope.launch {
            checkpoint(mutableState.value.playback, force = true)
            val current = mutableState.value
            val queue = current.queue
            val tab = current.audioTabs.active
            val nextIndex = when {
                tab.repeatMode == PlayerRepeatMode.ONE -> queue.currentIndex
                tab.shuffle && queue.entries.size > 1 -> {
                    val alternatives = queue.entries.indices.filter { it != queue.currentIndex }
                    alternatives[Random.nextInt(alternatives.size)]
                }
                queue.currentIndex < queue.entries.lastIndex -> queue.currentIndex + 1
                tab.repeatMode == PlayerRepeatMode.ALL && queue.entries.isNotEmpty() -> 0
                else -> -1
            }
            if (nextIndex < 0) {
                mutableState.value = current.copy(status = "Queue finished.")
                return@launch
            }
            updateQueue(QueueReducer.select(queue, nextIndex))
            playCurrent(resumeOverrideMillis = 0)
        }
    }

    private fun onPrepareForSleep(preparingForSleep: Boolean) {
        val decision = sleepTransitionPolicy.transition(preparingForSleep, mutableState.value.playback)
        if (preparingForSleep) {
            if (decision.forceCheckpoint) checkpoint(mutableState.value.playback, force = true)
            val pauseFailure = if (decision.pausePlayback) {
                runCatching { runBlocking { mpv.pause(true) } }.exceptionOrNull()
            } else {
                null
            }
            if (pauseFailure != null) {
                playbackFailure(pauseFailure)
                return
            }
            mutableState.value = mutableState.value.copy(status = decision.status)
            return
        }

        scope.launch {
            mutableState.value = mutableState.value.copy(status = decision.status)
            if (decision.refreshAndResume) {
                if (mutableState.value.playback.restartAvailable || mutableState.value.playback.unexpectedExit) {
                    mutableState.value = mutableState.value.copy(
                        status = "The player stopped during sleep; restart it manually to resume",
                    )
                } else {
                    playCurrent()
                }
            }
        }
    }

    private fun checkpoint(playback: MpvState, force: Boolean = false) {
        val track = mutableState.value.queue.current?.track ?: return
        val decision = checkpointPolicy.evaluate(
            queue = mutableState.value.queue,
            observation = PlaybackObservation(
                mediaId = MediaIdentity.encode(track.sourceId, track.id),
                positionMillis = playback.positionMillis,
                durationMillis = playback.durationMillis ?: track.durationMillis,
                playbackSpeed = playback.speed,
                isPlaying = playback.running && !playback.paused && !playback.idle,
            ),
            cursor = checkpointCursor,
            observedAtEpochMillis = System.currentTimeMillis(),
            force = force,
        )
        checkpointCursor = decision.cursor
        decision.progress?.let { progress ->
            repository.saveProgress(progress)
            val current = mutableState.value
            val tabs = AudioTabReducer.updateActive(current.audioTabs) { tab ->
                tab.copy(
                    queue = current.queue,
                    playbackPositionMillis = progress.positionMillis,
                    playbackSpeed = progress.playbackSpeed,
                    volume = playback.volume,
                )
            }
            mutableState.value = current.copy(
                audioTabs = tabs,
                progressByNodeId = current.progressByNodeId + (progress.nodeId to progress),
            )
            persistTabs(tabs)
        }
    }

    private fun canRollbackLocalTagResult(result: FileApplyResult): Boolean =
        (result.status == ApplyResultStatus.VERIFIED || result.status == ApplyResultStatus.INDETERMINATE) &&
            result.resultSha256 != null && result.rollbackFile?.isFile == true

    /**
     * Re-associate only recovery records discovered under the root the user just selected.
     * Verified same-session rollback options may coexist, but indeterminate in-memory state is
     * replaced by the durable hash-validated discovery result after every rescan/recovery action.
     */
    private fun syncDurableLocalRecovery(
        binding: DesktopLocalFolderBinding,
        preferredOutcomes: List<DesktopLocalTagOutcome>? = null,
    ) {
        if (localBinding !== binding) return
        val durable = binding.recoveryState
        val retainedVerified = localTagRecoveryResults.filter { result ->
            result.status == ApplyResultStatus.VERIFIED && canRollbackLocalTagResult(result)
        }
        localTagRecoveryResults.clear()
        localTagRecoveryResults += retainedVerified
        durable.recoverableResults.forEach { recovered ->
            if (localTagRecoveryResults.none { existing ->
                    existing.identity.nodeId == recovered.identity.nodeId && existing.resultSha256 == recovered.resultSha256
                }
            ) {
                localTagRecoveryResults += recovered
            }
        }

        val durableOutcomes = durable.recoverableResults.map { recovered ->
            DesktopLocalTagOutcome(
                filename = recovered.identity.filename,
                status = recovered.status,
                message = localUserMessage(binding, recovered.message),
                rollbackAvailable = canRollbackLocalTagResult(recovered),
            )
        } + durable.issues.map { issue ->
            DesktopLocalTagOutcome(
                filename = issue.filename ?: "Recovery record",
                status = ApplyResultStatus.INDETERMINATE,
                message = issue.message,
                rollbackAvailable = false,
            )
        }

        val current = mutableState.value
        val outcomes = when {
            preferredOutcomes != null -> preferredOutcomes
            durable.recoveryRequired -> durableOutcomes
            current.localWorkbench.recoveryRequired -> emptyList()
            else -> current.localWorkbench.tagOutcomes
        }
        val recoveryMessage = if (durable.recoveryRequired) {
            "Interrupted local tag recovery was rediscovered under the explicitly selected root; resolve it before additional metadata writes."
        } else {
            current.localWorkbench.message
        }
        mutableState.value = current.copy(
            localWorkbench = current.localWorkbench.copy(
                tagOutcomes = outcomes,
                rollbackAvailableCount = localTagRecoveryResults.count(::canRollbackLocalTagResult),
                recoveryRequired = durable.recoveryRequired,
                message = recoveryMessage,
            ),
        )
    }

    private fun clearLocalReviews() {
        reviewedLocalTags = null
        localTagDryRunReady = false
        reviewedLocalPlaylist = null
        reviewedLocalPlaylistBatch = null
        mutableState.value = mutableState.value.copy(
            localWorkbench = mutableState.value.localWorkbench.copy(
                reviewedTagCount = 0,
                tagReview = null,
                tagDryRunReady = false,
                playlistReview = null,
                operationLabel = null,
                operationCompleted = 0,
                operationTotal = 0,
            ),
        )
    }

    private fun projectLocalOperationProgress(label: String, completed: Int, total: Int) {
        val current = mutableState.value
        mutableState.value = current.copy(
            localWorkbench = current.localWorkbench.copy(
                operationLabel = label,
                operationCompleted = completed,
                operationTotal = total,
            ),
        )
    }

    private fun detachLocalBinding() {
        val binding = localBinding ?: return
        localStatusJob?.cancel()
        localStatusJob = null
        localBinding = null
        clearLocalReviews()
        localTagRecoveryResults.clear()
        sources.remove(binding.source.id)
        if (source.id == binding.source.id) source = demoSource
        if (mutableState.value.queue.entries.any { it.track.sourceId == binding.source.id }) {
            updateQueue(PlaybackQueue(generation = mutableState.value.queue.generation + 1))
            scope.launch { runCatching { mpv.stop() } }
        }
        runCatching { binding.close() }
        mutableState.value = mutableState.value.copy(localWorkbench = DesktopLocalWorkbenchUiState())
    }

    private fun projectLocalPreview(
        binding: DesktopLocalFolderBinding,
        preview: FolderTreeTagPreview,
        hostStatus: LocalFolderWorkbenchStatus,
    ) {
        if (localBinding !== binding) return
        val proposals = preview.snapshots.flatMap { snapshot ->
            snapshot.files.flatMap { row ->
                row.fieldProposals.map { proposal ->
                    DesktopLocalTagProposal(
                        nodeId = row.identity.nodeId,
                        filename = row.identity.filename,
                        field = proposal.field,
                        ruleId = proposal.ruleId,
                        currentValue = proposal.currentValue,
                        proposedValue = proposal.proposedValue,
                        confidence = proposal.confidence,
                        autoPreselected = proposal.autoPreselected,
                        warnings = proposal.warnings,
                    )
                }
            }
        }
        mutableState.value = mutableState.value.copy(
            localWorkbench = mutableState.value.localWorkbench.copy(
                active = true,
                recursiveScope = binding.recursive,
                hostState = hostStatus.state,
                sessionRevision = hostStatus.sessionRevision,
                folderCount = hostStatus.folderCount,
                fileCount = hostStatus.fileCount,
                proposals = proposals,
                message = localUserMessage(binding, hostStatus.error ?: hostStatus.message),
            ),
        )
    }

    private suspend fun projectLocalStatus(
        binding: DesktopLocalFolderBinding,
        hostStatus: LocalFolderWorkbenchStatus,
    ) {
        if (localBinding !== binding) return
        val previous = mutableState.value.localWorkbench
        if (previous.active && previous.sessionRevision != hostStatus.sessionRevision) {
            clearLocalReviews()
        }
        val preview = if (hostStatus.state == LocalFolderWorkbenchWatchState.LIVE) binding.currentPreview() else null
        if (preview != null) {
            projectLocalPreview(binding, preview, hostStatus)
        } else {
            mutableState.value = mutableState.value.copy(
                localWorkbench = mutableState.value.localWorkbench.copy(
                    active = true,
                    recursiveScope = binding.recursive,
                    hostState = hostStatus.state,
                    sessionRevision = hostStatus.sessionRevision,
                    folderCount = hostStatus.folderCount,
                    fileCount = hostStatus.fileCount,
                    message = localUserMessage(binding, hostStatus.error ?: hostStatus.message),
                ),
            )
        }
    }

    private fun localSourceMessage(message: String): String {
        val binding = localBinding ?: return message
        return if (source.id == binding.source.id) localUserMessage(binding, message) else message
    }

    private fun localUserMessage(binding: DesktopLocalFolderBinding, message: String): String {
        val canonicalRoot = binding.source.identity.canonicalRoot.path
        val absoluteRoot = binding.source.identity.canonicalRoot.absolutePath
        return message
            .replace(canonicalRoot, "<selected-root>")
            .replace(absoluteRoot, "<selected-root>")
    }

    private fun restorePCloudSession(selectedSource: String?) {
        val generation = ++pCloudRestoreGeneration
        if (!PCloudSessionRestorePolicy.permitsRestore(repository.setting(PCloudSessionRestorePolicy.SETTING_KEY))) {
            pCloudRestoreJob = scope.launch(Dispatchers.IO) {
                if (vault.available()) runCatching { vault.clear(PCLOUD_SESSION_KEY) }
            }
            return
        }
        pCloudRestoreJob = scope.launch(Dispatchers.IO) {
            if (!vault.available()) {
                if (generation == pCloudRestoreGeneration && !closing.get()) {
                    mutableState.value = mutableState.value.copy(
                        status = "The stored pCloud session is unavailable because Secret Service is not running",
                    )
                }
                return@launch
            }
            val lookup = runCatching { vault.lookup(PCLOUD_SESSION_KEY) }
            if (generation != pCloudRestoreGeneration || closing.get()) return@launch
            if (lookup.isFailure) {
                mutableState.value = mutableState.value.copy(
                    status = "The stored pCloud session is locked or unavailable; unlock the keyring or reconnect",
                )
                return@launch
            }
            val secret = lookup.getOrNull() ?: return@launch
            try {
                val session = gson.fromJson(secret.concatToString(), PCloudSession::class.java)
                withContext(Dispatchers.Default) {
                    if (generation != pCloudRestoreGeneration || closing.get()) return@withContext
                    attachPCloud(session)
                    mutableState.value = mutableState.value.copy(connectedToPCloud = true)
                    if (selectedSource == "pcloud") {
                        source = sources.getValue(dev.properpcloud.core.model.SourceId("pcloud"))
                        restorePlaybackState()
                        loadActiveTabFolder()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                if (generation == pCloudRestoreGeneration && !closing.get()) {
                    repository.setSetting(PCloudSessionRestorePolicy.SETTING_KEY, PCloudSessionRestorePolicy.DISCONNECTED)
                    runCatching { vault.clear(PCLOUD_SESSION_KEY) }
                    mutableState.value = mutableState.value.copy(
                        status = "The stored pCloud session was invalid and has been cleared",
                    )
                }
            } finally {
                secret.fill('\u0000')
            }
        }
    }

    private fun cancelPCloudRestore() {
        pCloudRestoreGeneration += 1
        pCloudRestoreJob?.cancel()
        pCloudRestoreJob = null
    }

    private fun attachPCloud(session: PCloudSession) {
        val pcloud = PCloudSourceFactory.create(session)
        sources[pcloud.id] = pcloud
        pCloudSession = session
        repository.setSetting(PCloudSessionRestorePolicy.SETTING_KEY, PCloudSessionRestorePolicy.ACTIVE)
    }

    private inline fun updatePCloudConnectState(
        generation: Long,
        transform: (DesktopUiState) -> DesktopUiState,
    ) {
        if (generation == pCloudConnectGeneration) {
            mutableState.value = transform(mutableState.value)
        }
    }

    private fun sourceFor(node: MediaNode): AudioSource = sources[node.sourceId] ?: error("source ${node.sourceId.value} is unavailable")

    private fun playbackFailure(@Suppress("UNUSED_PARAMETER") error: Throwable) {
        checkpoint(mutableState.value.playback, force = true)
        mutableState.value = mutableState.value.copy(
            status = "Playback failed. Check mpv availability and retry.",
        )
    }

    private fun updateMpris() {
        val state = mutableState.value
        mpris?.update(MprisSnapshot(
            track = state.queue.current?.track,
            playback = state.playback,
            canNext = state.queue.currentIndex in 0 until state.queue.entries.lastIndex,
            canPrevious = state.queue.currentIndex > 0,
        ))
    }

    private fun mprisActions() = object : MprisActions {
        override fun playPause() { this@DesktopController.playPause() }
        override fun play() { resume() }
        override fun pause() { this@DesktopController.pause() }
        override fun stop() { this@DesktopController.stop() }
        override fun next() { this@DesktopController.next() }
        override fun previous() { this@DesktopController.previous() }
        override fun seek(offsetMillis: Long) { this@DesktopController.seek(offsetMillis) }
        override fun seekAbsolute(positionMillis: Long) { this@DesktopController.seekAbsolute(positionMillis) }
        override fun raise() { mutableState.value = mutableState.value.copy(requestAttention = System.nanoTime()) }
        override fun quit() = close()
    }

    fun openDocumentation() {
        runCatching { Desktop.getDesktop().browse(URI("https://properpcloud.fkr.dev")) }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        cancelPCloudRestore()
        runCatching { checkpoint(mutableState.value.playback, force = true) }
        runCatching { detachLocalBinding() }
        runCatching { sleepMonitor?.close() }
        runCatching { mpris?.close() }
        runCatching { mpv.close() }
        runCatching { repository.close() }
        streamRefreshGeneration += 1
        streamRefreshJob?.cancel()
        playerRecoveryGeneration += 1
        playerRecoveryJob?.cancel()
        searchJob?.cancel()
        sleepTimerJob?.cancel()
        scope.cancel()
    }

    private companion object {
        const val PCLOUD_SESSION_KEY = "pcloud-session"
        const val SEARCH_DEBOUNCE_MILLIS = 200L
        const val MAX_SEARCH_FOLDERS = 1_500
        const val MAX_FOLDER_ANCESTORS = 128
        const val PLAYER_CRASH_RESTART_DELAY_MILLIS = 250L
    }
}

private data class DesktopStoredQueueRestoration(
    val queue: PlaybackQueue,
    val omittedCount: Int,
    val unavailableSourceCount: Int,
    val requiresRewrite: Boolean,
)

private data class RestoredDesktopAudioTabs(
    val collection: AudioTabCollection,
    val omittedCount: Int,
    val unavailableSourceCount: Int,
    val requiresRewrite: Boolean,
)

private fun decodeDesktopSearchTypes(encoded: String?): Set<SearchMatchType> =
    encoded?.split(',')
        ?.filter(String::isNotBlank)
        ?.mapNotNull { name -> SearchMatchType.entries.firstOrNull { it.name == name } }
        ?.toSet()
        ?: SearchMatchType.entries.toSet()
