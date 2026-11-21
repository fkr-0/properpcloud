package dev.fkr.properpcloud.source.pcloud

import dev.fkr.properpcloud.core.model.MediaFormatEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PCloudMediaFormatTest {
    @Test
    fun `pCloud known extensions map through shared registry`() {
        val expected = mapOf(
            "book.m4b" to "audio/mp4",
            "album.alac" to "audio/alac",
            "recording.ape" to "audio/x-ape",
            "voice.wma" to "audio/x-ms-wma",
            "stream.opus" to "audio/opus",
            "concert.AIFF" to "audio/aiff",
        )

        expected.forEach { (name, mime) ->
            val result = classifyPCloudMedia(name, null)
            assertEquals(name, MediaFormatEvidence.AUDIO_CANDIDATE, result.evidence)
            assertEquals(name, mime, result.playbackMimeType)
        }
    }

    @Test
    fun `pCloud extensionless MIME-labelled files require an allowlisted essence`() {
        val result = classifyPCloudMedia("voice", " AUDIO/OPUS ; codecs=opus ")
        assertTrue(result.isAudioCandidate)
        assertEquals("audio/opus", result.playbackMimeType)

        val unknown = classifyPCloudMedia("payload", "audio/x-private-provider")
        assertFalse(unknown.isAudioCandidate)
        assertNull(unknown.playbackMimeType)
    }

    @Test
    fun `pCloud MP4 remains ambiguous except with explicit audio MIME`() {
        listOf(null, "application/octet-stream", "video/mp4").forEach { mime ->
            val result = classifyPCloudMedia("film.mp4", mime)
            assertFalse(result.isAudioCandidate)
            assertNull(result.playbackMimeType)
        }

        val audio = classifyPCloudMedia("audio.mp4", "audio/mp4; codecs=alac")
        assertTrue(audio.isAudioCandidate)
        assertEquals("audio/mp4", audio.playbackMimeType)
    }

    @Test
    fun `pCloud conflicts do not turn arbitrary MIME into audio candidates`() {
        val executable = classifyPCloudMedia("payload.EXE", "audio/mpeg")
        assertEquals(MediaFormatEvidence.CONFLICTING, executable.evidence)
        assertFalse(executable.isAudioCandidate)

        val mismatch = classifyPCloudMedia("recording.mp3", "audio/flac")
        assertEquals(MediaFormatEvidence.CONFLICTING, mismatch.evidence)
        assertNull(mismatch.playbackMimeType)
    }

    @Test
    fun `pCloud octet stream falls back only to unambiguous known extensions`() {
        assertEquals("audio/aiff", classifyPCloudMedia("recording.AIFF", "application/octet-stream").playbackMimeType)
        assertEquals(MediaFormatEvidence.AMBIGUOUS_CONTAINER, classifyPCloudMedia("movie.MP4", "application/octet-stream").evidence)
        assertEquals(MediaFormatEvidence.UNKNOWN, classifyPCloudMedia("data.bin", "application/octet-stream").evidence)
    }
}
