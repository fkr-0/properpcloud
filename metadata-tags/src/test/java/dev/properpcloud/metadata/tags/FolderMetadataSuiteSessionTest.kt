package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.FolderMetadataLookup
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.PlaybackQueue
import dev.properpcloud.core.model.QueueEntry
import dev.properpcloud.core.model.QueueOperation
import dev.properpcloud.core.model.QueueReducer
import dev.properpcloud.core.model.SnapshotGeneration
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagMutation
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.core.model.TagSnapshot
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderMetadataSuiteSessionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun directPlaylistRequiresPreviewAndConfirmationAndStaleEvidenceBecomesPresentationError() = runBlocking {
        val album = temporary.newFolder("Album")
        val track = File(album, "01 - One.mp3").apply { writeText("audio-one") }
        val toolkit = RecordingToolkit()
        val session = session(album, toolkit)

        val beforePreview = session.reviewDirectPlaylist()
        assertFalse(beforePreview.succeeded)
        assertTrue(beforePreview.message.contains("Preview"))

        val preview = session.previewTree(command(album, generation = 1)).value
        assertNotNull(preview)
        val review = session.reviewDirectPlaylist(FolderPlaylistOrder.TAG_TRACK_NUMBER)
        assertTrue(review.succeeded)
        assertTrue(review.message.contains("confirm"))

        val rejected = session.materializePlaylist(review.value!!, confirmWrite = false)
        assertFalse(rejected.succeeded)
        assertFalse(File(album, review.value!!.plan.fileName).exists())

        track.appendText("-changed-after-review")
        val stale = session.materializePlaylist(review.value!!, confirmWrite = true)
        assertFalse(stale.succeeded)
        assertTrue(stale.reconciliationRequired)
        assertTrue(stale.message.contains("changed after preview"))
        assertTrue(session.status().reconciliationRequired)
        assertEquals(0, toolkit.stagePatchCalls)
    }

    @Test
    fun recursivePlaylistOptInIsIndependentReportsProgressAndWatcherInvalidationCancelsAutomation() = runBlocking {
        val root = temporary.newFolder("Library")
        val album2 = File(root, "Album 2").apply { mkdirs() }
        val album10 = File(root, "Album 10").apply { mkdirs() }
        File(album2, "01 - Two.mp3").writeText("two")
        File(album10, "01 - Ten.mp3").writeText("ten")
        val toolkit = RecordingToolkit()
        val session = session(root, toolkit)

        val tree = session.previewTree(command(root, generation = 10, recursive = true)).value!!
        val withoutPlaylistOptIn = session.reviewPlaylistBatch(recursivePlaylistOptIn = false)
        assertFalse(withoutPlaylistOptIn.succeeded)
        assertTrue(withoutPlaylistOptIn.message.contains("explicit opt-in"))

        val playlistReview = session.reviewPlaylistBatch(recursivePlaylistOptIn = true)
        assertTrue(playlistReview.succeeded)
        assertTrue(playlistReview.value!!.plan.recursiveOptInConfirmed)

        val albumRow = tree.snapshots.single { it.folderPath == album2.canonicalFile }.files.single()
        val tagApproval = session.approveLocalProposals(
            ApproveLocalProposalsCommand(
                snapshot = tree.snapshots.single { it.folderPath == album2.canonicalFile },
                nodeId = albumRow.identity.nodeId,
                acceptedRuleByField = mapOf(TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER),
            ),
        ).value!!
        val tagWithoutOptIn = session.reviewTagBatch(listOf(tagApproval), recursiveTagOptIn = false)
        assertFalse(tagWithoutOptIn.succeeded)
        assertTrue(tagWithoutOptIn.message.contains("explicit opt-in"))
        assertEquals(0, toolkit.stagePatchCalls)

        val progress = mutableListOf<FolderPlaylistBatchProgress>()
        val materialized = session.materializePlaylistBatch(
            review = playlistReview.value!!,
            confirmWrite = true,
            onProgress = progress::add,
        )
        assertTrue(materialized.succeeded)
        assertEquals(listOf(1, 2), progress.map { it.completed })
        assertTrue(progress.all { it.total == 2 })
        assertEquals(0, toolkit.stagePatchCalls)

        val scheduled = session.schedulePostSyncPlaylistRegeneration(
            key = "library",
            review = playlistReview.value!!,
            nowEpochMillis = 100,
        )
        assertTrue(scheduled.succeeded)
        assertEquals(1, session.status().pendingPlaylistRegenerations)

        val invalidated = session.invalidateForFilesystemChange("Filesystem watcher reported an audio change.")
        assertTrue(invalidated.reconciliationRequired)
        assertEquals(0, invalidated.pendingPlaylistRegenerations)
        val paused = session.flushPostSyncPlaylistRegeneration(nowEpochMillis = 1_000)
        assertFalse(paused.succeeded)
        assertTrue(paused.reconciliationRequired)
        assertEquals(0, toolkit.stagePatchCalls)
    }

    @Test
    fun scanApproveApplyVerifyPlaylistAndQueueOrderStayInOneGuardedLocalContract() = runBlocking {
        val artist = temporary.newFolder("Artist")
        val album = File(artist, "Album").apply { mkdirs() }
        listOf("10 - Ten.mp3", "2 - Two.mp3", "01 - One.mp3").forEach { name ->
            File(album, name).writeText("audio-$name")
        }
        val toolkit = RecordingToolkit()
        val session = session(artist, toolkit)
        val initial = session.previewTree(command(album, generation = 20)).value!!
        val snapshot = initial.snapshots.single()

        val approvals = snapshot.files.map { row ->
            session.approveLocalProposals(
                ApproveLocalProposalsCommand(
                    snapshot = snapshot,
                    nodeId = row.identity.nodeId,
                    acceptedRuleByField = mapOf(TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER),
                ),
            ).value!!
        }
        val tagReview = session.reviewTagBatch(approvals).value!!

        val dryRunProgress = mutableListOf<FolderTagBatchProgress>()
        val dryRun = session.executeTagBatch(
            tagReview,
            stagingDirectory = File(artist, ".phase4-scratch"),
            dryRun = true,
            onProgress = dryRunProgress::add,
        )
        assertTrue(dryRun.succeeded)
        assertFalse(dryRun.reconciliationRequired)
        assertEquals(0, toolkit.stagePatchCalls)
        assertEquals(listOf(1, 2, 3), dryRunProgress.map { it.completed })

        val applied = session.executeTagBatch(
            tagReview,
            stagingDirectory = File(artist, ".phase4-scratch"),
            dryRun = false,
            confirmWrite = true,
        )
        assertTrue(applied.succeeded)
        assertTrue(applied.reconciliationRequired)
        assertEquals(3, toolkit.stagePatchCalls)
        assertTrue(applied.value!!.results.all { it.status == dev.properpcloud.core.model.ApplyResultStatus.VERIFIED })

        val verified = session.reconcile(command(album, generation = 21)).value!!
        assertTrue(verified.snapshots.single().files.all {
            it.originalSnapshot.fields[TagField.ALBUM]?.value == "Album"
        })

        val playlistReview = session.reviewDirectPlaylist(FolderPlaylistOrder.TAG_TRACK_NUMBER).value!!
        val playlist = session.materializePlaylist(playlistReview, confirmWrite = true).value!!.file
        val orderedNames = playlist.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.removePrefix("./") }
        assertEquals(listOf("01 - One.mp3", "2 - Two.mp3", "10 - Ten.mp3"), orderedNames)

        val rowsByName = verified.snapshots.single().files.associateBy { it.identity.filename }
        val queueEntries = orderedNames.map { name ->
            val row = rowsByName.getValue(name)
            QueueEntry(
                AudioTrack(
                    sourceId = row.identity.sourceId,
                    id = row.identity.nodeId,
                    parentId = verified.snapshots.single().folderId,
                    name = name,
                    trackNumber = row.originalSnapshot.fields[TagField.TRACK_NUMBER]?.value?.toIntOrNull(),
                    taggedTitle = row.originalSnapshot.fields[TagField.TITLE]?.value,
                    durationMillis = row.originalSnapshot.durationMillis,
                ),
            )
        }
        val queue = QueueReducer.apply(PlaybackQueue(), QueueOperation.REPLACE, queueEntries)
        assertEquals(orderedNames, queue.entries.map { it.track.name })
        assertEquals("01 - One.mp3", queue.current?.track?.name)
    }

    @Test
    fun stalePostSyncRegenerationReturnsActionableErrorAndNeverStagesTags() = runBlocking {
        val album = temporary.newFolder("Stale Album")
        val track = File(album, "01 - One.mp3").apply { writeText("audio") }
        val toolkit = RecordingToolkit()
        val session = session(album, toolkit)
        session.previewTree(command(album, generation = 30))
        val review = session.reviewPlaylistBatch().value!!
        session.schedulePostSyncPlaylistRegeneration("album", review, nowEpochMillis = 10)
        track.appendText("-external-change")

        val result = session.flushPostSyncPlaylistRegeneration(nowEpochMillis = 1_000)

        assertFalse(result.succeeded)
        assertTrue(result.reconciliationRequired)
        assertTrue(result.message.contains("failed"))
        assertEquals(0, session.status().pendingPlaylistRegenerations)
        assertEquals(0, toolkit.stagePatchCalls)
    }

    private fun session(root: File, toolkit: RecordingToolkit): FolderMetadataSuiteSession {
        val scanner = FolderTagScanner(toolkit).also { it.addAuthorizedRoot(root) }
        val workflow = FolderAutoTagWorkflow(
            scanner = scanner,
            lookup = FolderMetadataLookup { _, _ -> emptyList() },
            applyService = FolderTagApplyService(toolkit),
        )
        return FolderMetadataSuiteSession(workflow)
    }

    private fun command(
        directory: File,
        generation: Long,
        recursive: Boolean = false,
    ) = FolderTreeTagPreviewCommand(
        directory = directory,
        sourceId = SourceId("local"),
        generation = SnapshotGeneration(generation),
        recursive = recursive,
    )

    private class RecordingToolkit : AudioTagToolkit {
        var stagePatchCalls = 0

        override fun inspect(file: File): TagSnapshot {
            val stem = file.nameWithoutExtension
            val prefix = stem.substringBefore(' ').toIntOrNull()
            val title = stem.substringAfter(" - ", stem)
            val album = file.readText().substringAfter("|album=", "").substringBefore('|').takeIf(String::isNotBlank)
            val fields = linkedMapOf<TagField, MetadataValue>(
                TagField.TITLE to MetadataValue(title, MetadataProvenance.EMBEDDED),
            )
            prefix?.let { fields[TagField.TRACK_NUMBER] = MetadataValue(it.toString(), MetadataProvenance.EMBEDDED) }
            album?.let { fields[TagField.ALBUM] = MetadataValue(it, MetadataProvenance.EMBEDDED) }
            return TagSnapshot(
                format = "ID3",
                fields = fields,
                durationMillis = prefix?.toLong()?.times(1_000L),
            )
        }

        override fun stagePatch(
            source: File,
            stagingDirectory: File,
            patch: TagPatch,
            expectedSourceSha256: String?,
        ): StagedTagResult {
            stagePatchCalls += 1
            expectedSourceSha256?.let { check(source.sha256().equals(it, ignoreCase = true)) }
            val staged = File(stagingDirectory, ".properpcloud-stage-${UUID.randomUUID()}-${source.name}")
            source.copyTo(staged)
            (patch.mutations[TagField.ALBUM] as? TagMutation.Set)?.value?.let { album ->
                val base = staged.readText().substringBefore("|album=")
                staged.writeText("$base|album=$album")
            }
            val before = inspect(source)
            val after = inspect(staged)
            return StagedTagResult(
                stagedFile = staged,
                sourceSha256 = source.sha256(),
                stagedSha256 = staged.sha256(),
                snapshot = after,
                changedFields = patch.changedFields(before),
            )
        }
    }
}
