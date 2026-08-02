package dev.properpcloud.app.metadata

import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MetadataCandidate
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagMutation
import dev.properpcloud.core.model.TagSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDraftPlannerTest {
    private val track = AudioTrack(
        sourceId = SourceId("demo"),
        id = NodeId("track"),
        parentId = NodeId("folder"),
        name = "01 - Fallback title.wav",
        taggedTitle = "Fallback title",
        durationMillis = 61_000,
    )

    @Test
    fun singleDraftCalculatesSetClearAndKeep() {
        val original = snapshot(
            TagField.TITLE to "Old title",
            TagField.ARTIST to "Old artist",
        )
        val draft = MetadataDraftPlanner.draft(original) + mapOf(
            TagField.TITLE to "New title",
            TagField.ARTIST to "",
        )

        val patch = MetadataDraftPlanner.patch(original, draft)

        assertEquals(TagMutation.Set("New title"), patch.mutations[TagField.TITLE])
        assertEquals(TagMutation.Clear, patch.mutations[TagField.ARTIST])
        assertEquals(TagMutation.Keep, patch.mutations[TagField.ALBUM])
    }

    @Test
    fun searchUsesFilenameFallbackWithoutInventingArtist() {
        val query = MetadataDraftPlanner.searchQuery(track, TagSnapshot("WAV"), emptyMap())

        assertEquals("Fallback title", query.title)
        assertEquals(null, query.artist)
        assertEquals(61_000L, query.durationMillis)
    }

    @Test
    fun batchCommonAndSequenceOverrideCandidateWhileBlankIsSafe() {
        val original = snapshot(TagField.TITLE to "Original", TagField.ALBUM to "Old album")
        val candidate = MetadataCandidate(
            id = "candidate",
            provider = MetadataProvenance.MUSICBRAINZ,
            score = 0.9,
            fields = mapOf(
                TagField.TITLE to MetadataValue("Suggested", MetadataProvenance.MUSICBRAINZ),
                TagField.ALBUM to MetadataValue("Suggested album", MetadataProvenance.MUSICBRAINZ),
            ),
        )

        val patch = MetadataDraftPlanner.batchPatch(
            snapshot = original,
            candidate = candidate,
            acceptedCandidateFields = setOf(TagField.TITLE, TagField.ALBUM),
            commonFields = mapOf(
                TagField.ALBUM to BatchFieldDraft(enabled = true, value = "Common album"),
                TagField.ARTIST to BatchFieldDraft(enabled = true, value = ""),
            ),
            sequenceNumber = 4,
            sequenceTotal = 9,
        )

        assertEquals(TagMutation.Set("Suggested"), patch.mutations[TagField.TITLE])
        assertEquals(TagMutation.Set("Common album"), patch.mutations[TagField.ALBUM])
        assertEquals(TagMutation.Keep, patch.mutations[TagField.ARTIST])
        assertEquals(TagMutation.Set("4"), patch.mutations[TagField.TRACK_NUMBER])
        assertEquals(TagMutation.Set("9"), patch.mutations[TagField.TRACK_TOTAL])
        assertTrue(TagField.ARTIST !in patch.changedFields(original))
    }

    @Test
    fun explicitBatchClearRemovesField() {
        val original = snapshot(TagField.GENRE to "Field recording")

        val patch = MetadataDraftPlanner.batchPatch(
            snapshot = original,
            candidate = null,
            acceptedCandidateFields = emptySet(),
            commonFields = mapOf(TagField.GENRE to BatchFieldDraft(enabled = true, clear = true)),
            sequenceNumber = null,
            sequenceTotal = null,
        )

        assertEquals(TagMutation.Clear, patch.mutations[TagField.GENRE])
    }

    private fun snapshot(vararg values: Pair<TagField, String>) = TagSnapshot(
        format = "ID3v2.4",
        fields = values.associate { (field, value) ->
            field to MetadataValue(value, MetadataProvenance.EMBEDDED)
        },
    )
}
