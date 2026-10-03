package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.SnapshotGeneration
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.core.model.TagScanFailureKind
import dev.properpcloud.core.model.TagSnapshot
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderTagScannerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun malformedAudioRemainsVisibleAndCannotProduceProposals() {
        val directory = temporary.newFolder("album")
        File(directory, "01-good.mp3").writeText("good")
        File(directory, "02-broken.mp3").writeText("broken")
        val scanner = FolderTagScanner(FakeToolkit()).also { it.addAuthorizedRoot(directory) }

        val snapshot = scanner.scan(directory, SourceId("local"), SnapshotGeneration(1))

        assertEquals(2, snapshot.files.size)
        val good = snapshot.files.single { it.identity.filename == "01-good.mp3" }
        assertNull(good.scanFailure)
        val broken = snapshot.files.single { it.identity.filename == "02-broken.mp3" }
        assertNotNull(broken.scanFailure)
        assertEquals(TagScanFailureKind.MALFORMED_OR_UNSUPPORTED, broken.scanFailure!!.kind)
        assertFalse(broken.canApply)
        assertFalse(broken.hasProposals)
    }

    @Test
    fun contentEvidenceKeepsAvailableFileTimeNanoseconds() {
        val directory = temporary.newFolder("mtime-nanos")
        val file = File(directory, "01-track.mp3").apply { writeText("good") }
        val requested = java.nio.file.attribute.FileTime.from(
            java.time.Instant.ofEpochSecond(1_700_000_000L, 123_456_789L),
        )
        Files.setLastModifiedTime(file.toPath(), requested)
        val scanner = FolderTagScanner(FakeToolkit()).also { it.addAuthorizedRoot(directory) }

        val row = scanner.scan(directory, SourceId("local"), SnapshotGeneration(1)).files.single()
        val observed = Files.getLastModifiedTime(file.toPath()).toInstant()
        val expected = observed.epochSecond * 1_000_000_000L + observed.nano

        assertEquals(expected, row.identity.contentEvidence.modifiedTimeNanos)
    }

    @Test
    fun transactionSiblingsAreNotRediscoveredAsLibraryAudio() {
        val directory = temporary.newFolder("transactions")
        File(directory, "01-track.mp3").writeText("good")
        File(directory, ".properpcloud-stage-01-track-123.mp3").writeText("candidate")
        File(directory, ".properpcloud-rollback-123.mp3").writeText("rollback")
        val scanner = FolderTagScanner(FakeToolkit()).also { it.addAuthorizedRoot(directory) }

        val snapshot = scanner.scan(directory, SourceId("local"), SnapshotGeneration(1))

        assertEquals(listOf("01-track.mp3"), snapshot.files.map { it.identity.filename })
    }

    @Test
    fun callerCanProjectFilesystemIdentityWithoutChangingDefaultScannerSemantics() {
        val directory = temporary.newFolder("opaque-identity")
        val file = File(directory, "01-track.mp3").apply { writeText("good") }
        val scanner = FolderTagScanner(
            toolkit = FakeToolkit(),
            nodeIdentity = { candidate, isDirectory ->
                NodeId(if (isDirectory) "opaque-root" else "opaque:${candidate.name}")
            },
        ).also { it.addAuthorizedRoot(directory) }

        val snapshot = scanner.scan(directory, SourceId("local:opaque"), SnapshotGeneration(1))

        assertEquals(NodeId("opaque-root"), snapshot.folderId)
        assertEquals(NodeId("opaque:${file.name}"), snapshot.files.single().identity.nodeId)
        assertFalse(snapshot.files.single().identity.nodeId.value.contains(directory.absolutePath))
    }

    @Test
    fun audioChildrenUseNaturalFilenameOrdering() {
        val directory = temporary.newFolder("natural-order")
        listOf("10-track.mp3", "2-track.mp3", "1-track.mp3").forEach { name ->
            File(directory, name).writeText("good")
        }
        val scanner = FolderTagScanner(FakeToolkit()).also { it.addAuthorizedRoot(directory) }

        val snapshot = scanner.scan(directory, SourceId("local"), SnapshotGeneration(1))

        assertEquals(
            listOf("1-track.mp3", "2-track.mp3", "10-track.mp3"),
            snapshot.files.map { it.identity.filename },
        )
    }

    private class FakeToolkit : AudioTagToolkit {
        override fun inspect(file: File): TagSnapshot {
            if (file.readText() == "broken") error("synthetic parse failure")
            return TagSnapshot(
                "ID3",
                mapOf(TagField.TITLE to MetadataValue(file.nameWithoutExtension, MetadataProvenance.EMBEDDED)),
            )
        }

        override fun stagePatch(
            source: File,
            stagingDirectory: File,
            patch: TagPatch,
            expectedSourceSha256: String?,
        ): StagedTagResult = error("not used")
    }
}
