package dev.properpcloud.desktop.data

import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files

class SqliteStateRepositoryTest {
    @Test
    fun `round trips queue settings and progress`() {
        val root = Files.createTempDirectory("properpcloud-sqlite-test-")
        try {
            SqliteStateRepository(root.resolve("state.db")).use { repository ->
                val track = AudioTrack(SourceId("demo"), NodeId("demo:track:1"), NodeId("demo:folder:1"), "01 test.wav", durationMillis = 120_000)
                repository.setSetting("source", "demo")
                repository.saveQueue(PlaybackQueue(entries = listOf(QueueEntry(track)), currentIndex = 0))
                repository.saveProgress(PlaybackProgress(track.sourceId, track.id, 42_500, track.durationMillis, 1.25f, 123456789))
                assertEquals("demo", repository.setting("source"))
                assertEquals(track.id, repository.loadQueue().entries.single().nodeId)
                assertEquals(42_500L, repository.loadProgress(track.sourceId, track.id)?.positionMillis)
                assertNotNull(repository.loadProgress(track.sourceId, track.id))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
