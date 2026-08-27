package dev.properpcloud.desktop

import com.google.gson.Gson
import dev.properpcloud.core.model.ApplyResultStatus
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.FileApplyResult
import dev.properpcloud.core.model.FolderQueueAssembler
import dev.properpcloud.core.model.FolderQueueBuilder
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.MediaIdentity
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackCheckpointCursor
import dev.properpcloud.core.model.PlaybackCheckpointPolicy
import dev.properpcloud.core.model.PlaybackObservation
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.core.model.QueueReducer
import dev.properpcloud.core.model.QueueRestoration
import dev.properpcloud.core.model.ResumePolicy
import dev.properpcloud.core.model.SignedLinkRetryGate
import dev.properpcloud.core.model.TagField
import dev.properpcloud.desktop.data.DesktopDemoAudioSource
import dev.properpcloud.desktop.data.DesktopLocalFolderAudioSource
import dev.properpcloud.desktop.data.SqliteStateRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

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
    val queue: PlaybackQueue = PlaybackQueue(),
    val playback: MpvState = MpvState(),
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
    private val repository = SqliteStateRepository(paths.data.resolve("properpcloud.db"))
    private val vault = SecretServiceVault()
    private val mpv = MpvController(paths.runtime, scope)
    private val demoSource = DesktopDemoAudioSource(paths.cache.resolve("demo-media"))
    private val sources = linkedMapOf<dev.properpcloud.core.model.SourceId, AudioSource>(demoSource.id to demoSource)
    private var source: AudioSource = demoSource
    private val mutableState = MutableStateFlow(DesktopUiState())
    val state: StateFlow<DesktopUiState> = mutableState.asStateFlow()
    private val closing = AtomicBoolean(false)
    private var mpris: MprisService? = null
    private var sleepMonitor: LogindSleepMonitor? = null
    private val sleepTransitionPolicy = SleepTransitionPolicy()
    private val checkpointPolicy = PlaybackCheckpointPolicy(minimumPositionDeltaMillis = 5_000)
    private val streamRetryGate = SignedLinkRetryGate()
    private var checkpointCursor = PlaybackCheckpointCursor()
    private var streamRefreshJob: Job? = null
    private var streamRefreshGeneration = 0L
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
                mutableState.value = mutableState.value.copy(playback = playback)
                updateMpris()
                checkpoint(playback)
                if (playback.streamFailure) refreshStreamAfterFailure(playback)
            }
        }
        scope.launch { loadFolder(source.root.id, resetBreadcrumbs = true); restoreQueue() }
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
                loadFolder(source.root.id, resetBreadcrumbs = true)
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
                    loadFolder(source.root.id, resetBreadcrumbs = true)
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
            if (mutableState.value.queue.entries.any { it.track.sourceId.value == "pcloud" }) {
                updateQueue(PlaybackQueue(generation = mutableState.value.queue.generation + 1))
            }
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

    fun removeQueue(index: Int) = scope.launch { updateQueue(QueueReducer.remove(mutableState.value.queue, index)) }
    fun moveQueue(index: Int, delta: Int) = scope.launch { updateQueue(QueueReducer.move(mutableState.value.queue, index, index + delta)) }
    fun playPause() = scope.launch { runCatching { mpv.togglePause() }.onFailure(::playbackFailure) }
    fun pause() = scope.launch { runCatching { mpv.pause(true) }.onFailure(::playbackFailure) }
    fun resume() = scope.launch { runCatching { mpv.pause(false) }.onFailure(::playbackFailure) }
    fun stop() = scope.launch { runCatching { mpv.stop() }.onFailure(::playbackFailure) }
    fun seek(offsetMillis: Long) = scope.launch { runCatching { mpv.seekRelative(offsetMillis) }.onFailure(::playbackFailure) }
    fun seekAbsolute(positionMillis: Long) = scope.launch { runCatching { mpv.seekAbsolute(positionMillis) }.onFailure(::playbackFailure) }

    fun restartPlayer() = scope.launch {
        val current = mutableState.value.queue.current?.track
        if (current == null) {
            mutableState.value = mutableState.value.copy(status = "Choose a track before restarting the player")
            return@launch
        }
        checkpoint(mutableState.value.playback, force = true)
        mutableState.value = mutableState.value.copy(status = "Restarting mpv and restoring ${current.name}…")
        playCurrent()
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
            val nodes = FolderQueueBuilder.sortNodes(source.list(folderId))
            val previous = mutableState.value.breadcrumbs
            val breadcrumbs = when {
                resetBreadcrumbs -> listOf(folder)
                truncateTo != null -> previous.takeWhile { it.id != truncateTo } + folder
                previous.lastOrNull()?.id == folder.id -> previous
                else -> previous + folder
            }
            mutableState.value = mutableState.value.copy(
                sourceName = source.root.name,
                connectedToPCloud = sources.keys.any { it.value == "pcloud" },
                currentFolder = folder,
                breadcrumbs = breadcrumbs,
                nodes = nodes,
                busy = false,
                status = "${nodes.size} items in ${folder.name}",
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(
                busy = false,
                status = "Folder load failed: ${localSourceMessage(it.message ?: "unavailable")}",
            )
        }
    }

    private suspend fun playCurrent(resetRetryBudget: Boolean = true) {
        val track = mutableState.value.queue.current?.track ?: return
        val sourceForTrack = sourceFor(track)
        val mediaId = MediaIdentity.encode(track.sourceId, track.id)
        if (resetRetryBudget) {
            streamRefreshGeneration += 1
            streamRefreshJob?.cancel()
            streamRetryGate.reset(mediaId)
        }
        val progress = repository.loadProgress(track.sourceId, track.id)?.let { ResumePolicy().normalize(it, System.currentTimeMillis()) }
        mutableState.value = mutableState.value.copy(status = "Resolving ${track.name}…")
        runCatching { mpv.load(sourceForTrack.resolveStream(track.id), progress?.positionMillis ?: 0) }
            .onSuccess { mutableState.value = mutableState.value.copy(status = "Playing ${track.name}") }
            .onFailure(::playbackFailure)
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
                    if (generation != streamRefreshGeneration || currentIdentity != mediaId) return@onSuccess
                    runCatching { mpv.load(refreshed, resumeMillis) }
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

    private suspend fun restoreQueue() {
        val stored = repository.loadQueue()
        val restoredEntries = stored.entries.map { reference ->
            val selectedSource = sources.entries.firstOrNull { it.key == reference.sourceId }?.value ?: return@map null
            runCatching { selectedSource.load(reference.nodeId) as? AudioTrack }.getOrNull()?.let { QueueEntry(it, reference.originFolderId) }
        }
        val restoration = QueueRestoration.repair(restoredEntries, stored.currentIndex)
        if (restoration.requiresRewrite) repository.saveQueue(restoration.queue)
        if (restoration.queue.entries.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(
                queue = restoration.queue,
                status = if (restoration.omittedCount > 0) {
                    "Restored ${restoration.queue.entries.size} queued tracks; ${restoration.omittedCount} unavailable item(s) were removed"
                } else {
                    "Restored ${restoration.queue.entries.size} queued tracks"
                },
            )
        } else if (stored.entries.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(status = "The saved queue was unavailable and has been cleared")
        }
        updateMpris()
    }

    private fun updateQueue(queue: PlaybackQueue) {
        checkpoint(mutableState.value.playback, force = true)
        mutableState.value = mutableState.value.copy(queue = queue)
        repository.saveQueue(queue)
        updateMpris()
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
                playbackSpeed = 1f,
                isPlaying = playback.running && !playback.paused && !playback.idle,
            ),
            cursor = checkpointCursor,
            observedAtEpochMillis = System.currentTimeMillis(),
            force = force,
        )
        checkpointCursor = decision.cursor
        decision.progress?.let(repository::saveProgress)
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
                        loadFolder(source.root.id, resetBreadcrumbs = true)
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
        scope.cancel()
    }

    private companion object {
        const val PCLOUD_SESSION_KEY = "pcloud-session"
    }
}
