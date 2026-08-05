package dev.properpcloud.desktop

import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.desktop.mpris.MprisActions
import dev.properpcloud.desktop.mpris.MprisService
import dev.properpcloud.desktop.mpris.MprisSnapshot
import dev.properpcloud.desktop.platform.LogindSleepMonitor
import dev.properpcloud.desktop.playback.MpvState
import dev.properpcloud.desktop.security.SecretServiceVault
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

private const val MPRIS_CONTROL_TIMEOUT_SECONDS = 20L

fun runLockedKeyringSmoke() {
    var credential: CharArray? = null
    var lookupResult: Result<CharArray?>? = null
    val elapsedMillis = measureTimeMillis {
        lookupResult = runCatching {
            SecretServiceVault(lookupTimeoutSeconds = 2).lookup("pcloud-session")
        }
        credential = lookupResult?.getOrNull()
    }
    val result = requireNotNull(lookupResult)
    try {
        check(credential == null) { "locked Secret Service unexpectedly returned a credential" }
        check(elapsedMillis <= 5_000) { "locked Secret Service lookup exceeded the five-second bound" }
        val failure = result.exceptionOrNull()
        check(failure == null || failure.message == "Secret Service lookup timed out") {
            "locked Secret Service lookup failed outside the bounded timeout contract"
        }
        println(
            "properpcloud locked keyring smoke: OK " +
                "(credential_returned=false lookup_bounded=true elapsed_ms=$elapsedMillis)",
        )
    } finally {
        credential?.fill('\u0000')
    }
}

fun runSleepMonitorSmoke() {
    LogindSleepMonitor { }.use { }
    println("properpcloud logind sleep monitor smoke: OK (system_bus_subscription=true)")
}

fun runMprisControlSmoke() {
    val expected = listOf(
        "raise",
        "play-pause",
        "play",
        "pause",
        "stop",
        "next",
        "previous",
        "seek:5000",
        "position:12000",
    )
    val recorder = RecordingMprisActions(expected.size)
    MprisService(recorder).use { service ->
        service.update(
            MprisSnapshot(
                track = smokeTrack(),
                playback = MpvState(running = true, paused = true, durationMillis = 60_000, idle = false),
                canNext = true,
                canPrevious = true,
            ),
        )
        println("properpcloud MPRIS control smoke ready")
        check(recorder.await()) { "timed out waiting for external MPRIS control methods" }
        check(recorder.observed() == expected) {
            "unexpected MPRIS control sequence: ${recorder.observed()}"
        }
        println("properpcloud MPRIS control smoke: OK (methods=${expected.joinToString(",")})")
    }
}

private class RecordingMprisActions(expectedCalls: Int) : MprisActions {
    private val calls = CopyOnWriteArrayList<String>()
    private val latch = CountDownLatch(expectedCalls)

    override fun playPause() = record("play-pause")
    override fun play() = record("play")
    override fun pause() = record("pause")
    override fun stop() = record("stop")
    override fun next() = record("next")
    override fun previous() = record("previous")
    override fun seek(offsetMillis: Long) = record("seek:$offsetMillis")
    override fun seekAbsolute(positionMillis: Long) = record("position:$positionMillis")
    override fun raise() = record("raise")
    override fun quit() = Unit

    fun await(): Boolean = latch.await(MPRIS_CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    fun observed(): List<String> = calls.toList()

    private fun record(value: String) {
        calls += value
        latch.countDown()
    }
}

private fun smokeTrack() = AudioTrack(
    sourceId = SourceId("smoke"),
    id = NodeId("smoke:track:1"),
    parentId = NodeId("smoke:folder:1"),
    name = "MPRIS smoke.wav",
    taggedTitle = "MPRIS smoke",
    durationMillis = 60_000,
)
