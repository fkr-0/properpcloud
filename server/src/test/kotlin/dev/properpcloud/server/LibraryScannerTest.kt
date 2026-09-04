package dev.properpcloud.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class LibraryScannerTest {
    @Test
    fun `second scan reuses unchanged audio and duplicate fingerprints remain queryable`() {
        val scratch = testDirectory("scanner-")
        try {
            val mount = scratch.resolve("mount")
            val album = mount.resolve("Artist/Album")
            Files.createDirectories(album)
            val bytes = "intentionally malformed but stable audio bytes".toByteArray()
            Files.write(album.resolve("one.mp3"), bytes)
            Files.write(album.resolve("copy.mp3"), bytes)

            CatalogRepository(scratch.resolve("library.db")).use { repository ->
                val scanner = LibraryScanner(mount, repository)
                val first = scanner.scan()
                assertEquals(2L, first.scannedFiles)
                assertEquals(2L, first.changedFiles)
                assertEquals(2L, first.errors)

                val firstEntry = repository.existingByPath("Artist/Album/one.mp3")
                assertEquals("audio", firstEntry?.kind)
                assertNotNull(firstEntry?.contentHash)
                assertNotNull(firstEntry?.metadataError)
                assertEquals(2, repository.duplicateGroups().getValue(firstEntry!!.contentHash!!).size)

                val second = scanner.scan()
                assertEquals(2L, second.scannedFiles)
                assertEquals(0L, second.changedFiles)
                assertEquals(0L, second.errors)
                assertEquals(firstEntry.contentHash, repository.existingByPath("Artist/Album/one.mp3")?.contentHash)
            }
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    private fun testDirectory(prefix: String): Path {
        val parent = Path.of("build", "test-tmp")
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, prefix)
    }
}
