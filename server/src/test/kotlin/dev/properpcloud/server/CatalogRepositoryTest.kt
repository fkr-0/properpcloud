package dev.properpcloud.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CatalogRepositoryTest {
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
                        kind = "audio", contentHash = "abc", artist = "Artist", title = "Needle Song", scanGeneration = generation,
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
