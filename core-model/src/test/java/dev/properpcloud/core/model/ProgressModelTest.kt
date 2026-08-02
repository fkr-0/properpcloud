package dev.properpcloud.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressModelTest {
    private val sourceId = SourceId("test")
    private val nodeId = NodeId("track")

    @Test
    fun completedItemsAreMarkedWithoutRewind() {
        val record = PlaybackProgress(sourceId, nodeId, 96_000, 100_000, observedAtEpochMillis = 1_000)
        val normalized = ResumePolicy().normalize(record, nowEpochMillis = 2_000)
        assertTrue(normalized.completed)
        assertEquals(96_000, normalized.positionMillis)
    }

    @Test
    fun longInterruptionUsesLongSmartRewind() {
        val record = PlaybackProgress(sourceId, nodeId, 60_000, 1_000_000, observedAtEpochMillis = 0)
        val normalized = ResumePolicy().normalize(record, nowEpochMillis = 31 * 60 * 1_000L)
        assertEquals(45_000, normalized.positionMillis)
    }
}
