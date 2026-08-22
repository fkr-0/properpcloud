package dev.properpcloud.desktop.data

import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.metadata.tags.LocalFolderRootCapability
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopLocalFolderAudioSourceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `filesystem identity is stable opaque and shared by browse plus playback`() = runTest {
        val root = temporary.newFolder("selected-library")
        val album = File(root, "Album").apply { mkdirs() }
        File(root, "10-track.mp3").writeText("ten")
        File(root, "2-track.mp3").writeText("two")
        File(root, "notes.txt").writeText("not audio")
        val nested = File(album, "01-nested.flac").apply { writeText("nested") }

        val firstIdentity = DesktopLocalFilesystemIdentity.forSelectedRoot(root)
        val secondIdentity = DesktopLocalFilesystemIdentity.forSelectedRoot(root)
        assertEquals(firstIdentity.sourceId, secondIdentity.sourceId)
        assertEquals(firstIdentity.nodeId(nested, false), secondIdentity.nodeId(nested, false))
        assertFalse(firstIdentity.sourceId.value.contains(root.absolutePath))
        assertFalse(firstIdentity.nodeId(nested, false).value.contains(root.absolutePath))

        val capability = LocalFolderRootCapability.open(root, firstIdentity.sourceId)
        val source = DesktopLocalFolderAudioSource(capability, firstIdentity)
        val rootNodes = source.list(source.root.id)

        assertEquals(listOf("Album", "2-track.mp3", "10-track.mp3"), rootNodes.map { it.name })
        assertTrue(rootNodes.first() is AudioFolder)
        assertTrue(rootNodes.drop(1).all { it is AudioTrack })
        assertFalse(rootNodes.any { it.name == "notes.txt" })

        val track = rootNodes.filterIsInstance<AudioTrack>().first()
        val stream = source.resolveStream(track.id)
        assertTrue(stream.url.startsWith("file:"))
        assertEquals(File(root, track.name).canonicalFile, File(URI(stream.url)).canonicalFile)
        assertEquals(null, stream.expiresAtEpochMillis)

        val inspection = source.inspect(track.id).fields
        assertEquals("Local folder", inspection["sourceType"])
        assertEquals(track.id.value, inspection["nodeId"])
        assertFalse(inspection.values.any { it.contains(root.absolutePath) })
    }

    @Test
    fun `browse rejects symlink escapes and unsupported files`() = runTest {
        val root = temporary.newFolder("symlink-library")
        val outside = temporary.newFolder("outside")
        File(root, "inside.mp3").writeText("inside")
        File(outside, "outside.mp3").writeText("outside")
        val link = File(root, "escape")
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        }.getOrDefault(false)

        val identity = DesktopLocalFilesystemIdentity.forSelectedRoot(root)
        val source = DesktopLocalFolderAudioSource(LocalFolderRootCapability.open(root, identity.sourceId), identity)
        val names = source.list(source.root.id).map { it.name }

        assertEquals(listOf("inside.mp3"), names)
        if (symlinkCreated) assertFalse(names.contains("escape"))
    }
}
