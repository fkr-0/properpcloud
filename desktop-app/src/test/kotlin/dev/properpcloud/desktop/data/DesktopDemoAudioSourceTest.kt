package dev.properpcloud.desktop.data

import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.FolderQueueAssembler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DesktopDemoAudioSourceTest {
    @Test
    fun `recursive folder queue and local stream are deterministic`() = runTest {
        val root = Files.createTempDirectory("properpcloud-demo-test-")
        try {
            val source = DesktopDemoAudioSource(root)
            val folder = source.list(source.root.id).filterIsInstance<AudioFolder>().first()
            val result = FolderQueueAssembler(source).build(folder.id, recursive = true)
            assertEquals(3, result.entries.size)
            val track: AudioTrack = result.entries.first().track
            val handle = source.resolveStream(track.id)
            assertTrue(handle.url.startsWith("file:"))
            assertTrue(Files.size(java.nio.file.Path.of(java.net.URI(handle.url))) > 44)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
