package dev.fkr.properpcloud.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.gson.JsonParser
import java.nio.file.Path

class MpvControllerTest {
    @Test
    fun `encodes arguments as one JSON IPC command`() {
        val controller = MpvController(Path.of("/tmp"), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertEquals(
            "{\"command\":[\"seek\",15.0,\"relative+exact\"],\"request_id\":7}\n",
            controller.commandJson(listOf("seek", 15.0, "relative+exact"), 7),
        )
    }

    @Test
    fun `ignores events and unmatched command responses`() {
        val controller = MpvController(Path.of("/tmp"), CoroutineScope(SupervisorJob() + Dispatchers.Default))

        assertNull(controller.responseForRequest("{\"event\":\"file-loaded\"}", 7))
        assertNull(controller.responseForRequest("{\"request_id\":6,\"error\":\"success\",\"data\":1}", 7))
        assertEquals(
            42.5,
            requireNotNull(
                controller.responseForRequest("{\"request_id\":7,\"error\":\"success\",\"data\":42.5}", 7)
                    ?.get("data")?.asDouble,
            ),
            0.0,
        )
    }

    @Test
    fun `reports command failure without returning provider or stream details`() {
        val controller = MpvController(Path.of("/tmp"), CoroutineScope(SupervisorJob() + Dispatchers.Default))

        val error = assertThrows(IllegalStateException::class.java) {
            controller.responseForRequest(
                "{\"request_id\":7,\"error\":\"loading failed\",\"data\":\"https://secret.invalid\"}",
                7,
            )
        }
        assertEquals("mpv command failed", error.message)
    }

    @Test
    fun `file-loaded is accepted only after start for the expected playlist entry`() {
        val gate = MpvLoadEventGate()

        assertFalse(gate.acceptFileLoaded())
        assertFalse(gate.acceptStart(6))
        gate.expectEntryId(7)
        assertFalse(gate.acceptFileLoaded())
        assertFalse(gate.acceptStart(6))
        assertFalse(gate.acceptStart(null))
        assertTrue(gate.acceptStart(7))
        assertTrue(gate.acceptFileLoaded())

        // A new load generation expects a different mpv playlist entry. Events for the
        // previous entry must not make the replacement appear ready.
        gate.expectEntryId(8)
        assertFalse(gate.acceptFileLoaded())
        assertFalse(gate.acceptStart(7))
        assertTrue(gate.acceptStart(8))
        assertTrue(gate.acceptFileLoaded())
    }

    @Test
    fun `audio track discovery accepts audio-only and audio-video containers but rejects no-audio media`() {
        assertFalse(mpvTrackListHasAudio(JsonParser.parseString("[]").asJsonArray))
        assertFalse(mpvTrackListHasAudio(JsonParser.parseString("[{\"type\":\"video\"}]").asJsonArray))
        assertTrue(mpvTrackListHasAudio(JsonParser.parseString("[{\"type\":\"audio\"}]").asJsonArray))
        assertTrue(mpvTrackListHasAudio(JsonParser.parseString("[{\"type\":\"video\"},{\"type\":\"audio\"}]").asJsonArray))
        assertFalse(mpvTrackListHasAudio(null))
    }

    @Test
    fun `classifies deterministic media errors separately from network failures`() {
        assertEquals(MpvFailureKind.UNSUPPORTED_CODEC, classifyMpvFailure("Failed to open codec decoder"))
        assertEquals(MpvFailureKind.INVALID_MEDIA, classifyMpvFailure("demux: invalid container"))
        assertEquals(MpvFailureKind.NO_AUDIO, classifyMpvFailure("no audio stream found"))
        assertEquals(MpvFailureKind.TRANSIENT_NETWORK, classifyMpvFailure("HTTP connection timed out"))
        assertEquals(MpvFailureKind.UNKNOWN, classifyMpvFailure("opaque mpv failure"))
    }

    @Test
    fun `failure classifications never retain raw diagnostics`() {
        val failure = MpvPlaybackException(classifyMpvFailure("unsupported codec in https://private.invalid/file?token=secret"))
        assertEquals(MpvFailureKind.UNSUPPORTED_CODEC, failure.kind)
        assertFalse(failure.message.orEmpty().contains("private.invalid"))
        assertFalse(failure.message.orEmpty().contains("secret"))
    }

    @Test
    fun `unexpected process exit preserves whether playback should resume`() {
        val failed = mpvExitState(
            MpvState(running = true, paused = false, idle = false, positionMillis = 12_000),
            expected = false,
        )
        assertFalse(failed.running)
        assertTrue(failed.paused)
        assertTrue(failed.unexpectedExit)
        assertTrue(failed.restartAvailable)
        assertTrue(failed.resumeAfterRestart)
        assertEquals("mpv exited unexpectedly", failed.error)

        val closed = mpvExitState(failed, expected = true)
        assertFalse(closed.unexpectedExit)
        assertFalse(closed.restartAvailable)
        assertFalse(closed.resumeAfterRestart)
        assertNull(closed.error)
    }

    @Test
    fun `paused process crash remains paused after recovery`() {
        val failed = mpvExitState(
            MpvState(running = true, paused = true, idle = false, positionMillis = 5_000),
            expected = false,
        )

        assertTrue(failed.restartAvailable)
        assertFalse(failed.resumeAfterRestart)
    }

    @Test
    fun `unexpected idle transition is a redacted stream failure`() {
        val playing = MpvState(running = true, paused = false, idle = false, positionMillis = 12_000)

        val failed = mpvPlaybackState(
            previous = playing,
            paused = true,
            positionMillis = 12_000,
            durationMillis = 60_000,
            idle = true,
            eofReached = false,
            expectedIdle = false,
        )

        assertTrue(failed.streamFailure)
        assertTrue(failed.restartAvailable)
        assertEquals("mpv playback failed", failed.error)

        val nextPoll = mpvPlaybackState(
            previous = failed,
            paused = true,
            positionMillis = 12_000,
            durationMillis = 60_000,
            idle = true,
            eofReached = false,
            expectedIdle = false,
        )
        assertFalse(nextPoll.streamFailure)
        assertTrue(nextPoll.restartAvailable)
        assertEquals("mpv playback failed", nextPoll.error)
    }

    @Test
    fun `normal eof and explicit stop do not request link refresh`() {
        val playing = MpvState(running = true, paused = false, idle = false, positionMillis = 12_000)

        val eof = mpvPlaybackState(playing, true, 60_000, 60_000, true, eofReached = true, expectedIdle = false)
        val stopped = mpvPlaybackState(playing, true, 12_000, 60_000, true, eofReached = false, expectedIdle = true)

        assertFalse(eof.streamFailure)
        assertFalse(stopped.streamFailure)
        assertTrue(eof.eofReached)
        assertFalse(stopped.eofReached)
        assertNull(eof.error)
        assertNull(stopped.error)
    }

    @Test
    fun `known terminal failure survives polling without being reclassified as transient`() {
        val failed = MpvState(
            running = true,
            paused = true,
            idle = true,
            error = "unsupported or unavailable audio codec",
            failureKind = MpvFailureKind.UNSUPPORTED_CODEC,
            streamFailure = true,
        )

        val projected = mpvPlaybackState(
            previous = failed,
            paused = true,
            positionMillis = 0,
            durationMillis = null,
            idle = true,
            eofReached = false,
            expectedIdle = false,
        )

        assertEquals(MpvFailureKind.UNSUPPORTED_CODEC, projected.failureKind)
        assertEquals("unsupported or unavailable audio codec", projected.error)
        assertFalse(projected.streamFailure)
        assertFalse(projected.restartAvailable)
    }

    @Test
    fun `playback projection preserves bounded speed and normalized volume`() {
        val playing = MpvState(running = true, paused = false, idle = false)

        val projected = mpvPlaybackState(
            previous = playing,
            paused = false,
            positionMillis = 1_000,
            durationMillis = 60_000,
            idle = false,
            eofReached = false,
            expectedIdle = false,
            speed = 1.75f,
            volume = 0.42f,
        )

        assertEquals(1.75f, projected.speed)
        assertEquals(0.42f, projected.volume)
    }
}
