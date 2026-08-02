package dev.properpcloud.desktop

import com.google.gson.Gson
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioSource
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.FolderQueueAssembler
import dev.properpcloud.core.model.FolderQueueBuilder
import dev.properpcloud.core.model.MediaNode
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.core.model.QueueReducer
import dev.properpcloud.core.model.ResumePolicy
import dev.properpcloud.desktop.data.DesktopDemoAudioSource
import dev.properpcloud.desktop.data.SqliteStateRepository
import dev.properpcloud.desktop.mpris.MprisActions
import dev.properpcloud.desktop.mpris.MprisService
import dev.properpcloud.desktop.mpris.MprisSnapshot
import dev.properpcloud.desktop.platform.XdgPaths
import dev.properpcloud.desktop.playback.MpvController
import dev.properpcloud.desktop.playback.MpvState
import dev.properpcloud.desktop.security.SecretServiceVault
import dev.properpcloud.source.pcloud.PCloudAccountRegion
import dev.properpcloud.source.pcloud.PCloudDirectLoginClient
import dev.properpcloud.source.pcloud.PCloudDirectLoginResult
import dev.properpcloud.source.pcloud.PCloudSession
import dev.properpcloud.source.pcloud.PCloudSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

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
    val requestAttention: Long = 0,
)

class DesktopController(
    private val paths: XdgPaths = XdgPaths.resolve().create(),
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
    private var lastCheckpointSecond = -1L

    init {
        restorePCloudSession()
        val selected = repository.setting("source")
        if (selected == "pcloud") sources.values.firstOrNull { it.id.value == "pcloud" }?.let { source = it }
        mpris = runCatching { MprisService(mprisActions()) }.getOrNull()
        scope.launch {
            mpv.state.collect { playback ->
                mutableState.value = mutableState.value.copy(playback = playback)
                updateMpris()
                checkpoint(playback)
            }
        }
        scope.launch { loadFolder(source.root.id, resetBreadcrumbs = true); restoreQueue() }
    }

    fun useDemo() = scope.launch {
        source = demoSource
        repository.setSetting("source", "demo")
        loadFolder(source.root.id, resetBreadcrumbs = true)
        mutableState.value = mutableState.value.copy(sourceName = source.root.name, status = "Using the deterministic offline demo")
    }

    fun usePCloud() = scope.launch {
        val pcloud = sources.values.firstOrNull { it.id.value == "pcloud" }
        if (pcloud == null) {
            mutableState.value = mutableState.value.copy(status = "Connect a pCloud account first")
        } else {
            source = pcloud
            repository.setSetting("source", "pcloud")
            loadFolder(source.root.id, resetBreadcrumbs = true)
        }
    }

    fun connectPCloud(email: String, password: CharArray, region: PCloudAccountRegion) = scope.launch {
        mutableState.value = mutableState.value.copy(busy = true, status = "Connecting to pCloud ${region.displayName}…")
        when (val result = PCloudDirectLoginClient().signIn(email, password, region)) {
            is PCloudDirectLoginResult.Connected -> {
                val serialized = gson.toJson(result.session).toCharArray()
                runCatching { vault.store(PCLOUD_SESSION_KEY, serialized) }
                    .onFailure {
                        mutableState.value = mutableState.value.copy(busy = false, status = "Secret Service storage failed: ${it.message}")
                        return@launch
                    }
                attachPCloud(result.session)
                source = sources.getValue(result.session.let { dev.properpcloud.core.model.SourceId("pcloud") })
                repository.setSetting("source", "pcloud")
                loadFolder(source.root.id, resetBreadcrumbs = true)
                mutableState.value = mutableState.value.copy(busy = false, connectedToPCloud = true, status = "Connected to pCloud")
            }
            is PCloudDirectLoginResult.ProviderRejected -> mutableState.value = mutableState.value.copy(busy = false, status = "pCloud rejected sign-in (code ${result.providerCode ?: "unknown"})")
            PCloudDirectLoginResult.InvalidInput -> mutableState.value = mutableState.value.copy(busy = false, status = "Email or password is invalid")
            PCloudDirectLoginResult.InvalidResponse -> mutableState.value = mutableState.value.copy(busy = false, status = "pCloud returned an invalid response")
            PCloudDirectLoginResult.NetworkFailure -> mutableState.value = mutableState.value.copy(busy = false, status = "Could not reach pCloud")
        }
    }

    fun disconnectPCloud() = scope.launch {
        runCatching { vault.clear(PCLOUD_SESSION_KEY) }
        sources.entries.removeIf { it.key.value == "pcloud" }
        source = demoSource
        repository.setSetting("source", "demo")
        loadFolder(source.root.id, resetBreadcrumbs = true)
        mutableState.value = mutableState.value.copy(connectedToPCloud = false, status = "pCloud disconnected locally")
    }

    fun open(node: MediaNode) = when (node) {
        is AudioFolder -> scope.launch { loadFolder(node.id) }
        is AudioTrack -> play(node)
    }

    fun navigateTo(folder: AudioFolder) = scope.launch { loadFolder(folder.id, truncateTo = folder.id) }

    fun inspect(node: MediaNode) = scope.launch {
        runCatching { sourceFor(node).inspect(node.id).fields }
            .onSuccess { mutableState.value = mutableState.value.copy(inspection = it, status = "Inspection: ${node.name}") }
            .onFailure { mutableState.value = mutableState.value.copy(status = "Inspection failed: ${it.message}") }
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
        }.onFailure { mutableState.value = mutableState.value.copy(busy = false, status = "Folder load failed: ${it.message}") }
    }

    private suspend fun playCurrent() {
        val track = mutableState.value.queue.current?.track ?: return
        val sourceForTrack = sourceFor(track)
        val progress = repository.loadProgress(track.sourceId, track.id)?.let { ResumePolicy().normalize(it, System.currentTimeMillis()) }
        mutableState.value = mutableState.value.copy(status = "Resolving ${track.name}…")
        runCatching { mpv.load(sourceForTrack.resolveStream(track.id), progress?.positionMillis ?: 0) }
            .onSuccess { mutableState.value = mutableState.value.copy(status = "Playing ${track.name}") }
            .onFailure(::playbackFailure)
        updateMpris()
    }

    private suspend fun restoreQueue() {
        val stored = repository.loadQueue()
        val entries = stored.entries.mapNotNull { reference ->
            val selectedSource = sources.entries.firstOrNull { it.key == reference.sourceId }?.value ?: return@mapNotNull null
            runCatching { selectedSource.load(reference.nodeId) as? AudioTrack }.getOrNull()?.let { QueueEntry(it, reference.originFolderId) }
        }
        if (entries.isNotEmpty()) {
            val index = stored.currentIndex.coerceIn(0, entries.lastIndex)
            mutableState.value = mutableState.value.copy(queue = PlaybackQueue(0, entries, index), status = "Restored ${entries.size} queued tracks")
        }
        updateMpris()
    }

    private fun updateQueue(queue: PlaybackQueue) {
        mutableState.value = mutableState.value.copy(queue = queue)
        repository.saveQueue(queue)
        updateMpris()
    }

    private fun checkpoint(playback: MpvState) {
        val second = playback.positionMillis / 1_000
        if (second == lastCheckpointSecond || second % 5 != 0L) return
        lastCheckpointSecond = second
        val track = mutableState.value.queue.current?.track ?: return
        repository.saveProgress(PlaybackProgress(
            track.sourceId, track.id, playback.positionMillis, playback.durationMillis ?: track.durationMillis,
            1f, System.currentTimeMillis(), completed = false,
        ))
    }

    private fun restorePCloudSession() {
        if (!vault.available()) return
        val secret = runCatching { vault.lookup(PCLOUD_SESSION_KEY) }.getOrNull() ?: return
        try {
            val session = gson.fromJson(secret.concatToString(), PCloudSession::class.java)
            attachPCloud(session)
            mutableState.value = mutableState.value.copy(connectedToPCloud = true)
        } catch (_: RuntimeException) {
            runCatching { vault.clear(PCLOUD_SESSION_KEY) }
        } finally {
            secret.fill('\u0000')
        }
    }

    private fun attachPCloud(session: PCloudSession) {
        val pcloud = PCloudSourceFactory.create(session)
        sources[pcloud.id] = pcloud
    }

    private fun sourceFor(node: MediaNode): AudioSource = sources[node.sourceId] ?: error("source ${node.sourceId.value} is unavailable")

    private fun playbackFailure(error: Throwable) {
        mutableState.value = mutableState.value.copy(status = "Playback failed: ${error.message}")
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
        runCatching { checkpoint(mutableState.value.playback) }
        runCatching { mpris?.close() }
        runCatching { mpv.close() }
        runCatching { repository.close() }
        scope.cancel()
    }

    private companion object { const val PCLOUD_SESSION_KEY = "pcloud-session" }
}
