package dev.properpcloud.desktop.data

import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.SourceId
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

data class StoredQueueReference(val sourceId: SourceId, val nodeId: NodeId, val originFolderId: NodeId)
data class StoredQueue(val entries: List<StoredQueueReference>, val currentIndex: Int)

class SqliteStateRepository(database: Path) : AutoCloseable {
    private val connection: Connection

    init {
        Files.createDirectories(database.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            statement.execute("CREATE TABLE IF NOT EXISTS queue_entries (position INTEGER PRIMARY KEY, source_id TEXT NOT NULL, node_id TEXT NOT NULL, origin_id TEXT NOT NULL)")
            statement.execute("CREATE TABLE IF NOT EXISTS progress (source_id TEXT NOT NULL, node_id TEXT NOT NULL, position_ms INTEGER NOT NULL, duration_ms INTEGER, speed REAL NOT NULL, observed_ms INTEGER NOT NULL, completed INTEGER NOT NULL, PRIMARY KEY(source_id,node_id))")
        }
    }

    @Synchronized
    fun setting(key: String): String? = connection.prepareStatement("SELECT value FROM settings WHERE key=?").use { query ->
        query.setString(1, key)
        query.executeQuery().use { if (it.next()) it.getString(1) else null }
    }

    @Synchronized
    fun setSetting(key: String, value: String) {
        connection.prepareStatement("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use {
            it.setString(1, key); it.setString(2, value); it.executeUpdate()
        }
    }

    @Synchronized
    fun saveQueue(queue: PlaybackQueue) = transaction {
        connection.createStatement().use { it.executeUpdate("DELETE FROM queue_entries") }
        connection.prepareStatement("INSERT INTO queue_entries(position,source_id,node_id,origin_id) VALUES(?,?,?,?)").use { insert ->
            queue.entries.forEachIndexed { index, entry ->
                insert.setInt(1, index)
                insert.setString(2, entry.track.sourceId.value)
                insert.setString(3, entry.track.id.value)
                insert.setString(4, entry.originFolderId.value)
                insert.addBatch()
            }
            insert.executeBatch()
        }
        setSetting("queue.currentIndex", queue.currentIndex.toString())
    }

    @Synchronized
    fun loadQueue(): StoredQueue {
        val entries = connection.prepareStatement("SELECT source_id,node_id,origin_id FROM queue_entries ORDER BY position").use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(StoredQueueReference(SourceId(rows.getString(1)), NodeId(rows.getString(2)), NodeId(rows.getString(3))))
                }
            }
        }
        return StoredQueue(entries, setting("queue.currentIndex")?.toIntOrNull() ?: -1)
    }

    @Synchronized
    fun saveProgress(progress: PlaybackProgress) {
        connection.prepareStatement("""
            INSERT INTO progress(source_id,node_id,position_ms,duration_ms,speed,observed_ms,completed)
            VALUES(?,?,?,?,?,?,?) ON CONFLICT(source_id,node_id) DO UPDATE SET
            position_ms=excluded.position_ms,duration_ms=excluded.duration_ms,speed=excluded.speed,
            observed_ms=excluded.observed_ms,completed=excluded.completed
        """.trimIndent()).use {
            it.setString(1, progress.sourceId.value); it.setString(2, progress.nodeId.value)
            it.setLong(3, progress.positionMillis)
            val durationMillis = progress.durationMillis
            if (durationMillis == null) it.setNull(4, java.sql.Types.BIGINT) else it.setLong(4, durationMillis)
            it.setFloat(5, progress.playbackSpeed); it.setLong(6, progress.observedAtEpochMillis); it.setBoolean(7, progress.completed)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun loadProgress(sourceId: SourceId, nodeId: NodeId): PlaybackProgress? =
        connection.prepareStatement("SELECT position_ms,duration_ms,speed,observed_ms,completed FROM progress WHERE source_id=? AND node_id=?").use { query ->
            query.setString(1, sourceId.value); query.setString(2, nodeId.value)
            query.executeQuery().use { rows ->
                if (!rows.next()) null else PlaybackProgress(
                    sourceId, nodeId, rows.getLong(1), rows.getLong(2).takeUnless { rows.wasNull() },
                    rows.getFloat(3), rows.getLong(4), rows.getBoolean(5),
                )
            }
        }

    private fun <T> transaction(block: () -> T): T {
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        return try { block().also { connection.commit() } } catch (error: Throwable) { connection.rollback(); throw error } finally { connection.autoCommit = autoCommit }
    }

    override fun close() = connection.close()
}
