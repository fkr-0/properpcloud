package dev.properpcloud.desktop.data

import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.sql.DriverManager

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

    @Test
    fun `history is opt in bounded and survives repository reopen`() {
        val root = Files.createTempDirectory("properpcloud-sqlite-history-")
        val database = root.resolve("state.db")
        try {
            val source = SourceId("pcloud")
            SqliteStateRepository(database).use { repository ->
                repository.setSetting(SqliteStateRepository.HISTORY_ENABLED_KEY, "true")
                repository.setSetting(SqliteStateRepository.HISTORY_RETENTION_KEY, "2")
                (1L..3L).forEach { index ->
                    repository.saveProgress(
                        PlaybackProgress(source, NodeId("file:$index"), index * 1_000, 20_000, observedAtEpochMillis = index),
                    )
                }
                assertEquals(listOf("file:3", "file:2"), repository.loadPlaybackHistory().map { it.nodeId.value })
            }
            SqliteStateRepository(database).use { reopened ->
                assertEquals(listOf("file:3", "file:2"), reopened.loadPlaybackHistory().map { it.nodeId.value })
                reopened.setSetting(SqliteStateRepository.HISTORY_ENABLED_KEY, "false")
                reopened.saveProgress(PlaybackProgress(source, NodeId("file:4"), 4_000, 20_000, observedAtEpochMillis = 4))
                assertEquals(listOf("file:3", "file:2"), reopened.loadPlaybackHistory().map { it.nodeId.value })
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `opening legacy database adds history schema without losing queue or progress`() {
        val root = Files.createTempDirectory("properpcloud-sqlite-legacy-")
        val database = root.resolve("state.db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    statement.execute("CREATE TABLE queue_entries (position INTEGER PRIMARY KEY, source_id TEXT NOT NULL, node_id TEXT NOT NULL, origin_id TEXT NOT NULL)")
                    statement.execute("CREATE TABLE progress (source_id TEXT NOT NULL, node_id TEXT NOT NULL, position_ms INTEGER NOT NULL, duration_ms INTEGER, speed REAL NOT NULL, observed_ms INTEGER NOT NULL, completed INTEGER NOT NULL, PRIMARY KEY(source_id,node_id))")
                    statement.execute("INSERT INTO settings(key,value) VALUES('queue.currentIndex','0')")
                    statement.execute("INSERT INTO queue_entries(position,source_id,node_id,origin_id) VALUES(0,'demo','track:legacy','folder:legacy')")
                    statement.execute("INSERT INTO progress(source_id,node_id,position_ms,duration_ms,speed,observed_ms,completed) VALUES('demo','track:legacy',12000,60000,1.0,1234,0)")
                }
            }

            SqliteStateRepository(database).use { repository ->
                assertEquals(NodeId("track:legacy"), repository.loadQueue().entries.single().nodeId)
                assertEquals(12_000L, repository.loadProgress(SourceId("demo"), NodeId("track:legacy"))?.positionMillis)
                assertTrue(repository.loadPlaybackHistory().isEmpty())

                repository.setSetting(SqliteStateRepository.HISTORY_ENABLED_KEY, "true")
                repository.saveProgress(
                    PlaybackProgress(
                        SourceId("demo"),
                        NodeId("track:legacy"),
                        13_000,
                        60_000,
                        observedAtEpochMillis = 2_000,
                    ),
                )
                assertEquals(13_000L, repository.loadPlaybackHistory().single().positionMillis)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
