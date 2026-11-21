package dev.fkr.properpcloud.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFormatClassifierTest {
    @Test
    fun `known audio extensions are candidates with deterministic playback hints`() {
        val expected = mapOf(
            "track.mp3" to "audio/mpeg",
            "book.m4b" to "audio/mp4",
            "lossless.alac" to "audio/alac",
            "archive.ape" to "audio/x-ape",
            "voice.wma" to "audio/x-ms-wma",
            "track.opus" to "audio/opus",
            "recording.AIFF" to "audio/aiff",
            "track.flac" to "audio/flac",
        )

        expected.forEach { (name, mime) ->
            val result = MediaFormatRegistry.classify(name, null)
            assertEquals(name, MediaFormatEvidence.AUDIO_CANDIDATE, result.evidence)
            assertEquals(name, mime, result.playbackMimeType)
        }
    }

    @Test
    fun `uppercase extensions are normalized independently of locale`() {
        val result = MediaFormatRegistry.classify("Album/VOICE.OPUS", null)
        assertEquals("opus", result.extension)
        assertEquals("audio/opus", result.playbackMimeType)
        assertTrue(result.isAudioCandidate)
    }

    @Test
    fun `mp4 remains ambiguous without explicit audio MIME evidence`() {
        listOf(null, "application/octet-stream", "video/mp4").forEach { mime ->
            val result = MediaFormatRegistry.classify("movie.mp4", mime)
            assertFalse(result.isAudioCandidate)
            assertEquals(
                if (mime == "video/mp4") MediaFormatEvidence.CONFLICTING
                else MediaFormatEvidence.AMBIGUOUS_CONTAINER,
                result.evidence,
            )
            assertNull(result.playbackMimeType)
        }
        assertEquals(
            "audio/mp4",
            MediaFormatRegistry.classify("audio.mp4", " AUDIO/MP4 ; codecs=alac ").playbackMimeType,
        )
    }

    @Test
    fun `extensionless files require an allowlisted audio MIME type`() {
        val result = MediaFormatRegistry.classify("recording", " AUDIO/OPUS ; codecs=opus ")
        assertEquals(MediaFormatEvidence.AUDIO_CANDIDATE, result.evidence)
        assertEquals("audio/opus", result.normalizedMimeType)
        assertEquals("audio/opus", result.playbackMimeType)

        val unknown = MediaFormatRegistry.classify("payload", "audio/x-unknown")
        assertEquals(MediaFormatEvidence.UNKNOWN, unknown.evidence)
        assertFalse(unknown.isAudioCandidate)
    }

    @Test
    fun `arbitrary audio labels do not promote executable suffixes`() {
        val result = MediaFormatRegistry.classify("installer.EXE", "audio/mpeg")
        assertEquals(MediaFormatEvidence.CONFLICTING, result.evidence)
        assertFalse(result.isAudioCandidate)
        assertNull(result.playbackMimeType)
    }

    @Test
    fun `octet stream defers to a recognized extension but not an ambiguous container`() {
        assertEquals(
            "audio/flac",
            MediaFormatRegistry.classify("track.FLAC", "application/octet-stream").playbackMimeType,
        )
        assertEquals(
            MediaFormatEvidence.AMBIGUOUS_CONTAINER,
            MediaFormatRegistry.classify("movie.MP4", "application/octet-stream").evidence,
        )
        assertEquals(
            MediaFormatEvidence.UNKNOWN,
            MediaFormatRegistry.classify("unknown.bin", "application/octet-stream").evidence,
        )
    }

    @Test
    fun `conflicting MIME and extension evidence is retained without guessing`() {
        val mismatch = MediaFormatRegistry.classify("track.mp3", "audio/flac")
        assertEquals(MediaFormatEvidence.CONFLICTING, mismatch.evidence)
        assertNull(mismatch.playbackMimeType)

        val nonAudio = MediaFormatRegistry.classify("notes.txt", "text/plain; charset=utf-8")
        assertEquals("text/plain", nonAudio.normalizedMimeType)
        assertEquals(MediaFormatEvidence.NON_AUDIO, nonAudio.evidence)

        val malformed = MediaFormatRegistry.classify("track.mp3", "audio / mpeg")
        assertEquals(MediaFormatEvidence.CONFLICTING, malformed.evidence)
        assertNull(malformed.normalizedMimeType)
    }

    @Test
    fun `normalization is ASCII case insensitive and strips parameters`() {
        assertEquals("audio/mp4", MediaFormatRegistry.normalizeMimeType("  AuDiO/Mp4 ; codecs=\"alac\" "))
        assertNull(MediaFormatRegistry.normalizeMimeType("audio/*"))
        assertNull(MediaFormatRegistry.normalizeMimeType("audio/mp4/extra"))
        assertNull(MediaFormatRegistry.normalizeMimeType("audio/é"))
    }

    @Test
    fun `supported MIME types derive from the canonical extension registry`() {
        assertTrue(MediaFormatRegistry.supportedMimeTypes.contains("audio/x-ape"))
        assertTrue(MediaFormatRegistry.supportedMimeTypes.contains("audio/x-ms-wma"))
        assertTrue(MediaFormatRegistry.supportedMimeTypes.contains("audio/aiff"))
        assertFalse(MediaFormatRegistry.supportedMimeTypes.contains("video/mp4"))
        assertEquals(MediaFormatRegistry.supportedMimeTypes.distinct().sorted(), MediaFormatRegistry.supportedMimeTypes)
    }
}
