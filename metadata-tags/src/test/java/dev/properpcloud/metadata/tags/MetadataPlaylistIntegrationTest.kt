package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.ApplyResultStatus
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.FolderMetadataLookup
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.SnapshotGeneration
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagField
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MetadataPlaylistIntegrationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `folder tags apply verify then drive portable playlist playback order`() = runBlocking {
        val library = temporary.newFolder("library")
        val album = File(library, "Artist/Album").apply { mkdirs() }
        listOf("10 - Ten.wav", "2 - Two.wav", "01 - One.wav").forEach { name ->
            File(album, name).apply(::writeSilentWave)
        }

        val toolkit = JAudioTaggerToolkit()
        val scanner = FolderTagScanner(toolkit).also { it.addAuthorizedRoot(library) }
        val workflow = FolderAutoTagWorkflow(
            scanner = scanner,
            lookup = FolderMetadataLookup { _, _ -> emptyList() },
            applyService = FolderTagApplyService(toolkit),
        )
        val sourceId = SourceId("integration-local")

        val preview = workflow.previewTree(
            FolderTreeTagPreviewCommand(
                directory = album,
                sourceId = sourceId,
                generation = SnapshotGeneration(100),
            ),
        )
        val snapshot = preview.snapshots.single()
        assertEquals(listOf("01 - One.wav", "2 - Two.wav", "10 - Ten.wav"), snapshot.files.map { it.identity.filename })

        val approvals = snapshot.files.map { row ->
            workflow.approveLocalProposals(
                ApproveLocalProposalsCommand(
                    snapshot = snapshot,
                    nodeId = row.identity.nodeId,
                    acceptedRuleByField = mapOf(
                        TagField.TITLE to TagProposalEngine.RULE_PARSE_FILENAME,
                        TagField.ARTIST to TagProposalEngine.RULE_INFER_ARTIST_FOLDER,
                        TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER,
                        TagField.TRACK_NUMBER to TagProposalEngine.RULE_SEQUENCE_TRACKS,
                        TagField.TRACK_TOTAL to TagProposalEngine.RULE_SEQUENCE_TRACKS,
                    ),
                ),
            )
        }
        val tagPlan = workflow.planBatch(preview, approvals)

        val dryRun = workflow.executeBatchPlan(
            plan = tagPlan,
            stagingDirectory = album,
            dryRun = true,
        )
        assertTrue(dryRun.preflight.all { it.ready })
        assertTrue(dryRun.results.isEmpty())

        val applied = workflow.executeBatchPlan(
            plan = tagPlan,
            stagingDirectory = album,
            dryRun = false,
            confirmWrite = true,
        )
        assertEquals(3, applied.results.size)
        assertTrue(applied.results.all { it.status == ApplyResultStatus.VERIFIED })

        val verified = workflow.preview(
            FolderTagPreviewCommand(
                directory = album,
                sourceId = sourceId,
                generation = SnapshotGeneration(200),
            ),
        )
        val verifiedByName = verified.files.associateBy { it.identity.filename }
        listOf(
            "01 - One.wav" to Triple("One", "1", "3"),
            "2 - Two.wav" to Triple("Two", "2", "3"),
            "10 - Ten.wav" to Triple("Ten", "3", "3"),
        ).forEach { (name, expected) ->
            val fields = verifiedByName.getValue(name).originalSnapshot.fields
            assertEquals(expected.first, fields[TagField.TITLE]?.value)
            assertEquals("Artist", fields[TagField.ARTIST]?.value)
            assertEquals("Album", fields[TagField.ALBUM]?.value)
            assertEquals(expected.second, fields[TagField.TRACK_NUMBER]?.value)
            assertEquals(expected.third, fields[TagField.TRACK_TOTAL]?.value)
        }

        val playlist = workflow.writePlaylist(
            workflow.planPlaylist(
                FolderPlaylistWriteCommand(
                    snapshot = verified,
                    order = FolderPlaylistOrder.TAG_TRACK_NUMBER,
                ),
            ),
        )
        val relativeEntries = playlist.file.readLines()
            .filterNot { line -> line.isBlank() || line.startsWith("#") }
        assertEquals(
            listOf("./01 - One.wav", "./2 - Two.wav", "./10 - Ten.wav"),
            relativeEntries,
        )
        assertTrue(playlist.file.readText().contains("#EXTINF:2,Artist - One"))

        val playbackQueue = PlaybackQueue(
            entries = relativeEntries.mapIndexed { index, relative ->
                val filename = relative.removePrefix("./")
                val row = verifiedByName.getValue(filename)
                QueueEntry(
                    AudioTrack(
                        sourceId = sourceId,
                        id = row.identity.nodeId,
                        parentId = NodeId("integration-album"),
                        name = filename,
                        trackNumber = index + 1,
                        taggedTitle = row.originalSnapshot.fields[TagField.TITLE]?.value,
                        durationMillis = row.originalSnapshot.durationMillis,
                    ),
                )
            },
            currentIndex = 0,
        )
        assertEquals(
            listOf("One", "Two", "Ten"),
            playbackQueue.entries.map { it.track.taggedTitle },
        )
        assertEquals("One", playbackQueue.current?.track?.taggedTitle)
    }

    private fun writeSilentWave(file: File) {
        val sampleRate = 8_000
        // Two seconds keeps duration evidence deterministic and exercises real EXTINF output.
        val samples = ByteArray(sampleRate * 2 * 2)
        DataOutputStream(FileOutputStream(file)).use { out ->
            fun ascii(value: String) = out.writeBytes(value)
            fun littleInt(value: Int) {
                out.writeByte(value and 0xff)
                out.writeByte(value ushr 8 and 0xff)
                out.writeByte(value ushr 16 and 0xff)
                out.writeByte(value ushr 24 and 0xff)
            }
            fun littleShort(value: Int) {
                out.writeByte(value and 0xff)
                out.writeByte(value ushr 8 and 0xff)
            }
            ascii("RIFF")
            littleInt(36 + samples.size)
            ascii("WAVE")
            ascii("fmt ")
            littleInt(16)
            littleShort(1)
            littleShort(1)
            littleInt(sampleRate)
            littleInt(sampleRate * 2)
            littleShort(2)
            littleShort(16)
            ascii("data")
            littleInt(samples.size)
            out.write(samples)
        }
    }
}
