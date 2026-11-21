package dev.fkr.properpcloud.desktop.mpris

import dev.fkr.properpcloud.core.model.AudioTrack
import dev.fkr.properpcloud.core.model.NodeId
import dev.fkr.properpcloud.core.model.SourceId
import dev.fkr.properpcloud.desktop.playback.MpvState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MprisServiceTest {
    @Test
    fun `live mpv duration fills absent track metadata and stable metadata wins`() {
        val trackWithoutDuration = track()
        val live = MpvState(durationMillis = 123_456)
        assertEquals(123_456_000L, mprisDurationMicros(trackWithoutDuration, live))

        val trackWithDuration = track().copy(durationMillis = 90_000)
        assertEquals(90_000_000L, mprisDurationMicros(trackWithDuration, live))
        assertEquals(null, mprisDurationMicros(null, MpvState()))
    }

    @Test
    fun `URI schemes are not advertised while URI opening is unsupported`() {
        assertTrue(mprisSupportedUriSchemes().isEmpty())
        val failure = assertThrows(UnsupportedOperationException::class.java) {
            rejectUnsupportedMprisUri("file:///tmp/private.mp3")
        }
        assertEquals("URI opening is not supported", failure.message)
        assertFalse(failure.message.orEmpty().contains("/tmp/private"))
    }

    @Test
    fun `supported MIME values are audio-only and include admitted containers`() {
        val mimeTypes = mprisSupportedMimeTypes()
        assertTrue(mimeTypes.contains("audio/webm"))
        assertTrue(mimeTypes.contains("audio/x-matroska"))
        assertTrue(mimeTypes.all { it.startsWith("audio/") })
        assertFalse(mimeTypes.contains("video/webm"))
        assertFalse(mimeTypes.contains("video/x-matroska"))
    }

    private fun track() = AudioTrack(
        sourceId = SourceId("local:test"),
        id = NodeId("local-track:test"),
        parentId = NodeId("local-folder:test"),
        name = "sample.webm",
    )
}
