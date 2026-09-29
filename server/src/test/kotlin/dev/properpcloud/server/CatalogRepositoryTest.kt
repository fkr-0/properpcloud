package dev.properpcloud.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class CatalogRepositoryTest {
    @Test
    fun `existing catalog gains bitrate column without losing rows`() {
        val root = testDirectory("catalog-migration-")
        val database = root.resolve("library.db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE library_entries (
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
                    statement.execute(
                        "INSERT INTO library_entries(node_id,path,name,kind,scan_generation) VALUES('old','old.mp3','old.mp3','audio',1)",
                    )
                }
            }

            CatalogRepository(database).use { repository ->
                assertEquals("old", repository.existingByPath("old.mp3")?.nodeId)
                assertEquals(null, repository.existingByPath("old.mp3")?.bitrateKbps)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `catalog persists browse search and duplicate hash evidence`() {
        val root = testDirectory("catalog-")
        try {
            CatalogRepository(root.resolve("library.db")).use { repository ->
                val generation = repository.beginScan(providerOnline = false, mountOnline = true)
                repository.upsert(CatalogEntry("catalog:root", null, "", "Server library", "folder", scanGeneration = generation))
                repository.upsert(CatalogEntry("folder:1", "catalog:root", "Artist", "Artist", "folder", scanGeneration = generation))
                repository.upsert(
                    CatalogEntry(
                        nodeId = "file:1", parentNodeId = "folder:1", path = "Artist/song.flac", name = "song.flac",
                        kind = "audio", contentHash = "abc", artist = "Artist", title = "Needle Song", bitrateKbps = 128,
                        scanGeneration = generation,
                    ),
                )
                repository.upsert(
                    CatalogEntry(
                        nodeId = "file:2", parentNodeId = "folder:1", path = "Artist/copy.flac", name = "copy.flac",
                        kind = "audio", contentHash = "abc", artist = "Artist", title = "Copy", scanGeneration = generation,
                    ),
                )
                repository.completeScan(generation, 1, 1, 0, false, "offline metadata")
                assertEquals(listOf("folder:1"), repository.browse("catalog:root").map { it.nodeId })
                assertEquals(listOf("file:1"), repository.search("needle").map { it.nodeId })
                assertEquals("abc", repository.findByNodeId("file:1")?.contentHash)
                assertEquals(128, repository.findByNodeId("file:1")?.bitrateKbps)
                assertEquals(setOf("file:1", "file:2"), repository.duplicateGroups().getValue("abc").map { it.nodeId }.toSet())
                assertEquals("ready", repository.status().state)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `provider stable id survives path replacement`() {
        val root = testDirectory("rename-")
        try {
            CatalogRepository(root.resolve("library.db")).use { repository ->
                repository.upsert(CatalogEntry("catalog:pcloud:file:42", "catalog:root", "old.flac", "old.flac", "audio"))
                repository.upsert(CatalogEntry("catalog:pcloud:file:42", "catalog:root", "new.flac", "new.flac", "audio"))
                assertNotNull(repository.findByNodeId("catalog:pcloud:file:42"))
                assertTrue(repository.existingByPath("old.flac") == null)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun testDirectory(prefix: String): Path {
        val parent = Path.of("build", "test-tmp")
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, prefix)
    }
}
