package dev.properpcloud.desktop

import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.core.model.TagSnapshot
import dev.properpcloud.desktop.metadata.DesktopLocalFolderBinding
import dev.properpcloud.metadata.tags.AudioTagToolkit
import dev.properpcloud.metadata.tags.FolderPlaylistOrder
import dev.properpcloud.metadata.tags.LocalFolderChangeBatch
import dev.properpcloud.metadata.tags.LocalFolderChangeEvent
import dev.properpcloud.metadata.tags.LocalFolderChangeObserver
import dev.properpcloud.metadata.tags.LocalFolderChangeObserverFactory
import dev.properpcloud.metadata.tags.LocalFolderRootCapability
import dev.properpcloud.metadata.tags.StagedTagResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistCliTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `parser exposes every playlist order and keeps writes opt in`() {
        val root = temporary.newFolder("orders")
        val expected = mapOf(
            "filename" to FolderPlaylistOrder.NATURAL_FILENAME,
            "disc-track" to FolderPlaylistOrder.TAG_TRACK_NUMBER,
            "tagged-title" to FolderPlaylistOrder.TAGGED_TITLE,
            "title-number" to FolderPlaylistOrder.TITLE_NUMBER,
            "modification-time" to FolderPlaylistOrder.MODIFICATION_TIME,
        )

        expected.forEach { (name, order) ->
            val parsed = parsePlaylistCliOptions(
                arrayOf("--generate-playlists", root.path, "--order", name),
            )
            assertEquals(order, parsed.order)
            assertFalse(parsed.write)
        }
    }

    @Test
    fun `recursive album CLI previews before explicit write and never stages tags`() {
        val root = temporary.newFolder("library")
        val album = File(root, "Artist/Album").apply { mkdirs() }
        val discOne = File(album, "Disc 1").apply { mkdirs() }
        val discTwo = File(album, "Disc 2").apply { mkdirs() }
        File(discOne, "2 Second.mp3").writeText("second")
        File(discOne, "10 Tenth.mp3").writeText("tenth")
        File(discTwo, "1 Later.mp3").writeText("later")
        val toolkit = RecordingToolkit()
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val bindingFactory: (File, Boolean) -> DesktopLocalFolderBinding = { selectedRoot, recursive ->
            DesktopLocalFolderBinding.createSelected(
                selectedRoot,
                recursive = recursive,
                toolkit = toolkit,
                observerFactory = NoopObserverFactory,
            )
        }
        val common = arrayOf(
            "--generate-playlists",
            root.path,
            "--recursive",
            "--one-per-album",
            "--order",
            "filename",
        )

        val previewExit = runPlaylistCli(common, output::add, errors::add, bindingFactory)

        assertEquals(0, previewExit)
        assertTrue(errors.isEmpty())
        assertTrue(output.any { "Preview only" in it })
        assertTrue(output.any { it == "Target: ./Artist/Album/Artist - Album.m3u8" })
        assertTrue("#EXTM3U" in output)
        assertTrue("./Disc 2/1 Later.mp3" in output)
        assertTrue("./Disc 1/2 Second.mp3" in output)
        assertTrue("./Disc 1/10 Tenth.mp3" in output)
        assertTrue(root.walkTopDown().none { it.extension.equals("m3u8", ignoreCase = true) })
        assertEquals(0, toolkit.stagePatchCalls)

        output.clear()
        val writeExit = runPlaylistCli(common + "--write", output::add, errors::add, bindingFactory)

        assertEquals(0, writeExit)
        assertTrue(errors.isEmpty())
        val playlists = root.walkTopDown().filter { it.extension.equals("m3u8", ignoreCase = true) }.toList()
        assertEquals(1, playlists.size)
        val entries = playlists.single().readLines().filterNot { it.isBlank() || it.startsWith("#") }
        assertEquals(
            listOf("./Disc 2/1 Later.mp3", "./Disc 1/2 Second.mp3", "./Disc 1/10 Tenth.mp3"),
            entries,
        )
        assertTrue(output.any { it.startsWith("Wrote 1 reviewed playlist") })
        assertEquals(0, toolkit.stagePatchCalls)
    }

    @Test
    fun `title-number CLI prints exact numeric review and writes only after explicit confirmation`() {
        val root = temporary.newFolder("title-number-cli")
        File(root, "z-ten.mp3").writeText("title=10 Tenth")
        File(root, "z-one.mp3").writeText("title=01 First")
        File(root, "z-two.mp3").writeText("title=2 Second")
        val toolkit = RecordingToolkit()
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val bindingFactory: (File, Boolean) -> DesktopLocalFolderBinding = { selectedRoot, recursive ->
            DesktopLocalFolderBinding.createSelected(
                selectedRoot,
                recursive = recursive,
                toolkit = toolkit,
                observerFactory = NoopObserverFactory,
            )
        }
        val command = arrayOf(
            "--generate-playlists",
            root.path,
            "--order",
            "title-number",
        )

        assertEquals(0, runPlaylistCli(command, output::add, errors::add, bindingFactory))
        assertTrue(errors.isEmpty())
        assertTrue(output.first().contains("order=title-number"))
        val reviewedEntries = output.filter { it.startsWith("./") }
        assertEquals(listOf("./z-one.mp3", "./z-two.mp3", "./z-ten.mp3"), reviewedEntries)
        assertTrue(root.walkTopDown().none { it.extension.equals("m3u8", ignoreCase = true) })

        output.clear()
        assertEquals(0, runPlaylistCli(command + "--write", output::add, errors::add, bindingFactory))
        val playlist = root.walkTopDown().single { it.extension.equals("m3u8", ignoreCase = true) }
        assertEquals(
            listOf("./z-one.mp3", "./z-two.mp3", "./z-ten.mp3"),
            playlist.readLines().filter { it.startsWith("./") },
        )
        assertEquals(0, toolkit.stagePatchCalls)
    }

    @Test
    fun `album grouping cannot silently widen a direct folder command`() {
        val root = temporary.newFolder("direct")
        val errors = mutableListOf<String>()

        val exit = runPlaylistCli(
            arrayOf("--generate-playlists", root.path, "--one-per-album"),
            output = {},
            errorOutput = errors::add,
        )

        assertEquals(2, exit)
        assertTrue(errors.first().contains("requires --recursive"))
    }

    private class RecordingToolkit : AudioTagToolkit {
        var stagePatchCalls = 0

        override fun inspect(file: File): TagSnapshot {
            val track = file.name.takeWhile(Char::isDigit).toIntOrNull()
            val disc = Regex("(?i)disc\\s*(\\d+)").matchEntire(file.parentFile.name)?.groupValues?.get(1)?.toIntOrNull()
            val embeddedTitle = file.readText()
                .lineSequence()
                .firstOrNull { it.startsWith("title=") }
                ?.removePrefix("title=")
                ?.takeIf(String::isNotBlank)
                ?: file.nameWithoutExtension
            return TagSnapshot(
                format = "test-id3",
                fields = buildMap {
                    put(TagField.TITLE, MetadataValue(embeddedTitle, MetadataProvenance.EMBEDDED))
                    put(TagField.ALBUM, MetadataValue(file.parentFile.parentFile.name, MetadataProvenance.EMBEDDED))
                    put(TagField.ARTIST, MetadataValue("Artist", MetadataProvenance.EMBEDDED))
                    track?.let { put(TagField.TRACK_NUMBER, MetadataValue(it.toString(), MetadataProvenance.EMBEDDED)) }
                    disc?.let { put(TagField.DISC_NUMBER, MetadataValue(it.toString(), MetadataProvenance.EMBEDDED)) }
                },
            )
        }

        override fun stagePatch(
            source: File,
            stagingDirectory: File,
            patch: TagPatch,
            expectedSourceSha256: String?,
        ): StagedTagResult {
            stagePatchCalls += 1
            error("playlist CLI must never stage tag bytes")
        }
    }

    private object NoopObserverFactory : LocalFolderChangeObserverFactory {
        override fun open(
            capability: LocalFolderRootCapability,
            recursive: Boolean,
            quietWindowMillis: Long,
            maximumCoalescingLatencyMillis: Long,
        ): LocalFolderChangeObserver = object : LocalFolderChangeObserver {
            override fun start(
                onEvent: (LocalFolderChangeEvent) -> Unit,
                onBatch: (LocalFolderChangeBatch) -> Unit,
            ) = Unit

            override fun close() = Unit
        }
    }
}
