package dev.properpcloud.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.FolderQueueAssembler
import dev.properpcloud.core.model.LibrarySearch
import dev.properpcloud.core.model.LibrarySearchRequest
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.SearchMatchType
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.desktop.data.DesktopDemoAudioSource
import dev.properpcloud.desktop.data.SqliteStateRepository
import dev.properpcloud.desktop.mpris.MprisActions
import dev.properpcloud.desktop.mpris.MprisService
import dev.properpcloud.desktop.mpris.MprisSnapshot
import dev.properpcloud.desktop.platform.XdgPaths
import dev.properpcloud.desktop.playback.MpvController
import dev.properpcloud.desktop.playback.MpvState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (playlistCliRequested(args)) {
        val exitCode = runPlaylistCli(args)
        if (exitCode != 0) exitProcess(exitCode)
        return
    }
    argumentValue(args, "--local-tag-recovery-kill-smoke")?.let { selectedRoot ->
        runLocalTagRecoveryPreKillSmoke(selectedRoot)
        return
    }
    argumentValue(args, "--local-tag-recovery-restart-smoke")?.let { selectedRoot ->
        runLocalTagRecoveryRestartSmoke(selectedRoot)
        return
    }
    if (args.contains("--locked-keyring-smoke")) {
        runLockedKeyringSmoke()
        return
    }
    if (args.contains("--sleep-monitor-smoke")) {
        runSleepMonitorSmoke()
        return
    }
    if (args.contains("--mpris-control-smoke")) {
        runMprisControlSmoke()
        return
    }
    if (args.contains("--mpris-smoke")) {
        runMprisSmoke()
        return
    }
    if (args.contains("--smoke")) {
        runDesktopSmoke()
        return
    }
    if (args.contains("--crash-recovery-smoke")) {
        runCrashRecoverySmoke()
        return
    }
    if (args.contains("--resilience-soak")) {
        runResilienceSoak()
        return
    }
    launchDesktop()
}

private fun argumentValue(args: Array<String>, name: String): String? {
    val index = args.indexOf(name)
    if (index < 0) return null
    return args.getOrNull(index + 1) ?: error("$name requires one directory argument")
}

private fun runCrashRecoverySmoke() = runBlocking {
    val root = Files.createTempDirectory("properpcloud-crash-recovery-")
    val paths = XdgPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache"), root.resolve("runtime")).create()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val source = DesktopDemoAudioSource(paths.cache.resolve("media"))
    val repository = SqliteStateRepository(paths.data.resolve("state.db"))
    val mpv = MpvController(paths.runtime, scope, extraArguments = listOf("--ao=null"))
    try {
        val folder = source.list(source.root.id).filterIsInstance<AudioFolder>().first()
        val queue = PlaybackQueue(entries = FolderQueueAssembler(source).build(folder.id, recursive = true).entries, currentIndex = 0)
        val track = requireNotNull(queue.current).track
        repository.saveQueue(queue)
        mpv.load(source.resolveStream(track.id))
        awaitSmokeCondition("initial mpv playback", attempts = 40, delayMillis = 50) {
            mpv.state.value.running && mpv.state.value.positionMillis > 0
        }
        val before = mpv.state.value.positionMillis.coerceAtLeast(0)
        repository.saveProgress(PlaybackProgress(track.sourceId, track.id, before, track.durationMillis, 1f, System.currentTimeMillis()))
        mpv.terminateProcessForSmoke()
        awaitSmokeCondition("unexpected mpv exit", attempts = 80, delayMillis = 25) {
            mpv.state.value.unexpectedExit
        }
        check(mpv.state.value.unexpectedExit && mpv.state.value.restartAvailable) { "unexpected mpv exit was not detected" }
        val persisted = requireNotNull(repository.loadProgress(track.sourceId, track.id))
        mpv.load(source.resolveStream(track.id), persisted.positionMillis)
        awaitSmokeCondition("manual mpv restart", attempts = 80, delayMillis = 25) {
            mpv.state.value.running && !mpv.state.value.unexpectedExit
        }
        check(mpv.state.value.running) { "mpv did not restart" }
        check(repository.loadQueue().entries[repository.loadQueue().currentIndex].nodeId == track.id) {
            "selected stable queue identity changed during recovery"
        }
        check(kotlin.math.abs(mpv.state.value.positionMillis - persisted.positionMillis) <= 5_000) {
            "restarted playback exceeded the five-second recovery bound"
        }
        println("properpcloud crash recovery smoke: OK (detected exit, zero automatic restarts, stable queue identity, bounded resume)")
    } finally {
        mpv.close()
        repository.close()
        scope.cancel()
        root.toFile().deleteRecursively()
    }
}

private suspend fun awaitSmokeCondition(
    description: String,
    attempts: Int,
    delayMillis: Long,
    condition: () -> Boolean,
) {
    repeat(attempts) {
        if (condition()) return
        delay(delayMillis)
    }
    error("timed out waiting for $description")
}

private fun runMprisSmoke() = runBlocking {
    val actions = object : MprisActions {
        override fun playPause() = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seek(offsetMillis: Long) = Unit
        override fun seekAbsolute(positionMillis: Long) = Unit
        override fun raise() = Unit
        override fun quit() = Unit
    }
    MprisService(actions).use { service ->
        service.update(
            MprisSnapshot(
                track = AudioTrack(
                    sourceId = SourceId("smoke"),
                    id = NodeId("smoke:track:1"),
                    parentId = NodeId("smoke:folder:1"),
                    name = "MPRIS smoke.wav",
                    taggedTitle = "MPRIS smoke",
                    durationMillis = 60_000,
                ),
                playback = MpvState(running = true, paused = true, durationMillis = 60_000, idle = false),
                canNext = true,
                canPrevious = true,
            ),
        )
        println("properpcloud MPRIS smoke ready")
        delay(15_000)
    }
}

private fun launchDesktop() = application {
    val controller = DesktopController()
    Window(
        onCloseRequest = {
            controller.close()
            exitApplication()
        },
        title = "properpcloud",
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
    ) {
        DisposableEffect(Unit) { onDispose(controller::close) }
        DesktopApp(controller)
    }
}

private fun runDesktopSmoke() = runBlocking {
    val root = Files.createTempDirectory("properpcloud-smoke-")
    val paths = XdgPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache"), root.resolve("runtime")).create()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val source = DesktopDemoAudioSource(paths.cache.resolve("media"))
    var repository = SqliteStateRepository(paths.data.resolve("state.db"))
    val mpv = MpvController(paths.runtime, scope, extraArguments = listOf("--ao=null"))
    try {
        val folder = source.list(source.root.id).filterIsInstance<AudioFolder>().first { it.name == "Numbered tracks" }
        val loadedNodes = source.list(folder.id)
        val searchTypes = setOf(SearchMatchType.AUDIO_FILES, SearchMatchType.PLAYLIST_FILES)
        val searchResults = LibrarySearch.matches(
            loadedNodes,
            LibrarySearchRequest("signal", searchTypes),
        )
        check(searchResults.size == 3 && searchResults.all { it is AudioTrack }) {
            "filename search did not return the three loaded audio matches"
        }
        repository.setSetting(
            SqliteStateRepository.SEARCH_MATCH_TYPES_KEY,
            searchTypes.sortedBy { it.ordinal }.joinToString(",") { it.name },
        )
        repository.setSetting(SqliteStateRepository.HISTORY_ENABLED_KEY, "true")
        repository.setSetting(SqliteStateRepository.HISTORY_RETENTION_KEY, "25")

        val queue = FolderQueueAssembler(source).build(folder.id, recursive = false)
        check(queue.entries.isNotEmpty()) { "demo queue is empty" }
        val playbackQueue = PlaybackQueue(entries = queue.entries, currentIndex = 1)
        repository.saveQueue(playbackQueue)
        val track: AudioTrack = playbackQueue.current!!.track
        mpv.load(source.resolveStream(track.id))
        delay(750)
        check(mpv.state.value.running) { "mpv did not remain running" }
        val checkpoint = PlaybackProgress(
            track.sourceId,
            track.id,
            mpv.state.value.positionMillis,
            track.durationMillis,
            1f,
            System.currentTimeMillis(),
        )
        repository.saveProgress(checkpoint)
        check(repository.loadQueue().entries.size == queue.entries.size) { "queue persistence mismatch" }
        check(repository.loadProgress(track.sourceId, track.id) != null) { "progress persistence mismatch" }
        check(repository.loadPlaybackHistory().single().nodeId == track.id) { "history persistence mismatch" }

        // Recreate the durable session boundary while playback state is still externally owned by
        // mpv. Only stable identity/settings/progress are expected to survive the repository close.
        repository.close()
        repository = SqliteStateRepository(paths.data.resolve("state.db"))
        val restoredQueue = repository.loadQueue()
        val restoredTrackId = restoredQueue.entries[restoredQueue.currentIndex].nodeId
        val restoredProgress = requireNotNull(repository.loadProgress(track.sourceId, track.id))
        check(restoredTrackId == track.id) { "current stable queue identity did not survive reopen" }
        check(restoredProgress.positionMillis == checkpoint.positionMillis) { "progress did not survive reopen" }
        check(repository.loadPlaybackHistory().single().nodeId == track.id) { "history did not survive reopen" }
        check(
            repository.setting(SqliteStateRepository.SEARCH_MATCH_TYPES_KEY) ==
                "AUDIO_FILES,PLAYLIST_FILES",
        ) { "search match preferences did not survive reopen" }

        mpv.stop()
        mpv.load(source.resolveStream(track.id), restoredProgress.positionMillis)
        awaitSmokeCondition("restored mpv playback", attempts = 80, delayMillis = 25) {
            mpv.state.value.running && !mpv.state.value.idle
        }
        check(kotlin.math.abs(mpv.state.value.positionMillis - restoredProgress.positionMillis) <= 5_000) {
            "restored playback exceeded the five-second resume bound"
        }
        println(
            "properpcloud desktop smoke: OK " +
                "(${queue.entries.size} tracks, filename search, mpv IPC, SQLite reopen, queue/progress/history/filter restore)",
        )
    } finally {
        mpv.close()
        repository.close()
        scope.cancel()
        root.toFile().deleteRecursively()
    }
}
