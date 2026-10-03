package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.ContentEvidence
import dev.properpcloud.core.model.FolderStructureTagConfig
import dev.properpcloud.core.model.LocalFileIdentity
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TagProposalEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun safeNormalizationComposesUnicodeWhitespaceAndNumbersOncePerField() {
        val file = temporary.newFile("01 - Cafe Noir.mp3").apply { writeText("x") }
        val snapshot = TagSnapshot(
            format = "ID3v2.4",
            fields = mapOf(
                TagField.TITLE to embedded("  Cafe\u0301   Noir  "),
                TagField.TRACK_NUMBER to embedded("003/012"),
            ),
        )

        val proposals = TagProposalEngine().generateProposals(
            listOf(identity(file) to snapshot),
            folderName = "Album",
        ).single().fieldProposals

        val title = proposals.filter { it.field == TagField.TITLE && it.ruleId == TagProposalEngine.RULE_SAFE_NORMALIZE }
        assertEquals(1, title.size)
        assertEquals("Café Noir", title.single().proposedValue)
        val track = proposals.single { it.field == TagField.TRACK_NUMBER && it.ruleId == TagProposalEngine.RULE_SAFE_NORMALIZE }
        assertEquals("3/12", track.proposedValue)
    }

    @Test
    fun freeformWhitespaceAndNonstandardNumericTextAreNotReinterpreted() {
        val file = temporary.newFile("chapter.mp3").apply { writeText("x") }
        val snapshot = TagSnapshot(
            format = "ID3",
            fields = mapOf(
                TagField.COMMENT to embedded("  keep   my spacing  "),
                TagField.TRACK_NUMBER to embedded("03 of 12"),
            ),
        )

        val proposals = TagProposalEngine().generateProposals(
            listOf(identity(file) to snapshot),
            folderName = "Album",
        ).single().fieldProposals

        assertFalse(proposals.any { it.field == TagField.COMMENT && it.ruleId == TagProposalEngine.RULE_SAFE_NORMALIZE })
        assertFalse(proposals.any { it.field == TagField.TRACK_NUMBER && it.ruleId == TagProposalEngine.RULE_SAFE_NORMALIZE })
    }

    @Test
    fun oneAlbumObservationIsNotEnoughForAutomaticFolderConsensus() {
        val tagged = temporary.newFile("01.mp3").apply { writeText("a") }
        val missing = temporary.newFile("02.mp3").apply { writeText("b") }
        val files = listOf(
            identity(tagged) to TagSnapshot("ID3", mapOf(TagField.ALBUM to embedded("Only evidence"))),
            identity(missing) to TagSnapshot("ID3"),
        )

        val proposals = TagProposalEngine().generateProposals(files, "Folder album")[1].fieldProposals
        assertFalse(proposals.any { it.ruleId == TagProposalEngine.RULE_COPY_UNANIMOUS_ALBUM })
        assertTrue(proposals.any { it.field == TagField.ALBUM && !it.autoPreselected })
    }

    @Test
    fun twoExactAlbumObservationsCanPreselectMissingAlbum() {
        val first = temporary.newFile("01.mp3").apply { writeText("a") }
        val second = temporary.newFile("02.mp3").apply { writeText("b") }
        val missing = temporary.newFile("03.mp3").apply { writeText("c") }
        val files = listOf(
            identity(first) to TagSnapshot("ID3", mapOf(TagField.ALBUM to embedded("Shared album"))),
            identity(second) to TagSnapshot("ID3", mapOf(TagField.ALBUM to embedded("Shared album"))),
            identity(missing) to TagSnapshot("ID3"),
        )

        val proposal = TagProposalEngine().generateProposals(files, "Folder album")[2].fieldProposals
            .single { it.field == TagField.ALBUM && it.ruleId == TagProposalEngine.RULE_COPY_UNANIMOUS_ALBUM }
        assertTrue(proposal.autoPreselected)
        assertEquals("Shared album", proposal.proposedValue)
    }

    @Test
    fun hierarchyDepthDerivesArtistAlbumAndFilenameTitle() {
        val library = temporary.newFolder("library")
        val artist = File(library, "Derived Artist").apply { mkdirs() }
        val album = File(artist, "Derived Album").apply { mkdirs() }
        val tracks = File(album, "Tracks").apply { mkdirs() }
        val track = File(tracks, "Track Name.mp3").apply { writeText("x") }

        val proposals = TagProposalEngine().generateProposals(
            listOf(identity(track) to TagSnapshot("ID3")),
            tracks,
            FolderStructureTagConfig(
                albumAncestorDepth = 1,
                artistAncestorDepth = 2,
                recognizeDiscFolders = false,
            ),
        ).single().fieldProposals

        assertEquals(
            "Derived Album",
            proposals.single { it.ruleId == TagProposalEngine.RULE_INFER_ALBUM_FOLDER }.proposedValue,
        )
        assertEquals(
            "Derived Artist",
            proposals.single { it.ruleId == TagProposalEngine.RULE_INFER_ARTIST_FOLDER }.proposedValue,
        )
        assertEquals(
            "Track Name",
            proposals.single { it.ruleId == TagProposalEngine.RULE_INFER_TITLE_FILENAME }.proposedValue,
        )
    }

    @Test
    fun commonDiscFolderIsSkippedForArtistAlbumDepthAndProvidesDiscNumber() {
        val library = temporary.newFolder("disc-library")
        val artist = File(library, "Artist").apply { mkdirs() }
        val album = File(artist, "Album").apply { mkdirs() }
        val disc = File(album, "Disc 02").apply { mkdirs() }
        val track = File(disc, "01 Song.mp3").apply { writeText("x") }

        val proposals = TagProposalEngine().generateProposals(
            listOf(identity(track) to TagSnapshot("ID3")),
            disc,
            FolderStructureTagConfig(),
        ).single().fieldProposals

        assertEquals("Album", proposals.single { it.ruleId == TagProposalEngine.RULE_INFER_ALBUM_FOLDER }.proposedValue)
        assertEquals("Artist", proposals.single { it.ruleId == TagProposalEngine.RULE_INFER_ARTIST_FOLDER }.proposedValue)
        assertEquals("2", proposals.single { it.ruleId == TagProposalEngine.RULE_INFER_DISC_FOLDER }.proposedValue)
    }

    @Test
    fun hierarchyConflictIsPreviewedButNeverPreselected() {
        val library = temporary.newFolder("conflict-library")
        val artist = File(library, "Derived Artist").apply { mkdirs() }
        val album = File(artist, "Derived Album").apply { mkdirs() }
        val track = File(album, "Song.mp3").apply { writeText("x") }
        val snapshot = TagSnapshot(
            "ID3",
            mapOf(TagField.ALBUM to embedded("Embedded Album")),
        )

        val proposal = TagProposalEngine().generateProposals(
            listOf(identity(track) to snapshot),
            album,
            FolderStructureTagConfig(),
        ).single().fieldProposals.single { it.ruleId == TagProposalEngine.RULE_INFER_ALBUM_FOLDER }

        assertEquals("Embedded Album", proposal.currentValue)
        assertEquals("Derived Album", proposal.proposedValue)
        assertTrue(proposal.conflictsWithExistingValue)
        assertFalse(proposal.autoPreselected)
        assertTrue(proposal.warnings.single().contains("explicitly approve"))
    }

    @Test
    fun sequenceNumbersIgnoreCallerOrderAndUseNaturalFilenameOrder() {
        val ten = temporary.newFile("10 Song.mp3").apply { writeText("x") }
        val two = temporary.newFile("2 Song.mp3").apply { writeText("x") }
        val one = temporary.newFile("1 Song.mp3").apply { writeText("x") }

        val rows = TagProposalEngine().generateProposals(
            listOf(identity(ten) to TagSnapshot("ID3"), identity(two) to TagSnapshot("ID3"), identity(one) to TagSnapshot("ID3")),
            "Album",
        )

        assertEquals(listOf("1 Song.mp3", "2 Song.mp3", "10 Song.mp3"), rows.map { it.identity.filename })
        assertEquals(
            listOf("1", "2", "3"),
            rows.map { row ->
                row.fieldProposals.single {
                    it.field == TagField.TRACK_NUMBER && it.ruleId == TagProposalEngine.RULE_SEQUENCE_TRACKS
                }.proposedValue
            },
        )
    }

    private fun identity(file: File) = LocalFileIdentity(
        sourceId = SourceId("local"),
        nodeId = NodeId("file:${file.name}"),
        file = file,
        filename = file.name,
        contentEvidence = ContentEvidence(file.length(), file.lastModified() * 1_000_000L),
    )

    private fun embedded(value: String) = MetadataValue(value, MetadataProvenance.EMBEDDED)
}
