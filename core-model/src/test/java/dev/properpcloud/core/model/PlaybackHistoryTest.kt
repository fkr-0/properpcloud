package dev.properpcloud.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackHistoryTest {
    @Test
    fun `history upserts stable identity and obeys bounded retention`() {
        val source = SourceId("pcloud")
        fun progress(id: String, observed: Long) = PlaybackProgress(source, NodeId(id), observed, 100_000, 1f, observed)
        var history = emptyList<PlaybackHistoryEntry>()
        history = PlaybackHistoryPolicy.upsert(history, progress("one", 1), 2)
        history = PlaybackHistoryPolicy.upsert(history, progress("two", 2), 2)
        history = PlaybackHistoryPolicy.upsert(history, progress("one", 3), 2)
        history = PlaybackHistoryPolicy.upsert(history, progress("three", 4), 2)

        assertEquals(listOf("three", "one"), history.map { it.nodeId.value })
        assertEquals(3L, history.last().observedAtEpochMillis)
        assertEquals(PlaybackHistoryPolicy.MAX_RETENTION, PlaybackHistoryPolicy.normalizeRetention(Int.MAX_VALUE))
    }
}
