package dev.fkr.properpcloud.desktop.data

import dev.fkr.properpcloud.core.model.AudioFolder
import dev.fkr.properpcloud.core.model.AudioTrack
import dev.fkr.properpcloud.core.model.LibraryFile
import dev.fkr.properpcloud.core.model.LibraryFileKind
import dev.fkr.properpcloud.metadata.tags.LocalFolderRootCapability
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
        File(root, "mix.m3u8").writeText("#EXTM3U")
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

        assertEquals(listOf("Album", "2-track.mp3", "10-track.mp3", "mix.m3u8", "notes.txt"), rootNodes.map { it.name })
        assertTrue(rootNodes.first() is AudioFolder)
        assertEquals(2, rootNodes.filterIsInstance<AudioTrack>().size)
        assertEquals(LibraryFileKind.PLAYLIST, rootNodes.filterIsInstance<LibraryFile>().single { it.name == "mix.m3u8" }.kind)
        assertEquals(LibraryFileKind.GENERIC, rootNodes.filterIsInstance<LibraryFile>().single { it.name == "notes.txt" }.kind)

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
    fun `webm and matroska extensions are admitted by desktop playback policy`() = runTest {
        val root = temporary.newFolder("container-library")
        listOf("audio.webm", "audio.mka", "video-container.mkv").forEach { File(root, it).writeBytes(byteArrayOf(1, 2, 3)) }
        File(root, "document.txt").writeText("not media")
        File(root, "unknown.bin").writeBytes(byteArrayOf(0))

        assertTrue(DESKTOP_AUDIO_PLAYABLE_EXTENSIONS.containsAll(setOf("webm", "mka", "mkv")))

        val identity = DesktopLocalFilesystemIdentity.forSelectedRoot(root)
        val source = DesktopLocalFolderAudioSource(LocalFolderRootCapability.open(root, identity.sourceId), identity)
        val nodes = source.list(source.root.id)
        val tracks = nodes.filterIsInstance<AudioTrack>().associateBy { it.name }

        assertEquals(setOf("audio.webm", "audio.mka", "video-container.mkv"), tracks.keys)
        assertEquals("audio/webm", tracks.getValue("audio.webm").contentType)
        assertEquals("audio/x-matroska", tracks.getValue("audio.mka").contentType)
        assertEquals("audio/x-matroska", tracks.getValue("video-container.mkv").contentType)
        assertTrue(nodes.filterIsInstance<LibraryFile>().any { it.name == "document.txt" })
        assertTrue(nodes.filterIsInstance<LibraryFile>().any { it.name == "unknown.bin" })
    }

    @Test
    fun `playable container metadata is not required for queue admission`() = runTest {
        val root = temporary.newFolder("metadata-free-container")
        val file = File(root, "voice.webm").apply { writeBytes(byteArrayOf(0x1, 0x2)) }
        val identity = DesktopLocalFilesystemIdentity.forSelectedRoot(root)
        val source = DesktopLocalFolderAudioSource(LocalFolderRootCapability.open(root, identity.sourceId), identity)

        val track = source.list(source.root.id).filterIsInstance<AudioTrack>().single()

        assertEquals(file.name, track.name)
        assertEquals("voice", track.filenameStem)
        assertEquals(null, track.durationMillis)
        assertEquals("audio/webm", track.contentType)
    }

    @Test
    fun `browse rejects symlink escapes`() = runTest {
        val root = temporary.newFolder("symlink-library")
        val outside = temporary.newFolder("outside")
        File(root, "inside.mp3").writeText("inside")
        File(outside, "outside.mp3").writeText("outside")
        File(outside, "outside.webm").writeText("outside")
        val link = File(root, "escape")
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        }.getOrDefault(false)

        val identity = DesktopLocalFilesystemIdentity.forSelectedRoot(root)
        val source = DesktopLocalFolderAudioSource(LocalFolderRootCapability.open(root, identity.sourceId), identity)
        val names = source.list(source.root.id).map { it.name }

        assertEquals(listOf("inside.mp3"), names)
        if (symlinkCreated) {
            assertFalse(names.contains("escape"))
            val fileLink = File(root, "escape.webm")
            Files.createSymbolicLink(fileLink.toPath(), File(outside, "outside.webm").toPath())
            assertFalse(source.list(source.root.id).any { it.name == "escape.webm" })
        }
    }
}
