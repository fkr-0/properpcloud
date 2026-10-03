package dev.properpcloud.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test
    fun `unmounted backing directory retains the last good catalog`() {
        val scratch = testDirectory("mount-loss-")
        try {
            val mount = scratch.resolve("mount")
            Files.createDirectories(mount)
            Files.writeString(mount.resolve("keep.txt"), "last-good")

            CatalogRepository(scratch.resolve("library.db")).use { repository ->
                val first = LibraryScanner(mount, repository).scan()
                val retained = repository.existingByPath("keep.txt")
                assertEquals(1L, first.scannedFiles)
                assertNotNull(retained)

                val offlineScanner = LibraryScanner(
                    mount,
                    repository,
                    mountState = MountStateProbe { false },
                )
                assertThrows(IllegalStateException::class.java) { offlineScanner.scan() }

                assertEquals(retained, repository.existingByPath("keep.txt"))
                assertEquals("failed", repository.status().state)
                assertFalse(repository.status().mountOnline)
            }
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cached provider ids remain stable when provider refresh is unavailable`() {
        val scratch = testDirectory("cached-provider-")
        try {
            val mount = scratch.resolve("mount")
            Files.createDirectories(mount.resolve("Artist"))
            Files.writeString(mount.resolve("Artist/track.txt"), "provider-cached")

            CatalogRepository(scratch.resolve("library.db")).use { repository ->
                repository.replaceProviderEntries(
                    listOf(
                        PCloudProviderEntry(path = "", name = "pCloud", isFolder = true, folderId = 0),
                        PCloudProviderEntry(path = "Artist", name = "Artist", isFolder = true, folderId = 7, parentFolderId = 0),
                        PCloudProviderEntry(path = "Artist/track.txt", name = "track.txt", isFolder = false, fileId = 42, parentFolderId = 7),
                    ),
                )

                val first = LibraryScanner(mount, repository).scan()
                val cached = repository.existingByPath("Artist/track.txt")
                assertFalse(first.providerOnline)
                assertEquals("catalog:pcloud:file:42", cached?.nodeId)
                assertEquals("catalog:pcloud:folder:7", cached?.parentNodeId)
                assertEquals(42L, cached?.providerFileId)

                val second = LibraryScanner(mount, repository).scan()
                assertEquals(0L, second.changedFiles)
                assertEquals(cached?.nodeId, repository.existingByPath("Artist/track.txt")?.nodeId)
            }
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    @Test
    fun `plain readable directory is not accepted as a mounted production root`() {
        val scratch = testDirectory("mount-probe-")
        try {
            assertTrue(Files.isReadable(scratch))
            assertFalse(MountStateProbe.mountedDirectory(scratch).isOnline())
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
