package dev.properpcloud.server

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

data class CatalogEntry(
    val nodeId: String,
    val parentNodeId: String?,
    val path: String,
    val name: String,
    val kind: String,
    val sizeBytes: Long? = null,
    val modifiedAtEpochMillis: Long? = null,
    val contentHash: String? = null,
    val providerFileId: Long? = null,
    val providerFolderId: Long? = null,
    val contentType: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMillis: Long? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitDepth: Int? = null,
    val format: String? = null,
    val metadataError: String? = null,
    val scanGeneration: Long = 0,
)

data class ScanStatus(
    val state: String,
    val generation: Long,
    val startedAtEpochMillis: Long?,
    val completedAtEpochMillis: Long?,
    val scannedFiles: Long,
    val changedFiles: Long,
    val errors: Long,
    val providerOnline: Boolean,
    val mountOnline: Boolean,
    val message: String?,
)

class CatalogRepository(database: Path) : AutoCloseable {
    private val connection: Connection

    init {
        database.parent?.let(Files::createDirectories)
        connection = DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA busy_timeout = 5000")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS provider_entries (
                    path TEXT PRIMARY KEY, name TEXT NOT NULL, is_folder INTEGER NOT NULL,
                    file_id INTEGER, folder_id INTEGER, parent_folder_id INTEGER, size_bytes INTEGER,
                    modified_ms INTEGER, provider_hash TEXT, refreshed_ms INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS library_entries (
                    node_id TEXT PRIMARY KEY, parent_node_id TEXT, path TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
                    kind TEXT NOT NULL, size_bytes INTEGER, modified_ms INTEGER, content_hash TEXT,
                    provider_file_id INTEGER, provider_folder_id INTEGER, content_type TEXT,
                    title TEXT, artist TEXT, album TEXT, album_artist TEXT, genre TEXT, year TEXT,
                    track_number INTEGER, disc_number INTEGER, duration_ms INTEGER, sample_rate INTEGER,
                    channels INTEGER, bit_depth INTEGER, format TEXT, metadata_error TEXT,
                    scan_generation INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_library_parent ON library_entries(parent_node_id, kind, name)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_library_hash ON library_entries(content_hash) WHERE content_hash IS NOT NULL")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_library_artist_album ON library_entries(artist, album)")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS scan_status (
                    singleton INTEGER PRIMARY KEY CHECK(singleton=1), state TEXT NOT NULL, generation INTEGER NOT NULL,
                    started_ms INTEGER, completed_ms INTEGER, scanned_files INTEGER NOT NULL DEFAULT 0,
                    changed_files INTEGER NOT NULL DEFAULT 0, errors INTEGER NOT NULL DEFAULT 0,
                    provider_online INTEGER NOT NULL DEFAULT 0, mount_online INTEGER NOT NULL DEFAULT 0,
                    message TEXT
                )
                """.trimIndent(),
            )
            statement.execute("INSERT OR IGNORE INTO scan_status(singleton,state,generation) VALUES(1,'never',0)")
        }
    }

    @Synchronized
    fun duplicateGroups(limit: Int = 100): Map<String, List<CatalogEntry>> {
        val boundedLimit = limit.coerceIn(1, 500)
        val hashes = connection.prepareStatement(
            "SELECT content_hash FROM library_entries WHERE content_hash IS NOT NULL GROUP BY content_hash HAVING COUNT(*) > 1 ORDER BY COUNT(*) DESC, content_hash LIMIT ?",
        ).use { query ->
            query.setInt(1, boundedLimit)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        return hashes.associateWith { hash ->
            queryMany("SELECT * FROM library_entries WHERE content_hash=? ORDER BY path COLLATE NOCASE,node_id", listOf(hash))
        }
    }

    @Synchronized
    fun updateScanProgress(generation: Long, scanned: Long, changed: Long, errors: Long) {
        connection.prepareStatement(
            "UPDATE scan_status SET scanned_files=?,changed_files=?,errors=? WHERE singleton=1 AND generation=?",
        ).use {
            it.setLong(1, scanned); it.setLong(2, changed); it.setLong(3, errors); it.setLong(4, generation); it.executeUpdate()
        }
    }

    @Synchronized
    fun replaceProviderEntries(entries: List<PCloudProviderEntry>, refreshedAt: Long = System.currentTimeMillis()) = transaction {
        connection.createStatement().use { it.executeUpdate("DELETE FROM provider_entries") }
        connection.prepareStatement(
            "INSERT INTO provider_entries(path,name,is_folder,file_id,folder_id,parent_folder_id,size_bytes,modified_ms,provider_hash,refreshed_ms) VALUES(?,?,?,?,?,?,?,?,?,?)",
        ).use { insert ->
            entries.forEach { entry ->
                insert.setString(1, entry.path)
                insert.setString(2, entry.name)
                insert.setBoolean(3, entry.isFolder)
                insert.setNullableLong(4, entry.fileId)
                insert.setNullableLong(5, entry.folderId)
                insert.setNullableLong(6, entry.parentFolderId)
                insert.setNullableLong(7, entry.sizeBytes)
                insert.setNullableLong(8, entry.modifiedAtEpochMillis)
                insert.setString(9, entry.providerHash)
                insert.setLong(10, refreshedAt)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    @Synchronized
    fun providerEntry(path: String): PCloudProviderEntry? = connection.prepareStatement(
        "SELECT name,is_folder,file_id,folder_id,parent_folder_id,size_bytes,modified_ms,provider_hash FROM provider_entries WHERE path=?",
    ).use { query ->
        query.setString(1, path)
        query.executeQuery().use { rows ->
            if (!rows.next()) null else PCloudProviderEntry(
                path = path,
                name = rows.getString(1),
                isFolder = rows.getBoolean(2),
                fileId = rows.nullableLong(3),
                folderId = rows.nullableLong(4),
                parentFolderId = rows.nullableLong(5),
                sizeBytes = rows.nullableLong(6),
                modifiedAtEpochMillis = rows.nullableLong(7),
                providerHash = rows.getString(8),
            )
        }
    }

    @Synchronized
    fun existingByPath(path: String): CatalogEntry? = queryOne("SELECT * FROM library_entries WHERE path=?", path)

    @Synchronized
    fun findByNodeId(nodeId: String): CatalogEntry? = queryOne("SELECT * FROM library_entries WHERE node_id=?", nodeId)

    @Synchronized
    fun upsert(entry: CatalogEntry) {
        connection.prepareStatement("DELETE FROM library_entries WHERE node_id=? AND path<>?").use {
            it.setString(1, entry.nodeId)
            it.setString(2, entry.path)
            it.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO library_entries(
              node_id,parent_node_id,path,name,kind,size_bytes,modified_ms,content_hash,provider_file_id,provider_folder_id,
              content_type,title,artist,album,album_artist,genre,year,track_number,disc_number,duration_ms,sample_rate,
              channels,bit_depth,format,metadata_error,scan_generation
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(path) DO UPDATE SET
              node_id=excluded.node_id,parent_node_id=excluded.parent_node_id,name=excluded.name,kind=excluded.kind,
              size_bytes=excluded.size_bytes,modified_ms=excluded.modified_ms,content_hash=excluded.content_hash,
              provider_file_id=excluded.provider_file_id,provider_folder_id=excluded.provider_folder_id,
              content_type=excluded.content_type,title=excluded.title,artist=excluded.artist,album=excluded.album,
              album_artist=excluded.album_artist,genre=excluded.genre,year=excluded.year,track_number=excluded.track_number,
              disc_number=excluded.disc_number,duration_ms=excluded.duration_ms,sample_rate=excluded.sample_rate,
              channels=excluded.channels,bit_depth=excluded.bit_depth,format=excluded.format,
              metadata_error=excluded.metadata_error,scan_generation=excluded.scan_generation
            """.trimIndent(),
        ).use { statement ->
            entry.bind(statement)
            statement.executeUpdate()
        }
    }

    @Synchronized
    fun touch(path: String, generation: Long) {
        connection.prepareStatement("UPDATE library_entries SET scan_generation=? WHERE path=?").use {
            it.setLong(1, generation); it.setString(2, path); it.executeUpdate()
        }
    }

    @Synchronized
    fun removeNotInGeneration(generation: Long) {
        connection.prepareStatement("DELETE FROM library_entries WHERE scan_generation<>?").use {
            it.setLong(1, generation); it.executeUpdate()
        }
    }

    @Synchronized
    fun browse(parentNodeId: String): List<CatalogEntry> = queryMany(
        "SELECT * FROM library_entries WHERE parent_node_id=? ORDER BY CASE kind WHEN 'folder' THEN 0 WHEN 'audio' THEN 1 ELSE 2 END, name COLLATE NOCASE, node_id",
        listOf(parentNodeId),
    )

    @Synchronized
    fun search(query: String, limit: Int = 100): List<CatalogEntry> {
        val pattern = "%${query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_")}%"
        return queryMany(
            """
            SELECT * FROM library_entries
            WHERE name LIKE ? ESCAPE '!' COLLATE NOCASE OR title LIKE ? ESCAPE '!' COLLATE NOCASE
               OR artist LIKE ? ESCAPE '!' COLLATE NOCASE OR album LIKE ? ESCAPE '!' COLLATE NOCASE
               OR genre LIKE ? ESCAPE '!' COLLATE NOCASE
            ORDER BY name COLLATE NOCASE,node_id LIMIT ?
            """.trimIndent(),
            listOf(pattern, pattern, pattern, pattern, pattern, limit.coerceIn(1, 500)),
        )
    }

    @Synchronized
    fun beginScan(providerOnline: Boolean, mountOnline: Boolean): Long {
        val generation = status().generation + 1
        updateStatus("scanning", generation, System.currentTimeMillis(), null, 0, 0, 0, providerOnline, mountOnline, null)
        return generation
    }

    @Synchronized
    fun completeScan(generation: Long, scanned: Long, changed: Long, errors: Long, providerOnline: Boolean, message: String?) {
        val started = status().startedAtEpochMillis
        updateStatus("ready", generation, started, System.currentTimeMillis(), scanned, changed, errors, providerOnline, true, message)
    }

    @Synchronized
    fun failScan(generation: Long, providerOnline: Boolean, mountOnline: Boolean, message: String) {
        val current = status()
        updateStatus("failed", generation, current.startedAtEpochMillis, System.currentTimeMillis(), current.scannedFiles, current.changedFiles, current.errors + 1, providerOnline, mountOnline, message.take(300))
    }

    @Synchronized
    fun status(): ScanStatus = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT state,generation,started_ms,completed_ms,scanned_files,changed_files,errors,provider_online,mount_online,message FROM scan_status WHERE singleton=1").use { rows ->
            check(rows.next())
            ScanStatus(rows.getString(1), rows.getLong(2), rows.nullableLong(3), rows.nullableLong(4), rows.getLong(5), rows.getLong(6), rows.getLong(7), rows.getBoolean(8), rows.getBoolean(9), rows.getString(10))
        }
    }

    private fun updateStatus(state: String, generation: Long, started: Long?, completed: Long?, scanned: Long, changed: Long, errors: Long, providerOnline: Boolean, mountOnline: Boolean, message: String?) {
        connection.prepareStatement(
            "UPDATE scan_status SET state=?,generation=?,started_ms=?,completed_ms=?,scanned_files=?,changed_files=?,errors=?,provider_online=?,mount_online=?,message=? WHERE singleton=1",
        ).use {
            it.setString(1, state); it.setLong(2, generation); it.setNullableLong(3, started); it.setNullableLong(4, completed)
            it.setLong(5, scanned); it.setLong(6, changed); it.setLong(7, errors); it.setBoolean(8, providerOnline); it.setBoolean(9, mountOnline); it.setString(10, message); it.executeUpdate()
        }
    }

    private fun queryOne(sql: String, value: String): CatalogEntry? = connection.prepareStatement(sql).use { query ->
        query.setString(1, value)
        query.executeQuery().use { rows -> if (rows.next()) rows.toEntry() else null }
    }

    private fun queryMany(sql: String, args: List<Any>): List<CatalogEntry> = connection.prepareStatement(sql).use { query ->
        args.forEachIndexed { index, value ->
            when (value) {
                is String -> query.setString(index + 1, value)
                is Int -> query.setInt(index + 1, value)
                is Long -> query.setLong(index + 1, value)
                else -> error("unsupported query value")
            }
        }
        query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toEntry()) } }
    }

    private fun java.sql.ResultSet.toEntry() = CatalogEntry(
        nodeId = getString("node_id"), parentNodeId = getString("parent_node_id"), path = getString("path"), name = getString("name"), kind = getString("kind"),
        sizeBytes = nullableLong("size_bytes"), modifiedAtEpochMillis = nullableLong("modified_ms"), contentHash = getString("content_hash"), providerFileId = nullableLong("provider_file_id"), providerFolderId = nullableLong("provider_folder_id"),
        contentType = getString("content_type"), title = getString("title"), artist = getString("artist"), album = getString("album"), albumArtist = getString("album_artist"), genre = getString("genre"), year = getString("year"),
        trackNumber = nullableInt("track_number"), discNumber = nullableInt("disc_number"), durationMillis = nullableLong("duration_ms"), sampleRate = nullableInt("sample_rate"), channels = nullableInt("channels"), bitDepth = nullableInt("bit_depth"),
        format = getString("format"), metadataError = getString("metadata_error"), scanGeneration = getLong("scan_generation"),
    )

    private fun CatalogEntry.bind(statement: java.sql.PreparedStatement) {
        statement.setString(1, nodeId); statement.setString(2, parentNodeId); statement.setString(3, path); statement.setString(4, name); statement.setString(5, kind)
        statement.setNullableLong(6, sizeBytes); statement.setNullableLong(7, modifiedAtEpochMillis); statement.setString(8, contentHash); statement.setNullableLong(9, providerFileId); statement.setNullableLong(10, providerFolderId)
        statement.setString(11, contentType); statement.setString(12, title); statement.setString(13, artist); statement.setString(14, album); statement.setString(15, albumArtist); statement.setString(16, genre); statement.setString(17, year)
        statement.setNullableInt(18, trackNumber); statement.setNullableInt(19, discNumber); statement.setNullableLong(20, durationMillis); statement.setNullableInt(21, sampleRate); statement.setNullableInt(22, channels); statement.setNullableInt(23, bitDepth)
        statement.setString(24, format); statement.setString(25, metadataError); statement.setLong(26, scanGeneration)
    }

    private fun <T> transaction(block: () -> T): T {
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        return try { block().also { connection.commit() } } catch (error: Throwable) { connection.rollback(); throw error } finally { connection.autoCommit = autoCommit }
    }

    override fun close() = connection.close()
}

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) = if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) = if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
private fun java.sql.ResultSet.nullableLong(index: Int): Long? = getLong(index).let { if (wasNull()) null else it }
private fun java.sql.ResultSet.nullableLong(name: String): Long? = getLong(name).let { if (wasNull()) null else it }
private fun java.sql.ResultSet.nullableInt(name: String): Int? = getInt(name).let { if (wasNull()) null else it }
