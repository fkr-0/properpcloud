package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.FolderMetadataLookup
import dev.properpcloud.core.model.MetadataCandidate
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.SnapshotGeneration
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagSnapshot
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderAutoTagWorkflowTest {
    @Test
    fun previewsDirectoryCandidatesAndRequiresExplicitFieldApproval() = runBlocking {
        val directory = Files.createTempDirectory("folder-autotag").toFile()
        val file = File(directory, "01 - Old title.mp3").apply { writeText("not-real-audio") }
        val toolkit = FakeToolkit(
            mapOf(file.name to snapshot("Old title", 1, durationMillis = 123_000)),
        )
        val scanner = FolderTagScanner(toolkit).also { it.addAuthorizedRoot(directory) }
        val receivedTitles = mutableListOf<String?>()
        val receivedDurations = mutableListOf<Long?>()
        val lookup = FolderMetadataLookup { query, _ ->
            receivedTitles += query.title
            receivedDurations += query.durationMillis
            listOf(
                MetadataCandidate(
                    id = "mbid",
                    provider = MetadataProvenance.MUSICBRAINZ,
                    score = 0.92,
                    fields = mapOf(
                        TagField.TITLE to MetadataValue("Canonical title", MetadataProvenance.MUSICBRAINZ, 0.92),
                        TagField.ARTIST to MetadataValue("Canonical artist", MetadataProvenance.MUSICBRAINZ, 0.91),
                    ),
                ),
            )
        }
        val workflow = FolderAutoTagWorkflow(scanner, lookup, FolderTagApplyService(toolkit))

        val withoutConsent = workflow.preview(
            FolderTagPreviewCommand(directory, SourceId("local"), SnapshotGeneration(1)),
        )
        assertTrue(withoutConsent.files.single().onlineCandidates.isEmpty())
        assertTrue(receivedTitles.isEmpty())

        val preview = workflow.preview(
            FolderTagPreviewCommand(directory, SourceId("local"), SnapshotGeneration(2), onlineLookupConsent = true),
        )
        val row = preview.files.single()
        assertEquals(listOf("Old title"), receivedTitles)
        assertEquals(listOf(123_000L), receivedDurations)
        assertEquals("mbid", row.onlineCandidates.single().id)
        assertFalse(row.onlineCandidates.single().fields.isEmpty())

        val approval = workflow.approveCandidate(
            ApproveCandidateCommand(preview, row.identity.nodeId, "mbid", setOf(TagField.TITLE)),
        )
        assertEquals(setOf(TagField.TITLE), approval.toTagPatch().changedFields(row.originalSnapshot))
        assertFalse(approval.approvedFields.containsKey(TagField.ARTIST))
        assertFalse(approval.approvedFields.getValue(TagField.TITLE).proposal.autoPreselected)
    }

    @Test
    fun sourceChangeAfterPreviewInvalidatesApprovalWithoutStagingTags() = runBlocking {
        val directory = Files.createTempDirectory("folder-autotag-stale-approval").toFile()
        val file = File(directory, "01 - Old title.mp3").apply { writeText("reviewed-bytes") }
        val toolkit = FakeToolkit(mapOf(file.name to snapshot("Old title", 1)))
        val scanner = FolderTagScanner(toolkit).also { it.addAuthorizedRoot(directory) }
        val lookup = FolderMetadataLookup { _, _ ->
            listOf(
                MetadataCandidate(
                    id = "candidate",
                    provider = MetadataProvenance.MUSICBRAINZ,
                    score = 0.9,
                    fields = mapOf(
                        TagField.TITLE to MetadataValue("Canonical", MetadataProvenance.MUSICBRAINZ, 0.9),
                    ),
                ),
            )
        }
        val workflow = FolderAutoTagWorkflow(scanner, lookup, FolderTagApplyService(toolkit))
        val preview = workflow.preview(
            FolderTagPreviewCommand(
                directory,
                SourceId("local"),
                SnapshotGeneration(1),
                onlineLookupConsent = true,
            ),
        )
        file.appendText("-changed-after-preview")

        val error = assertThrows(IllegalArgumentException::class.java) {
            workflow.approveCandidate(
                ApproveCandidateCommand(
                    preview,
                    preview.files.single().identity.nodeId,
                    "candidate",
                    setOf(TagField.TITLE),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("changed after preview"))
        assertEquals(0, toolkit.stagePatchCalls)
    }

    @Test
    fun writesPortableFolderPlaylistAndCanReorderItWithoutAbsolutePaths() = runBlocking {
        val root = Files.createTempDirectory("folder-playlist").toFile()
        val directory = File(root, "Artist - Album").apply { mkdirs() }
        listOf("10 - Ten.mp3", "2 - Two.mp3", "01 - One.mp3").forEach { name ->
            File(directory, name).writeText("audio-$name")
        }
        val toolkit = FakeToolkit(
            mapOf(
                "10 - Ten.mp3" to snapshot("First", 1),
                "01 - One.mp3" to snapshot("Second", 2),
                "2 - Two.mp3" to snapshot("Tenth", 10),
            ),
        )
        val scanner = FolderTagScanner(toolkit).also { it.addAuthorizedRoot(root) }
        val workflow = FolderAutoTagWorkflow(scanner, FolderMetadataLookup { _, _ -> emptyList() }, FolderTagApplyService(toolkit))
        val preview = workflow.preview(
            FolderTagPreviewCommand(directory, SourceId("local"), SnapshotGeneration(1)),
        )

        val byTag = workflow.writePlaylist(
            workflow.planPlaylist(FolderPlaylistWriteCommand(preview, FolderPlaylistOrder.TAG_TRACK_NUMBER)),
        )
        assertEquals("Artist - Album.m3u8", byTag.file.name)
        assertEquals(
            listOf("./10 - Ten.mp3", "./01 - One.mp3", "./2 - Two.mp3"),
            playlistEntries(byTag.file),
        )
        assertTrue(byTag.file.readText().startsWith("#EXTM3U\n"))
        assertFalse(byTag.file.readText().contains(directory.absolutePath))

        val byFilename = workflow.writePlaylist(
            workflow.planPlaylist(FolderPlaylistWriteCommand(preview, FolderPlaylistOrder.NATURAL_FILENAME)),
        )
        assertEquals(byTag.file.canonicalFile, byFilename.file.canonicalFile)
        assertEquals(
            listOf("./01 - One.mp3", "./2 - Two.mp3", "./10 - Ten.mp3"),
            playlistEntries(byFilename.file),
        )
    }

    @Test
    fun recursiveTreeRequiresExplicitOptInAndDryRunReportsProgressWithoutWrites() = runBlocking {
        val root = Files.createTempDirectory("folder-tree-autotag").toFile()
        val artist = File(root, "Artist").apply { mkdirs() }
        val album = File(artist, "Album").apply { mkdirs() }
        listOf("2 Song.mp3", "10 Song.mp3").forEach { name -> File(album, name).writeText("audio-$name") }
        val toolkit = FakeToolkit()
        val scanner = FolderTagScanner(toolkit).also { it.addAuthorizedRoot(root) }
        val workflow = FolderAutoTagWorkflow(scanner, FolderMetadataLookup { _, _ -> emptyList() }, FolderTagApplyService(toolkit))

        val direct = workflow.previewTree(
            FolderTreeTagPreviewCommand(root, SourceId("local"), SnapshotGeneration(1)),
        )
        assertFalse(direct.recursive)
        assertEquals(1, direct.folderCount)
        assertEquals(0, direct.fileCount)

        val recursive = workflow.previewTree(
            FolderTreeTagPreviewCommand(root, SourceId("local"), SnapshotGeneration(10), recursive = true),
        )
        assertTrue(recursive.recursive)
        assertEquals(2, recursive.fileCount)
        val approvals = recursive.snapshots.flatMap { it.files }.map { row ->
            val snapshot = recursive.snapshots.single { candidate ->
                candidate.files.any { it.identity.nodeId == row.identity.nodeId }
            }
            workflow.approveLocalProposals(
                ApproveLocalProposalsCommand(
                    snapshot = snapshot,
                    nodeId = row.identity.nodeId,
                    acceptedRuleByField = mapOf(TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER),
                ),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            workflow.planBatch(recursive, approvals)
        }
        val plan = workflow.planBatch(recursive, approvals, recursiveOptIn = true)
        assertEquals(listOf("Artist/Album/2 Song.mp3", "Artist/Album/10 Song.mp3"), plan.items.map { it.relativePath })

        assertThrows(IllegalArgumentException::class.java) {
            workflow.executeBatchPlan(
                plan = plan,
                stagingDirectory = File(root, "scratch-not-created"),
                dryRun = false,
                confirmWrite = false,
            )
        }
        assertEquals(0, toolkit.stagePatchCalls)

        val progress = mutableListOf<FolderTagBatchProgress>()
        val result = workflow.executeBatchPlan(
            plan = plan,
            stagingDirectory = File(root, "scratch-not-created"),
            dryRun = true,
            onProgress = progress::add,
        )

        assertTrue(result.dryRun)
        assertTrue(result.results.isEmpty())
        assertEquals(2, result.preflight.size)
        assertTrue(result.preflight.all { it.ready })
        assertEquals(0, toolkit.stagePatchCalls)
        assertEquals(listOf(1, 2), progress.map { it.completed })
        assertTrue(progress.all { it.total == 2 && it.dryRun })
        assertFalse(File(root, "scratch-not-created").exists())
    }

    @Test
    fun approvedPreviewCanBeVerifiedThenPlayedFromPlaylistWithoutTagMutation() = runBlocking {
        val root = Files.createTempDirectory("folder-playlist-e2e").toFile()
        val artist = File(root, "Artist").apply { mkdirs() }
        val album = File(artist, "Album").apply { mkdirs() }
        listOf("10 - Ten.mp3", "2 - Two.mp3", "01 - One.mp3").forEach { name ->
            File(album, name).writeText("audio-$name")
        }
        val toolkit = FakeToolkit(
            mapOf(
                "10 - Ten.mp3" to snapshot("Ten", 10, durationMillis = 30_000),
                "2 - Two.mp3" to snapshot("Two", 2, durationMillis = 20_000),
                "01 - One.mp3" to snapshot("One", 1, durationMillis = 10_000),
            ),
        )
        val scanner = FolderTagScanner(toolkit).also { it.addAuthorizedRoot(root) }
        val workflow = FolderAutoTagWorkflow(
            scanner,
            FolderMetadataLookup { _, _ -> emptyList() },
            FolderTagApplyService(toolkit),
        )

        val preview = workflow.preview(
            FolderTagPreviewCommand(album, SourceId("local"), SnapshotGeneration(20)),
        )
        val reviewedRow = preview.files.first()
        val approval = workflow.approveLocalProposals(
            ApproveLocalProposalsCommand(
                snapshot = preview,
                nodeId = reviewedRow.identity.nodeId,
                acceptedRuleByField = mapOf(TagField.ALBUM to TagProposalEngine.RULE_INFER_ALBUM_FOLDER),
            ),
        )
        assertEquals("Album", approval.approvedFields.getValue(TagField.ALBUM).finalValue)
        assertEquals(0, toolkit.stagePatchCalls)

        val verified = workflow.preview(
            FolderTagPreviewCommand(album, SourceId("local"), SnapshotGeneration(21)),
        )
        assertEquals(
            preview.files.map { it.identity.contentEvidence },
            verified.files.map { it.identity.contentEvidence },
        )
        assertEquals(0, toolkit.stagePatchCalls)

        val playlistPlan = workflow.planPlaylist(
            FolderPlaylistWriteCommand(verified, FolderPlaylistOrder.TAG_TRACK_NUMBER),
        )
        val playlist = workflow.writePlaylist(playlistPlan)
        assertEquals(
            listOf("./01 - One.mp3", "./2 - Two.mp3", "./10 - Ten.mp3"),
            playlistEntries(playlist.file),
        )
        assertTrue(playlist.file.readText().contains("#EXTINF:10,One"))
        assertEquals(0, toolkit.stagePatchCalls)

        val tree = workflow.previewTree(
            FolderTreeTagPreviewCommand(album, SourceId("local"), SnapshotGeneration(30)),
        )
        val batchPlan = workflow.planPlaylistBatch(tree)
        val regeneration = FolderPlaylistRegenerationService(debounceMillis = 0)
        regeneration.schedule("post-sync:${album.canonicalPath}", batchPlan, nowEpochMillis = 100)
        val regenerated = regeneration.flushDue(nowEpochMillis = 100)
        assertEquals(1, regenerated.single().results.size)
        assertEquals(0, toolkit.stagePatchCalls)
    }

    @Test
    fun recursivePlaylistOptInDoesNotOptIntoRecursiveTagWrites() = runBlocking {
        val root = Files.createTempDirectory("folder-playlist-recursive-boundary").toFile()
        val album = File(root, "Artist/Album").apply { mkdirs() }
        File(album, "01 - One.mp3").writeText("audio")
        val toolkit = FakeToolkit(mapOf("01 - One.mp3" to snapshot("One", 1)))
        val scanner = FolderTagScanner(toolkit).also { it.addAuthorizedRoot(root) }
        val workflow = FolderAutoTagWorkflow(
            scanner,
            FolderMetadataLookup { _, _ -> emptyList() },
            FolderTagApplyService(toolkit),
        )
        val preview = workflow.previewTree(
            FolderTreeTagPreviewCommand(root, SourceId("local"), SnapshotGeneration(40), recursive = true),
        )

        assertThrows(IllegalArgumentException::class.java) {
            workflow.planPlaylistBatch(preview)
        }
        val playlistPlan = workflow.planPlaylistBatch(preview, recursiveOptIn = true)
        assertTrue(playlistPlan.recursiveOptInConfirmed)
        assertEquals(1, playlistPlan.playlistCount)
        assertEquals(0, toolkit.stagePatchCalls)

        workflow.writePlaylistBatch(playlistPlan)
        assertEquals(0, toolkit.stagePatchCalls)
    }

    private fun snapshot(title: String, track: Int, durationMillis: Long? = null) = TagSnapshot(
        "ID3",
        mapOf(
            TagField.TITLE to MetadataValue(title, MetadataProvenance.EMBEDDED),
            TagField.TRACK_NUMBER to MetadataValue(track.toString(), MetadataProvenance.EMBEDDED),
        ),
        durationMillis = durationMillis,
    )

    private fun playlistEntries(file: File): List<String> = file.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }

    private class FakeToolkit(
        private val snapshots: Map<String, TagSnapshot> = emptyMap(),
    ) : AudioTagToolkit {
        var stagePatchCalls: Int = 0

        override fun inspect(file: File) = snapshots[file.name] ?: TagSnapshot(
            "ID3",
            mapOf(TagField.TITLE to MetadataValue("Old title", MetadataProvenance.EMBEDDED)),
        )

        override fun stagePatch(
            source: File,
            stagingDirectory: File,
            patch: dev.properpcloud.core.model.TagPatch,
            expectedSourceSha256: String?,
        ): StagedTagResult {
            stagePatchCalls += 1
            error("not needed for preview")
        }
    }
}
