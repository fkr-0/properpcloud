package dev.properpcloud.app.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.properpcloud.app.data.DemoAudioSource
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagMutation
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.metadata.online.MetadataSearchQuery
import dev.properpcloud.metadata.online.OnlineMetadataProvider
import dev.properpcloud.metadata.tags.JAudioTaggerToolkit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetadataEditingWorkspaceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val toolkit = JAudioTaggerToolkit()

    @Test
    fun demoTrackProducesSeparateVerifiedShareableCandidate() = runTest {
        val source = DemoAudioSource(context)
        val workspace = MetadataEditingWorkspace(
            context = context,
            tagToolkit = toolkit,
            onlineProvider = object : OnlineMetadataProvider {
                override suspend fun search(query: MetadataSearchQuery, limit: Int) = emptyList<dev.properpcloud.core.model.MetadataCandidate>()
            },
        )
        val folder = source.list(source.root.id)
            .filterIsInstance<AudioFolder>()
            .first { it.name == "Numbered tracks" }
        val track = source.list(folder.id).filterIsInstance<AudioTrack>().first()

        val loaded = workspace.load(source, track)
        val sourceHashBefore = loaded.prepared.expectedContentHash
        val result = workspace.stage(
            loaded,
            TagPatch(
                mapOf(
                    TagField.TITLE to TagMutation.Set("Verified title"),
                    TagField.ARTIST to TagMutation.Set("properpcloud test artist"),
                ),
            ),
        )
        val artifact = workspace.artifact(result)

        assertTrue(loaded.prepared.localFile.isFile)
        assertTrue(artifact.file.isFile)
        assertNotEquals(loaded.prepared.localFile.absolutePath, artifact.file.absolutePath)
        assertEquals(sourceHashBefore, loaded.prepared.expectedContentHash)
        assertEquals(null, toolkit.inspect(loaded.prepared.localFile).fields[TagField.TITLE])
        assertEquals("Verified title", result.snapshot.fields[TagField.TITLE]?.value)
        assertEquals("properpcloud test artist", result.snapshot.fields[TagField.ARTIST]?.value)
        assertEquals(result.stagedSha256, artifact.sha256)
        assertTrue(artifact.mimeType.startsWith("audio/"))

        workspace.discard(listOf(loaded))
        assertTrue(!loaded.prepared.localFile.exists())
        artifact.file.delete()
    }

    @Test
    fun bundleIncludesManifestAndEveryVerifiedCandidate() = runTest {
        val source = DemoAudioSource(context)
        val workspace = MetadataEditingWorkspace(
            context = context,
            tagToolkit = toolkit,
            onlineProvider = object : OnlineMetadataProvider {
                override suspend fun search(query: MetadataSearchQuery, limit: Int) = emptyList<dev.properpcloud.core.model.MetadataCandidate>()
            },
        )
        val folder = source.list(source.root.id)
            .filterIsInstance<AudioFolder>()
            .first { it.name == "Numbered tracks" }
        val tracks = source.list(folder.id).filterIsInstance<AudioTrack>().take(2)
        val loaded = tracks.map { workspace.load(source, it) }
        val results = loaded.mapIndexed { index, item ->
            workspace.stage(
                item,
                TagPatch(mapOf(TagField.TRACK_NUMBER to TagMutation.Set((index + 1).toString()))),
            )
        }

        val artifact = workspace.bundle(results)
        val entries = java.util.zip.ZipFile(artifact.file).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }

        assertEquals(2, artifact.itemCount)
        assertTrue("metadata-manifest.csv" in entries)
        assertEquals(3, entries.size)
        assertTrue(results.all { !it.stagedFile.exists() })
        workspace.discard(loaded)
        artifact.file.delete()
    }
}
