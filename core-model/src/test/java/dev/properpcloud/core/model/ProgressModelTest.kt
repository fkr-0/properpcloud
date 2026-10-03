package dev.properpcloud.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressModelTest {
    private val source = SourceId("demo")
    private val node = NodeId("track:1")

    @Test
    fun `out of range and completed progress restart while active progress rewinds`() {
        val policy = ResumePolicy(smartRewindShortMillis = 5_000)
        val stale = PlaybackProgress(source, node, 150_000, 120_000, observedAtEpochMillis = 10)
        val complete = PlaybackProgress(source, node, 119_000, 120_000, observedAtEpochMillis = 10, completed = true)
        val active = PlaybackProgress(source, node, 50_000, 120_000, observedAtEpochMillis = 10)
        assertEquals(0L, policy.resumePositionMillis(stale, nowEpochMillis = 20, knownDurationMillis = 120_000))
        assertEquals(0L, policy.resumePositionMillis(complete, nowEpochMillis = 20, knownDurationMillis = 120_000))
        assertEquals(45_000L, policy.resumePositionMillis(active, nowEpochMillis = 20, knownDurationMillis = 120_000))
    }

    @Test
    fun completedItemsAreMarkedWithoutChangingTheStoredCheckpoint() {
        val record = PlaybackProgress(source, node, 96_000, 100_000, observedAtEpochMillis = 1_000)
        val normalized = ResumePolicy().normalize(record, nowEpochMillis = 2_000)
        assertTrue(normalized.completed)
        assertEquals(96_000, normalized.positionMillis)
    }

    @Test
    fun longInterruptionUsesLongSmartRewind() {
        val record = PlaybackProgress(source, node, 60_000, 1_000_000, observedAtEpochMillis = 0)
        val normalized = ResumePolicy().normalize(record, nowEpochMillis = 31 * 60 * 1_000L)
        assertEquals(45_000, normalized.positionMillis)
    }
}
