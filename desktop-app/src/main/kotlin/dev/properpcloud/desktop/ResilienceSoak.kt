package dev.properpcloud.desktop

import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.FolderQueueAssembler
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.desktop.data.DesktopDemoAudioSource
import dev.properpcloud.desktop.data.SqliteStateRepository
import dev.properpcloud.desktop.platform.XdgPaths
import dev.properpcloud.desktop.playback.MpvController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.math.abs

fun runResilienceSoak(durationSeconds: Long = System.getenv("PROPERPCLOUD_SOAK_SECONDS")?.toLongOrNull() ?: 120L) = runBlocking {
    require(durationSeconds in 10..14_400) { "soak duration must be between 10 seconds and four hours" }
    val root = Files.createTempDirectory("properpcloud-resilience-soak-")
    val paths = XdgPaths(root.resolve("config"), root.resolve("data"), root.resolve("cache"), root.resolve("runtime")).create()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val source = DesktopDemoAudioSource(paths.cache.resolve("media"))
    val repository = SqliteStateRepository(paths.data.resolve("state.db"))
    val mpv = MpvController(paths.runtime, scope, extraArguments = listOf("--ao=null"))
    val startedAt = System.nanoTime()
    val initialMemory = usedMemoryBytes()
    var cycles = 0
    var forcedExits = 0
    var maximumDrift = 0L
    try {
        val folder = source.list(source.root.id).filterIsInstance<AudioFolder>().first()
        val queue = PlaybackQueue(
            entries = FolderQueueAssembler(source).build(folder.id, recursive = true).entries,
            currentIndex = 0,
        )
        require(queue.entries.isNotEmpty()) { "soak queue is empty" }
        repository.saveQueue(queue)
        val track = requireNotNull(queue.current).track
        mpv.load(source.resolveStream(track.id))
        awaitSoakCondition("soak playback", attempts = 80, delayMillis = 25) {
            mpv.state.value.running && mpv.state.value.positionMillis > 0
        }
        val deadline = System.nanoTime() + durationSeconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            mpv.pause(true)
            delay(40)
            mpv.seekRelative(250)
            mpv.pause(false)
            delay(160)
            mpv.seekAbsolute((mpv.state.value.positionMillis / 2).coerceAtLeast(0))
            delay(120)
            val observed = mpv.state.value.positionMillis.coerceAtLeast(0)
            repository.saveProgress(
                PlaybackProgress(track.sourceId, track.id, observed, track.durationMillis, 1f, System.currentTimeMillis()),
            )
            if (cycles == 1) {
                mpv.terminateProcessForSmoke()
                awaitSoakCondition("soak forced mpv exit", attempts = 80, delayMillis = 25) {
                    mpv.state.value.unexpectedExit
                }
                forcedExits += 1
                val saved = requireNotNull(repository.loadProgress(track.sourceId, track.id))
                mpv.load(source.resolveStream(track.id), saved.positionMillis)
                awaitSoakCondition("soak explicit recovery", attempts = 80, delayMillis = 25) {
                    mpv.state.value.running && !mpv.state.value.unexpectedExit
                }
                maximumDrift = maxOf(maximumDrift, abs(mpv.state.value.positionMillis - saved.positionMillis))
            }
            val persistedQueue = repository.loadQueue()
            check(persistedQueue.entries[persistedQueue.currentIndex].nodeId == track.id) {
                "soak changed selected queue identity"
            }
            cycles += 1
        }
        val memoryGrowth = usedMemoryBytes() - initialMemory
        check(maximumDrift <= 5_000) { "soak recovery exceeded five-second drift" }
        check(memoryGrowth <= 192L * 1024 * 1024) { "soak memory growth exceeded 192 MiB" }
        println(
            "properpcloud resilience soak: OK " +
                "(seconds=$durationSeconds cycles=$cycles forced_exits=$forcedExits " +
                "max_drift_ms=$maximumDrift memory_growth_bytes=$memoryGrowth)",
        )
    } finally {
        mpv.close()
        repository.close()
        scope.cancel()
        root.toFile().deleteRecursively()
    }
}

private suspend fun awaitSoakCondition(
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

private fun usedMemoryBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
