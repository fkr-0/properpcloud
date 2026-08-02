package dev.properpcloud.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCheckpointPolicyTest {
    private val track = AudioTrack(
        sourceId = SourceId("pcloud"),
        id = NodeId("file:42"),
        parentId = NodeId("folder:7"),
        name = "chapter.flac",
        durationMillis = 100_000,
    )
    private val queue = PlaybackQueue(
        generation = 3,
        entries = listOf(QueueEntry(track, track.parentId)),
        currentIndex = 0,
    )
    private val mediaId = MediaIdentity.encode(track.sourceId, track.id)

    @Test
    fun firstObservationCreatesStableIdentityCheckpoint() {
        val decision = PlaybackCheckpointPolicy().evaluate(
            queue = queue,
            observation = PlaybackObservation(mediaId, 12_000, 100_000, isPlaying = true),
            cursor = PlaybackCheckpointCursor(),
            observedAtEpochMillis = 99,
        )

        assertEquals(track.sourceId, decision.progress?.sourceId)
        assertEquals(track.id, decision.progress?.nodeId)
        assertEquals(12_000L, decision.progress?.positionMillis)
        assertEquals(PlaybackCheckpointCursor(mediaId, 12_000), decision.cursor)
    }

    @Test
    fun playingObservationBelowThresholdDoesNotWriteAgain() {
        val cursor = PlaybackCheckpointCursor(mediaId, 12_000)
        val decision = PlaybackCheckpointPolicy().evaluate(
            queue,
            PlaybackObservation(mediaId, 19_999, 100_000, isPlaying = true),
            cursor,
            observedAtEpochMillis = 100,
        )

        assertNull(decision.progress)
        assertEquals(cursor, decision.cursor)
    }

    @Test
    fun pauseAndLifecycleForceAlwaysCheckpoint() {
        val policy = PlaybackCheckpointPolicy()
        val cursor = PlaybackCheckpointCursor(mediaId, 12_000)

        val paused = policy.evaluate(
            queue,
            PlaybackObservation(mediaId, 12_500, 100_000, isPlaying = false),
            cursor,
            observedAtEpochMillis = 101,
        )
        val forced = policy.evaluate(
            queue,
            PlaybackObservation(mediaId, 12_500, 100_000, isPlaying = true),
            cursor,
            observedAtEpochMillis = 102,
            force = true,
        )

        assertEquals(12_500L, paused.progress?.positionMillis)
        assertEquals(12_500L, forced.progress?.positionMillis)
    }

    @Test
    fun completionUsesObservedOrTrackDuration() {
        val decision = PlaybackCheckpointPolicy().evaluate(
            queue,
            PlaybackObservation(mediaId, 96_000, durationMillis = null, isPlaying = false),
            PlaybackCheckpointCursor(),
            observedAtEpochMillis = 103,
        )

        assertEquals(100_000L, decision.progress?.durationMillis)
        assertTrue(decision.progress?.completed == true)
    }

    @Test
    fun unknownOrMalformedMediaIdentityIsIgnored() {
        val policy = PlaybackCheckpointPolicy()

        assertNull(
            policy.evaluate(
                queue,
                PlaybackObservation("malformed", 1, null, isPlaying = false),
                PlaybackCheckpointCursor(),
                104,
            ).progress,
        )
        assertNull(
            policy.evaluate(
                queue,
                PlaybackObservation(
                    MediaIdentity.encode(SourceId("demo"), NodeId("missing")),
                    1,
                    null,
                    isPlaying = false,
                ),
                PlaybackCheckpointCursor(),
                105,
            ).progress,
        )
    }
}
