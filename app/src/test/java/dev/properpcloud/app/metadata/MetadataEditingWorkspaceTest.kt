package dev.properpcloud.app.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.properpcloud.app.data.DemoAudioSource
import dev.properpcloud.core.model.AudioFolder
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagMutation
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.metadata.online.MetadataSearchQuery
import dev.properpcloud.metadata.online.OnlineMetadataProvider
import dev.properpcloud.metadata.tags.JAudioTaggerToolkit
import dev.properpcloud.metadata.tags.FolderPlaylistOrder
import dev.properpcloud.metadata.tags.StagedTagResult
import kotlinx.coroutines.test.runTest
import java.io.File
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
    fun bundlePlaylistTitleNumberOrderMatchesPortableLeadingTitleContract() = runTest {
        val workspace = MetadataEditingWorkspace(
            context = context,
            tagToolkit = toolkit,
            onlineProvider = object : OnlineMetadataProvider {
                override suspend fun search(query: MetadataSearchQuery, limit: Int) = emptyList<dev.properpcloud.core.model.MetadataCandidate>()
            },
        )
        fun staged(name: String, title: String): StagedTagResult {
            val file = File(context.cacheDir, "metadata-test-${System.nanoTime()}-$name").apply { writeText(name) }
            return StagedTagResult(
                stagedFile = file,
                sourceSha256 = "a".repeat(64),
                stagedSha256 = "b".repeat(64),
                snapshot = dev.properpcloud.core.model.TagSnapshot(
                    "ID3",
                    mapOf(
                        TagField.ARTIST to MetadataValue("Artist", MetadataProvenance.EMBEDDED),
                        TagField.ALBUM to MetadataValue("Album", MetadataProvenance.EMBEDDED),
                        TagField.TITLE to MetadataValue(title, MetadataProvenance.EMBEDDED),
                    ),
                ),
                changedFields = setOf(TagField.TITLE),
            )
        }
        val items = listOf(
            MetadataBundleItem("ten.mp3", staged("ten.mp3", "10 Tenth")),
            MetadataBundleItem("one.mp3", staged("one.mp3", "01 First")),
            MetadataBundleItem("two.mp3", staged("two.mp3", "2 Second")),
        )

        val artifact = workspace.bundle(items, includePlaylist = true, playlistOrder = FolderPlaylistOrder.TITLE_NUMBER)
        val playlist = java.util.zip.ZipFile(artifact.file).use { zip ->
            zip.getInputStream(requireNotNull(zip.getEntry("Artist - Album.m3u8"))).bufferedReader().readText()
        }

        assertTrue(playlist.indexOf("./one.mp3") < playlist.indexOf("./two.mp3"))
        assertTrue(playlist.indexOf("./two.mp3") < playlist.indexOf("./ten.mp3"))
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

        val artifact = workspace.bundle(
            results.mapIndexed { index, result -> MetadataBundleItem(tracks[index].name, result) },
            includePlaylist = false,
        )
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

    @Test
    fun bundlePlaylistUsesReviewedNamingDurationAndModificationOrder() = runTest {
        val workspace = MetadataEditingWorkspace(
            context = context,
            tagToolkit = toolkit,
            onlineProvider = object : OnlineMetadataProvider {
                override suspend fun search(query: MetadataSearchQuery, limit: Int) = emptyList<dev.properpcloud.core.model.MetadataCandidate>()
            },
        )
        fun staged(name: String, title: String, durationMillis: Long?): StagedTagResult {
            val file = File(context.cacheDir, "metadata-test-${System.nanoTime()}-$name").apply { writeText(name) }
            return StagedTagResult(
                stagedFile = file,
                sourceSha256 = "a".repeat(64),
                stagedSha256 = "b".repeat(64),
                snapshot = dev.properpcloud.core.model.TagSnapshot(
                    format = "ID3",
                    fields = mapOf(
                        TagField.ARTIST to MetadataValue("Artist", MetadataProvenance.EMBEDDED),
                        TagField.ALBUM to MetadataValue("Album", MetadataProvenance.EMBEDDED),
                        TagField.TITLE to MetadataValue(title, MetadataProvenance.EMBEDDED),
                    ),
                    durationMillis = durationMillis,
                ),
                changedFields = setOf(TagField.TITLE),
            )
        }
        val later = staged("10-later.mp3", "Later", 1_500L)
        val earlier = staged("2-earlier.mp3", "Earlier", null)

        val artifact = workspace.bundle(
            listOf(
                MetadataBundleItem("10-later.mp3", later, modifiedAtEpochMillis = 2_000L),
                MetadataBundleItem("2-earlier.mp3", earlier, modifiedAtEpochMillis = 1_000L),
            ),
            includePlaylist = true,
            playlistOrder = FolderPlaylistOrder.MODIFICATION_TIME,
        )
        val playlist = java.util.zip.ZipFile(artifact.file).use { zip ->
            val entry = requireNotNull(zip.getEntry("Artist - Album.m3u8"))
            zip.getInputStream(entry).bufferedReader().readText()
        }

        assertTrue(playlist.indexOf("./2-earlier.mp3") < playlist.indexOf("./10-later.mp3"))
        assertTrue(playlist.contains("#EXTINF:-1,Artist - Earlier"))
        assertTrue(playlist.contains("#EXTINF:2,Artist - Later"))
        artifact.file.delete()
    }

    @Test
    fun bundlePlaylistNamingRequiresTagAgreementAcrossEveryReviewedExport() = runTest {
        val workspace = MetadataEditingWorkspace(
            context = context,
            tagToolkit = toolkit,
            onlineProvider = object : OnlineMetadataProvider {
                override suspend fun search(query: MetadataSearchQuery, limit: Int) = emptyList<dev.properpcloud.core.model.MetadataCandidate>()
            },
        )
        fun staged(name: String, artist: String?): StagedTagResult {
            val file = File(context.cacheDir, "metadata-test-${System.nanoTime()}-$name").apply { writeText(name) }
            val fields = linkedMapOf<TagField, MetadataValue>(
                TagField.ALBUM to MetadataValue("Album", MetadataProvenance.EMBEDDED),
                TagField.TITLE to MetadataValue(name.substringBeforeLast('.'), MetadataProvenance.EMBEDDED),
            )
            artist?.let { fields[TagField.ARTIST] = MetadataValue(it, MetadataProvenance.EMBEDDED) }
            return StagedTagResult(
                stagedFile = file,
                sourceSha256 = "a".repeat(64),
                stagedSha256 = "b".repeat(64),
                snapshot = dev.properpcloud.core.model.TagSnapshot("ID3", fields),
                changedFields = setOf(TagField.TITLE),
            )
        }

        val artifact = workspace.bundle(
            listOf(
                MetadataBundleItem("01.mp3", staged("01.mp3", "Artist")),
                MetadataBundleItem("02.mp3", staged("02.mp3", null)),
            ),
            includePlaylist = true,
        )
        val entries = java.util.zip.ZipFile(artifact.file).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }

        assertTrue("Album.m3u8" in entries)
        assertTrue("Artist - Album.m3u8" !in entries)
        artifact.file.delete()
    }
}
