package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.ContentEvidence
import dev.properpcloud.core.model.FileTagProposals
import dev.properpcloud.core.model.FolderTagSnapshot
import dev.properpcloud.core.model.LocalFileIdentity
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.SnapshotGeneration
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagSnapshot
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderPlaylistWriterTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun planIsSideEffectFreeAndTaggedTitleOrderingNeverChangesPaths() {
        val directory = temporary.newFolder("Compilation")
        val zeta = media(directory, "01-zeta.mp3", title = "Zeta", track = 1)
        val alpha = media(directory, "20-alpha.mp3", title = "Alpha", track = 20)
        val hash = media(directory, "#bonus.mp3", title = "Bonus", track = null)
        val writer = FolderPlaylistWriter()

        val plan = writer.plan(
            FolderPlaylistWriteCommand(snapshot(directory, listOf(zeta, alpha, hash)), FolderPlaylistOrder.TAGGED_TITLE),
        )

        assertFalse(File(directory, plan.fileName).exists())
        assertEquals("Compilation.m3u8", plan.fileName)
        assertEquals(listOf("./20-alpha.mp3", "./#bonus.mp3", "./01-zeta.mp3"), plan.relativeEntries)
        assertTrue(plan.extendedM3u.contains("\n./#bonus.mp3\n"))
        assertFalse(plan.extendedM3u.contains(directory.absolutePath))

        val result = writer.write(plan)
        assertEquals(64, result.sha256.length)
        assertEquals(plan.relativeEntries, result.relativeEntries)
    }

    @Test
    fun tagTrackOrderFallsBackToNaturalFilenameAndReservedNamesArePortable() {
        val directory = temporary.newFolder("album")
        val ten = media(directory, "10 - Ten.mp3", title = "Ten", track = null)
        val two = media(directory, "2 - Two.mp3", title = "Two", track = null)
        val one = media(directory, "01 - One.mp3", title = "One", track = 1)
        val writer = FolderPlaylistWriter()

        val result = writer.write(
            FolderPlaylistWriteCommand(
                snapshot(directory, listOf(ten, two, one)),
                order = FolderPlaylistOrder.TAG_TRACK_NUMBER,
                outputName = "CON.m3u",
            ),
        )

        assertEquals("CON playlist.m3u8", result.file.name)
        assertEquals(listOf("./01 - One.mp3", "./2 - Two.mp3", "./10 - Ten.mp3"), result.relativeEntries)
    }

    @Test
    fun modificationTimeOrderIsOldestFirstWithNaturalFilenameTieBreak() {
        val directory = temporary.newFolder("mtime")
        val later = media(directory, "10-later.mp3", "Later", null, modifiedTimeNanos = 3_000)
        val tieTen = media(directory, "10-tie.mp3", "Tie ten", null, modifiedTimeNanos = 2_000)
        val tieTwo = media(directory, "2-tie.mp3", "Tie two", null, modifiedTimeNanos = 2_000)
        val early = media(directory, "99-early.mp3", "Early", null, modifiedTimeNanos = 1_000)

        val plan = FolderPlaylistWriter().plan(
            FolderPlaylistWriteCommand(
                snapshot(directory, listOf(later, tieTen, tieTwo, early)),
                FolderPlaylistOrder.MODIFICATION_TIME,
            ),
        )

        assertEquals(
            listOf("./99-early.mp3", "./2-tie.mp3", "./10-tie.mp3", "./10-later.mp3"),
            plan.relativeEntries,
        )
    }

    @Test
    fun titleNumberOrderUsesLeadingEmbeddedNumberThenDeterministicFallbacksAndTies() {
        val directory = temporary.newFolder("title-number")
        val ten = media(directory, "z-ten.mp3", "10 Ten", null)
        val twoTieTen = media(directory, "10-tie.mp3", "2 Same", null)
        val twoTieTwo = media(directory, "2-tie.mp3", "2 Same", null)
        val one = media(directory, "z-one.mp3", "01 One", null)
        val nonNumericTen = media(directory, "10-appendix.mp3", "Appendix", null)
        val nonNumericTwo = media(directory, "2-appendix.mp3", "Appendix", null)
        val missing = media(directory, "3-missing.mp3", null, null)

        val plan = FolderPlaylistWriter().plan(
            FolderPlaylistWriteCommand(
                snapshot(
                    directory,
                    listOf(ten, twoTieTen, nonNumericTen, missing, one, twoTieTwo, nonNumericTwo),
                ),
                FolderPlaylistOrder.TITLE_NUMBER,
            ),
        )

        assertEquals(
            listOf(
                "./z-one.mp3",
                "./2-tie.mp3",
                "./10-tie.mp3",
                "./z-ten.mp3",
                "./2-appendix.mp3",
                "./10-appendix.mp3",
                "./3-missing.mp3",
            ),
            plan.relativeEntries,
        )
        assertTrue(plan.extendedM3u.contains("\n./z-one.mp3\n"))
        assertFalse(plan.extendedM3u.contains(directory.absolutePath))
    }

    @Test
    fun extinfUsesTrustedDurationAndFallsBackExplicitlyWhenUnknown() {
        val directory = temporary.newFolder("duration")
        val known = media(directory, "01-known.mp3", "Known", 1, durationMillis = 61_000)
        val unknown = media(directory, "02-unknown.mp3", "Unknown", 2)

        val plan = FolderPlaylistWriter().plan(
            FolderPlaylistWriteCommand(snapshot(directory, listOf(known, unknown))),
        )

        assertTrue(plan.extendedM3u.contains("#EXTINF:61,Known\n./01-known.mp3"))
        assertTrue(plan.extendedM3u.contains("#EXTINF:-1,Unknown\n./02-unknown.mp3"))
        assertEquals(1, plan.durationFallbackCount)
    }

    @Test
    fun coherentAlbumTagsImprovePlaylistNameWithoutChangingMediaPaths() {
        val directory = temporary.newFolder("incoming")
        val first = media(directory, "01.mp3", "One", 1, artist = "Example Artist", album = "Example Album")
        val second = media(directory, "02.mp3", "Two", 2, artist = "Example Artist", album = "Example Album")

        val plan = FolderPlaylistWriter().plan(
            FolderPlaylistWriteCommand(snapshot(directory, listOf(first, second))),
        )

        assertEquals("Example Artist - Example Album.m3u8", plan.fileName)
        assertEquals(listOf("./01.mp3", "./02.mp3"), plan.relativeEntries)
        assertFalse(plan.extendedM3u.contains("Example Album/01.mp3"))
    }

    @Test
    fun recursiveBatchRequiresOptInAndOnePerAlbumGroupsOnlyDiscFolders() {
        val root = temporary.newFolder("library")
        val album = File(root, "Album Folder").apply { mkdirs() }
        val disc1 = File(album, "CD 1").apply { mkdirs() }
        val disc2 = File(album, "Disc 02").apply { mkdirs() }
        val d1 = media(disc1, "01 - One.mp3", "One", 1, disc = 1, artist = "Artist", album = "Album")
        val d2 = media(disc2, "01 - Two.mp3", "Two", 1, disc = 2, artist = "Artist", album = "Album")
        val snapshots = listOf(snapshot(disc1, listOf(d1)), snapshot(disc2, listOf(d2)))
        val writer = FolderPlaylistWriter()

        assertThrows(IllegalArgumentException::class.java) {
            writer.planBatch(
                FolderPlaylistBatchCommand(root, snapshots, recursive = true, onePlaylistPerAlbum = true),
            )
        }

        val plan = writer.planBatch(
            FolderPlaylistBatchCommand(
                rootDirectory = root,
                snapshots = snapshots,
                recursive = true,
                recursiveOptIn = true,
                onePlaylistPerAlbum = true,
            ),
        )

        assertEquals(1, plan.playlistCount)
        assertEquals(album.canonicalFile, plan.playlists.single().directory)
        assertEquals("Artist - Album.m3u8", plan.playlists.single().fileName)
        assertEquals(
            listOf("./CD 1/01 - One.mp3", "./Disc 02/01 - Two.mp3"),
            plan.playlists.single().relativeEntries,
        )

        val progress = mutableListOf<FolderPlaylistBatchProgress>()
        val result = writer.writeBatch(plan, progress::add)
        assertEquals(1, result.results.size)
        assertEquals(listOf(1), progress.map { it.completed })
        assertEquals(listOf(1), progress.map { it.total })
        assertTrue(result.results.single().file.isFile)
    }

    @Test
    fun recursiveBatchRejectsSnapshotOutsideSelectedRoot() {
        val root = temporary.newFolder("selected")
        val outside = temporary.newFolder("outside")
        val escaped = media(outside, "01.mp3", "Outside", 1)

        assertThrows(IllegalArgumentException::class.java) {
            FolderPlaylistWriter().planBatch(
                FolderPlaylistBatchCommand(
                    rootDirectory = root,
                    snapshots = listOf(snapshot(outside, listOf(escaped))),
                    recursive = true,
                    recursiveOptIn = true,
                ),
            )
        }
    }

    @Test
    fun recursiveSubtreeCreatesOnePlaylistPerFolderAndReportsAllProgress() {
        val root = temporary.newFolder("tree")
        val firstAlbum = File(root, "Album 2").apply { mkdirs() }
        val secondAlbum = File(root, "Album 10").apply { mkdirs() }
        val first = media(firstAlbum, "01.mp3", "One", 1)
        val second = media(secondAlbum, "01.mp3", "Two", 1)
        val writer = FolderPlaylistWriter()

        val plan = writer.planBatch(
            FolderPlaylistBatchCommand(
                rootDirectory = root,
                snapshots = listOf(snapshot(secondAlbum, listOf(second)), snapshot(firstAlbum, listOf(first))),
                recursive = true,
                recursiveOptIn = true,
            ),
        )
        assertEquals(listOf("Album 2", "Album 10"), plan.playlists.map { it.directory.name })

        val progress = mutableListOf<FolderPlaylistBatchProgress>()
        val result = writer.writeBatch(plan, progress::add)
        assertEquals(2, result.results.size)
        assertEquals(listOf(1, 2), progress.map { it.completed })
        assertTrue(progress.all { it.total == 2 })
        assertTrue(result.results.all { it.file.isFile })
    }

    @Test
    fun rejectsMediaSymlinkEvenWhenItsTargetIsInsideTheSelectedFolder() {
        val directory = temporary.newFolder("symlink-media")
        val target = File(directory, "real.mp3").apply { writeText("audio") }
        val link = File(directory, "linked.mp3")
        Files.createSymbolicLink(link.toPath(), target.toPath().fileName)
        val escaped = FileTagProposals(
            identity = LocalFileIdentity(
                sourceId = SourceId("local"),
                nodeId = NodeId("file:linked"),
                file = link,
                filename = link.name,
                contentEvidence = ContentEvidence(target.length(), target.lastModified() * 1_000_000L),
            ),
            originalSnapshot = TagSnapshot("ID3"),
            fieldProposals = emptyList(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            FolderPlaylistWriter().plan(
                FolderPlaylistWriteCommand(snapshot(directory, listOf(escaped))),
            )
        }
    }

    @Test
    fun regenerationIsDebouncedBoundedAndDeterministic() {
        val directory = temporary.newFolder("regen")
        val track = media(directory, "01.mp3", "One", 1, durationMillis = 30_000)
        val writer = FolderPlaylistWriter()
        val direct = writer.plan(FolderPlaylistWriteCommand(snapshot(directory, listOf(track))))
        val batch = FolderPlaylistBatchPlan(
            rootDirectory = directory.canonicalFile,
            recursive = false,
            recursiveOptInConfirmed = true,
            onePlaylistPerAlbum = false,
            playlists = listOf(direct),
        )
        var now = 1_000L
        val service = FolderPlaylistRegenerationService(
            writer = writer,
            debounceMillis = 250,
            maxPendingBatches = 1,
            maxPlaylistsPerBatch = 4,
            clockMillis = { now },
        )

        val firstSchedule = service.schedule("album", batch)
        assertEquals(1_250L, firstSchedule.dueAtEpochMillis)
        assertTrue(service.flushDue(nowEpochMillis = 1_249).isEmpty())
        val first = service.flushDue(nowEpochMillis = 1_250).single().results.single()
        assertEquals(0, service.pendingCount())

        now = 2_000L
        service.schedule("album", batch)
        val replaced = service.schedule("album", batch, nowEpochMillis = 2_100)
        assertTrue(replaced.replacedPendingRequest)
        assertThrows(IllegalArgumentException::class.java) {
            service.schedule("other", batch, nowEpochMillis = 2_100)
        }
        val second = service.flushDue(nowEpochMillis = 2_350).single().results.single()

        assertEquals(first.sha256, second.sha256)
        assertEquals(first.relativeEntries, second.relativeEntries)
    }

    @Test
    fun stalePreviewEvidenceBlocksMaterializationAndRequiresReconciliation() {
        val directory = temporary.newFolder("stale-plan")
        val track = media(directory, "01.mp3", "One", 1)
        val writer = FolderPlaylistWriter()
        val plan = writer.plan(FolderPlaylistWriteCommand(snapshot(directory, listOf(track))))

        File(directory, "01.mp3").appendText("-changed-after-preview")

        val error = assertThrows(IllegalArgumentException::class.java) { writer.write(plan) }
        assertTrue(error.message.orEmpty().contains("changed after preview"))
        assertFalse(File(directory, plan.fileName).exists())
    }

    @Test
    fun newRecursiveDirectoryAfterPreviewBlocksBatchMaterialization() {
        val root = temporary.newFolder("stale-subtree")
        val cd1 = File(root, "CD 1").apply { mkdirs() }
        val first = media(cd1, "01.mp3", "One", 1)
        val writer = FolderPlaylistWriter()
        val plan = writer.planBatch(
            FolderPlaylistBatchCommand(
                rootDirectory = root,
                snapshots = listOf(snapshot(root, emptyList()), snapshot(cd1, listOf(first))),
                recursive = true,
                recursiveOptIn = true,
            ),
        )

        val cd2 = File(root, "CD 2").apply { mkdirs() }
        File(cd2, "02.mp3").writeText("new-disc")

        val error = assertThrows(IllegalArgumentException::class.java) { writer.writeBatch(plan) }
        assertTrue(error.message.orEmpty().contains("subtree directory membership changed"))
        assertTrue(plan.playlists.none { File(it.directory, it.fileName).exists() })
    }

    @Test
    fun reviewedEmptyDirectoryGainingAudioBlocksBatchBeforeAnyWrite() {
        val root = temporary.newFolder("stale-empty-membership")
        val album = File(root, "Album").apply { mkdirs() }
        val extras = File(root, "Extras").apply { mkdirs() }
        val track = media(album, "01.mp3", "One", 1)
        val writer = FolderPlaylistWriter()
        val plan = writer.planBatch(
            FolderPlaylistBatchCommand(
                rootDirectory = root,
                snapshots = listOf(
                    snapshot(root, emptyList()),
                    snapshot(album, listOf(track)),
                    snapshot(extras, emptyList()),
                ),
                recursive = true,
                recursiveOptIn = true,
            ),
        )
        assertEquals(emptySet<String>(), plan.reviewedDirectoryEvidence.single { it.directory == extras.canonicalFile }.expectedAudioFileNames)

        File(extras, "late.mp3").writeText("new-audio")

        val error = assertThrows(IllegalArgumentException::class.java) { writer.writeBatch(plan) }
        assertTrue(error.message.orEmpty().contains("membership changed after preview"))
        assertTrue(plan.playlists.none { File(it.directory, it.fileName).exists() })
    }

    @Test
    fun newAudioChildAfterPreviewBlocksMaterializationUntilReconciled() {
        val directory = temporary.newFolder("stale-membership")
        val track = media(directory, "01.mp3", "One", 1)
        val writer = FolderPlaylistWriter()
        val plan = writer.plan(FolderPlaylistWriteCommand(snapshot(directory, listOf(track))))

        File(directory, "02.mp3").writeText("new-audio")

        val error = assertThrows(IllegalArgumentException::class.java) { writer.write(plan) }
        assertTrue(error.message.orEmpty().contains("membership changed after preview"))
        assertFalse(File(directory, plan.fileName).exists())
    }

    @Test
    fun postSyncRegenerationRetainsStalePlanUntilCallerReconcilesIt() {
        val directory = temporary.newFolder("stale-regeneration")
        val track = media(directory, "01.mp3", "One", 1)
        val writer = FolderPlaylistWriter()
        val playlist = writer.plan(FolderPlaylistWriteCommand(snapshot(directory, listOf(track))))
        val batch = FolderPlaylistBatchPlan(
            rootDirectory = directory.canonicalFile,
            recursive = false,
            recursiveOptInConfirmed = true,
            onePlaylistPerAlbum = false,
            playlists = listOf(playlist),
        )
        val service = FolderPlaylistRegenerationService(writer = writer, debounceMillis = 0)
        service.schedule("album", batch, nowEpochMillis = 10)
        File(directory, "01.mp3").appendText("-changed")

        assertThrows(IllegalArgumentException::class.java) {
            service.flushDue(nowEpochMillis = 10)
        }
        assertEquals(1, service.pendingCount())
        assertFalse(File(directory, playlist.fileName).exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFilenameThatCannotBeRepresentedByLineOrientedM3u() {
        val directory = temporary.newFolder("bad-name")
        val file = File(directory, "bad\nname.mp3").apply { writeText("audio") }
        FolderPlaylistWriter().plan(
            FolderPlaylistWriteCommand(snapshot(directory, listOf(row(file, "Bad", 1)))),
        )
    }

    private fun media(
        directory: File,
        name: String,
        title: String?,
        track: Int?,
        disc: Int? = null,
        artist: String? = null,
        album: String? = null,
        durationMillis: Long? = null,
        modifiedTimeNanos: Long? = null,
    ): FileTagProposals {
        val file = File(directory, name).apply { writeText("audio-$name") }
        return row(file, title, track, disc, artist, album, durationMillis, modifiedTimeNanos)
    }

    private fun row(
        file: File,
        title: String?,
        track: Int?,
        disc: Int? = null,
        artist: String? = null,
        album: String? = null,
        durationMillis: Long? = null,
        modifiedTimeNanos: Long? = null,
    ): FileTagProposals {
        val fields = linkedMapOf<TagField, MetadataValue>()
        title?.let { fields[TagField.TITLE] = MetadataValue(it, MetadataProvenance.EMBEDDED) }
        track?.let { fields[TagField.TRACK_NUMBER] = MetadataValue(it.toString(), MetadataProvenance.EMBEDDED) }
        disc?.let { fields[TagField.DISC_NUMBER] = MetadataValue(it.toString(), MetadataProvenance.EMBEDDED) }
        artist?.let { fields[TagField.ARTIST] = MetadataValue(it, MetadataProvenance.EMBEDDED) }
        album?.let { fields[TagField.ALBUM] = MetadataValue(it, MetadataProvenance.EMBEDDED) }
        return FileTagProposals(
            identity = LocalFileIdentity(
                sourceId = SourceId("local"),
                nodeId = NodeId("file:${file.canonicalPath}"),
                file = file.canonicalFile,
                filename = file.name,
                contentEvidence = ContentEvidence(file.length(), modifiedTimeNanos ?: observedModifiedTimeNanos(file)),
            ),
            originalSnapshot = TagSnapshot("ID3", fields, durationMillis = durationMillis),
            fieldProposals = emptyList(),
        )
    }

    private fun snapshot(directory: File, rows: List<FileTagProposals>) = FolderTagSnapshot(
        generation = SnapshotGeneration(1),
        folderPath = directory.canonicalFile,
        sourceId = SourceId("local"),
        folderId = NodeId("folder:${directory.canonicalPath}"),
        files = rows,
        scanTimeEpochMillis = 1L,
    )

    private fun observedModifiedTimeNanos(file: File): Long {
        val instant = Files.getLastModifiedTime(file.toPath()).toInstant()
        return instant.epochSecond * 1_000_000_000L + instant.nano
    }
}
