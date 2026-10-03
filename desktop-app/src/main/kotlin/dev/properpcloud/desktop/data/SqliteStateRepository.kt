package dev.properpcloud.desktop.data

import dev.properpcloud.core.model.AudioTabCollection
import dev.properpcloud.core.model.AudioTabColor
import dev.properpcloud.core.model.AudioTabId
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlayerRepeatMode
import dev.properpcloud.core.model.PlaybackHistoryEntry
import dev.properpcloud.core.model.PlaybackHistoryPolicy
import dev.properpcloud.core.model.PlaybackProgress
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.normalizeAudioRootPath
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.nio.file.StandardCopyOption

data class StoredQueueReference(val sourceId: SourceId, val nodeId: NodeId, val originFolderId: NodeId)
data class StoredQueue(val entries: List<StoredQueueReference>, val currentIndex: Int)
data class StoredAudioTab(
    val id: AudioTabId,
    val name: String,
    val rootPath: String,
    val icon: String?,
    val color: AudioTabColor?,
    val queue: StoredQueue,
    val currentFolderId: NodeId?,
    val playbackPositionMillis: Long,
    val playbackSpeed: Float,
    val volume: Float,
    val shuffle: Boolean,
    val repeatMode: PlayerRepeatMode,
)
data class StoredNamedPlaylist(val name: String, val entries: List<StoredQueueReference>)
data class StoredAudioTabs(
    val tabs: List<StoredAudioTab>,
    val activeTabId: AudioTabId,
    val playlists: List<StoredNamedPlaylist>,
)

class SqliteStateRepository(database: Path) : AutoCloseable {
    private val connection: Connection

    init {
        Files.createDirectories(database.parent)
        val opened = DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}")
        try {
            opened.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                statement.execute("CREATE TABLE IF NOT EXISTS queue_entries (position INTEGER PRIMARY KEY, source_id TEXT NOT NULL, node_id TEXT NOT NULL, origin_id TEXT NOT NULL)")
                statement.execute("CREATE TABLE IF NOT EXISTS progress (source_id TEXT NOT NULL, node_id TEXT NOT NULL, position_ms INTEGER NOT NULL, duration_ms INTEGER, speed REAL NOT NULL, observed_ms INTEGER NOT NULL, completed INTEGER NOT NULL, PRIMARY KEY(source_id,node_id))")
                statement.execute("CREATE TABLE IF NOT EXISTS playback_history (source_id TEXT NOT NULL, node_id TEXT NOT NULL, position_ms INTEGER NOT NULL, duration_ms INTEGER, observed_ms INTEGER NOT NULL, completed INTEGER NOT NULL, PRIMARY KEY(source_id,node_id))")
                statement.execute("CREATE TABLE IF NOT EXISTS audio_tabs (position INTEGER PRIMARY KEY, tab_id TEXT NOT NULL UNIQUE, name TEXT NOT NULL, root_path TEXT NOT NULL, icon TEXT, color TEXT, current_folder_id TEXT, playback_position_ms INTEGER NOT NULL, playback_speed REAL NOT NULL, volume REAL NOT NULL, shuffle INTEGER NOT NULL, repeat_mode TEXT NOT NULL)")
                statement.execute("CREATE TABLE IF NOT EXISTS audio_tab_queue_entries (tab_id TEXT NOT NULL, position INTEGER NOT NULL, source_id TEXT NOT NULL, node_id TEXT NOT NULL, origin_id TEXT NOT NULL, PRIMARY KEY(tab_id,position))")
                statement.execute("CREATE TABLE IF NOT EXISTS saved_playlists (position INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)")
                statement.execute("CREATE TABLE IF NOT EXISTS saved_playlist_entries (playlist_name TEXT NOT NULL, position INTEGER NOT NULL, source_id TEXT NOT NULL, node_id TEXT NOT NULL, origin_id TEXT NOT NULL, PRIMARY KEY(playlist_name,position))")
            }
        } catch (error: Throwable) {
            runCatching { opened.close() }
            throw error
        }
        connection = opened
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
    fun saveAudioTabs(collection: AudioTabCollection) = transaction {
        connection.createStatement().use {
            it.executeUpdate("DELETE FROM audio_tab_queue_entries")
            it.executeUpdate("DELETE FROM audio_tabs")
            it.executeUpdate("DELETE FROM saved_playlist_entries")
            it.executeUpdate("DELETE FROM saved_playlists")
            it.executeUpdate("DELETE FROM settings WHERE key LIKE 'audioTab.queueIndex.%'")
        }
        connection.prepareStatement(
            "INSERT INTO audio_tabs(position,tab_id,name,root_path,icon,color,current_folder_id,playback_position_ms,playback_speed,volume,shuffle,repeat_mode) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        ).use { insertTab ->
            collection.tabs.forEachIndexed { position, tab ->
                insertTab.setInt(1, position)
                insertTab.setString(2, tab.definition.id.value)
                insertTab.setString(3, tab.definition.name)
                insertTab.setString(4, tab.definition.rootPath)
                insertTab.setString(5, tab.definition.icon)
                insertTab.setString(6, tab.definition.color?.name)
                insertTab.setString(7, tab.currentFolderId?.value)
                insertTab.setLong(8, tab.playbackPositionMillis)
                insertTab.setFloat(9, tab.playbackSpeed)
                insertTab.setFloat(10, tab.volume)
                insertTab.setBoolean(11, tab.shuffle)
                insertTab.setString(12, tab.repeatMode.name)
                insertTab.addBatch()
            }
            insertTab.executeBatch()
        }
        connection.prepareStatement(
            "INSERT INTO audio_tab_queue_entries(tab_id,position,source_id,node_id,origin_id) VALUES(?,?,?,?,?)",
        ).use { insertQueue ->
            collection.tabs.forEach { tab ->
                tab.queue.entries.forEachIndexed { position, entry ->
                    insertQueue.setString(1, tab.definition.id.value)
                    insertQueue.setInt(2, position)
                    insertQueue.setString(3, entry.track.sourceId.value)
                    insertQueue.setString(4, entry.track.id.value)
                    insertQueue.setString(5, entry.originFolderId.value)
                    insertQueue.addBatch()
                }
            }
            insertQueue.executeBatch()
        }
        collection.tabs.forEach { tab ->
            setSetting("audioTab.queueIndex.${tab.definition.id.value}", tab.queue.currentIndex.toString())
        }
        connection.prepareStatement("INSERT INTO saved_playlists(position,name) VALUES(?,?)").use { insertPlaylist ->
            collection.playlists.forEachIndexed { position, playlist ->
                insertPlaylist.setInt(1, position)
                insertPlaylist.setString(2, playlist.name)
                insertPlaylist.addBatch()
            }
            insertPlaylist.executeBatch()
        }
        connection.prepareStatement(
            "INSERT INTO saved_playlist_entries(playlist_name,position,source_id,node_id,origin_id) VALUES(?,?,?,?,?)",
        ).use { insertEntry ->
            collection.playlists.forEach { playlist ->
                playlist.entries.forEachIndexed { position, entry ->
                    insertEntry.setString(1, playlist.name)
                    insertEntry.setInt(2, position)
                    insertEntry.setString(3, entry.track.sourceId.value)
                    insertEntry.setString(4, entry.track.id.value)
                    insertEntry.setString(5, entry.originFolderId.value)
                    insertEntry.addBatch()
                }
            }
            insertEntry.executeBatch()
        }
        setSetting(AUDIO_TABS_ACTIVE_KEY, collection.activeTabId.value)
        setSetting(AUDIO_TABS_VERSION_KEY, "1")
    }

    @Synchronized
    fun loadAudioTabs(): StoredAudioTabs? {
        if (setting(AUDIO_TABS_VERSION_KEY) != "1") return null
        val queueByTab = connection.prepareStatement(
            "SELECT tab_id,source_id,node_id,origin_id FROM audio_tab_queue_entries ORDER BY tab_id,position",
        ).use { query ->
            query.executeQuery().use { rows ->
                buildMap<String, MutableList<StoredQueueReference>> {
                    while (rows.next()) {
                        val reference = runCatching {
                            StoredQueueReference(SourceId(rows.getString(2)), NodeId(rows.getString(3)), NodeId(rows.getString(4)))
                        }.getOrNull() ?: continue
                        getOrPut(rows.getString(1)) { mutableListOf() } += reference
                    }
                }
            }
        }
        val tabs = connection.prepareStatement(
            "SELECT tab_id,name,root_path,icon,color,current_folder_id,playback_position_ms,playback_speed,volume,shuffle,repeat_mode FROM audio_tabs ORDER BY position",
        ).use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        runCatching {
                            val id = AudioTabId(rows.getString(1))
                            val name = rows.getString(2).trim().also { require(it.isNotEmpty()) }
                            StoredAudioTab(
                                id = id,
                                name = name,
                                rootPath = normalizeAudioRootPath(rows.getString(3)),
                                icon = rows.getString(4),
                                color = rows.getString(5)?.let { stored -> AudioTabColor.entries.firstOrNull { it.name == stored } },
                                queue = StoredQueue(
                                    entries = queueByTab[id.value].orEmpty(),
                                    currentIndex = setting("audioTab.queueIndex.${id.value}")?.toIntOrNull() ?: -1,
                                ),
                                currentFolderId = rows.getString(6)?.let(::NodeId),
                                playbackPositionMillis = rows.getLong(7).coerceAtLeast(0),
                                playbackSpeed = rows.getFloat(8).coerceIn(0.5f, 3f),
                                volume = rows.getFloat(9).coerceIn(0f, 1f),
                                shuffle = rows.getBoolean(10),
                                repeatMode = PlayerRepeatMode.entries.firstOrNull { it.name == rows.getString(11) }
                                    ?: PlayerRepeatMode.OFF,
                            )
                        }.getOrNull()?.let(::add)
                    }
                }
            }
        }
        if (tabs.isEmpty() || tabs.map { it.id }.distinct().size != tabs.size) return null
        val active = setting(AUDIO_TABS_ACTIVE_KEY)
            ?.let { runCatching { AudioTabId(it) }.getOrNull() }
            ?.takeIf { id -> tabs.any { it.id == id } }
            ?: tabs.first().id
        val playlistEntries = connection.prepareStatement(
            "SELECT playlist_name,source_id,node_id,origin_id FROM saved_playlist_entries ORDER BY playlist_name,position",
        ).use { query ->
            query.executeQuery().use { rows ->
                buildMap<String, MutableList<StoredQueueReference>> {
                    while (rows.next()) {
                        val reference = runCatching {
                            StoredQueueReference(SourceId(rows.getString(2)), NodeId(rows.getString(3)), NodeId(rows.getString(4)))
                        }.getOrNull() ?: continue
                        getOrPut(rows.getString(1)) { mutableListOf() } += reference
                    }
                }
            }
        }
        val playlists = connection.prepareStatement("SELECT name FROM saved_playlists ORDER BY position").use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        rows.getString(1)?.trim()?.takeIf(String::isNotEmpty)?.let { name ->
                            add(StoredNamedPlaylist(name, playlistEntries[name].orEmpty()))
                        }
                    }
                }.distinctBy { it.name.lowercase() }
            }
        }
        return StoredAudioTabs(tabs, active, playlists)
    }

    @Synchronized
    fun loadPlaybackHistory(): List<PlaybackHistoryEntry> =
        connection.prepareStatement(
            "SELECT source_id,node_id,position_ms,duration_ms,observed_ms,completed FROM playback_history ORDER BY observed_ms DESC,source_id,node_id",
        ).use { query ->
            query.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        runCatching {
                            PlaybackHistoryEntry(
                                sourceId = SourceId(rows.getString(1)),
                                nodeId = NodeId(rows.getString(2)),
                                positionMillis = rows.getLong(3),
                                durationMillis = rows.getLong(4).takeUnless { rows.wasNull() },
                                observedAtEpochMillis = rows.getLong(5),
                                completed = rows.getBoolean(6),
                            )
                        }.getOrNull()?.let(::add)
                    }
                }
            }
        }

    @Synchronized
    fun saveProgress(progress: PlaybackProgress) = transaction {
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
        if (setting(HISTORY_ENABLED_KEY)?.toBooleanStrictOrNull() == true) {
            connection.prepareStatement("""
                INSERT INTO playback_history(source_id,node_id,position_ms,duration_ms,observed_ms,completed)
                VALUES(?,?,?,?,?,?) ON CONFLICT(source_id,node_id) DO UPDATE SET
                position_ms=excluded.position_ms,duration_ms=excluded.duration_ms,
                observed_ms=excluded.observed_ms,completed=excluded.completed
            """.trimIndent()).use {
                it.setString(1, progress.sourceId.value); it.setString(2, progress.nodeId.value)
                it.setLong(3, progress.positionMillis)
                val durationMillis = progress.durationMillis
                if (durationMillis == null) it.setNull(4, java.sql.Types.BIGINT) else it.setLong(4, durationMillis)
                it.setLong(5, progress.observedAtEpochMillis); it.setBoolean(6, progress.completed)
                it.executeUpdate()
            }
            val retention = PlaybackHistoryPolicy.normalizeRetention(
                setting(HISTORY_RETENTION_KEY)?.toIntOrNull() ?: PlaybackHistoryPolicy.DEFAULT_RETENTION,
            )
            connection.prepareStatement(
                "DELETE FROM playback_history WHERE rowid NOT IN (SELECT rowid FROM playback_history ORDER BY observed_ms DESC, source_id, node_id LIMIT ?)",
            ).use {
                it.setInt(1, retention)
                it.executeUpdate()
            }
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

    companion object {
        const val HISTORY_ENABLED_KEY = "history.enabled"
        const val HISTORY_RETENTION_KEY = "history.retention"
        const val SEARCH_MATCH_TYPES_KEY = "search.matchTypes"
        const val AUDIO_TABS_VERSION_KEY = "audioTabs.version"
        const val AUDIO_TABS_ACTIVE_KEY = "audioTabs.active"

        fun openResilient(database: Path): SqliteStateRepository = try {
            SqliteStateRepository(database)
        } catch (error: SQLException) {
            if (!error.isCorruptDatabase()) throw error
            quarantineCorruptDatabase(database)
            SqliteStateRepository(database)
        }

        private fun SQLException.isCorruptDatabase(): Boolean =
            errorCode == 11 || errorCode == 26 || message.orEmpty().lowercase().let { message ->
                "database disk image is malformed" in message || "file is not a database" in message
            }

        private fun quarantineCorruptDatabase(database: Path) {
            val marker = "corrupt-${System.currentTimeMillis()}"
            listOf(database, Path.of("${database}-wal"), Path.of("${database}-shm")).forEach { path ->
                if (!Files.exists(path)) return@forEach
                val target = path.resolveSibling("${path.fileName}.$marker")
                Files.move(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
