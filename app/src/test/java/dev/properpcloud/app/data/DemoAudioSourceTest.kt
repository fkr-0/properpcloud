package dev.properpcloud.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DemoAudioSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun demoLibraryContainsNestedFolderFirstContent() = runTest {
        val source = DemoAudioSource(context)
        val rootChildren = source.list(source.root.id)
        assertEquals(listOf("Audiobooks", "Field recordings", "Numbered tracks"), rootChildren.map { it.name })
        assertTrue(rootChildren.all { it is AudioFolder })
    }

    @Test
    fun demoStreamGeneratesAValidLocalWav() = runTest {
        val source = DemoAudioSource(context)
        val musicFolder = source.list(source.root.id).filterIsInstance<AudioFolder>().first { it.name == "Numbered tracks" }
        val track = source.list(musicFolder.id).filterIsInstance<AudioTrack>().first()
        val stream = source.resolveStream(track.id)
        val file = File(requireNotNull(android.net.Uri.parse(stream.url).path))
        assertTrue(file.exists())
        assertEquals("RIFF", file.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) })
    }
}
