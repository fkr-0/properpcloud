package dev.properpcloud.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataModelTest {
    private val sourceId = SourceId("pcloud")

    @Test
    fun candidatePlanChangesOnlyAcceptedFields() {
        val input = input(
            node = "file:1",
            fields = mapOf(TagField.TITLE to embedded("Old title"), TagField.ARTIST to embedded("Artist")),
        )
        val candidate = MetadataCandidate(
            id = "recording-id",
            provider = MetadataProvenance.MUSICBRAINZ,
            score = 0.95,
            fields = mapOf(
                TagField.TITLE to MetadataValue("New title", MetadataProvenance.MUSICBRAINZ, 0.95),
                TagField.ALBUM to MetadataValue("Album", MetadataProvenance.MUSICBRAINZ, 0.9),
            ),
        )

        val plan = BatchTagPlanner.fromCandidate(input, candidate, setOf(TagField.TITLE))

        assertEquals(setOf(TagField.TITLE), plan.changedFields)
        assertEquals("recording-id", plan.candidateId)
    }

    @Test
    fun onlineCandidateCannotBecomeAPlanWithoutExplicitFieldSelection() {
        val input = input("file:review")
        val candidate = MetadataCandidate(
            id = "recording-review",
            provider = MetadataProvenance.MUSICBRAINZ,
            score = 0.99,
            fields = mapOf(TagField.TITLE to MetadataValue("Suggested", MetadataProvenance.MUSICBRAINZ)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            BatchTagPlanner.fromCandidate(input, candidate, emptySet())
        }
    }

    @Test
    fun trackSequencingIsDeterministicAndIncludesTotal() {
        val batch = BatchTagPlanner.sequenceTracks(
            listOf(input("file:1"), input("file:2"), input("file:3")),
            startAt = 4,
        )

        assertEquals(3, batch.changedItemCount)
        assertEquals(TagMutation.Set("4"), batch.plans[0].patch.mutations[TagField.TRACK_NUMBER])
        assertEquals(TagMutation.Set("6"), batch.plans[2].patch.mutations[TagField.TRACK_NUMBER])
        assertEquals(TagMutation.Set("6"), batch.plans[1].patch.mutations[TagField.TRACK_TOTAL])
    }

    @Test
    fun commonPatchRetainsNoOpItemsWithoutPretendingTheyChanged() {
        val batch = BatchTagPlanner.commonPatch(
            listOf(input("file:1", mapOf(TagField.ALBUM to embedded("Same")))),
            mapOf(TagField.ALBUM to TagMutation.Set("Same")),
        )

        assertEquals(0, batch.changedItemCount)
        assertTrue(batch.plans.single().changedFields.isEmpty())
    }

    @Test
    fun remotePlanRequiresRevisionOrHash() {
        assertThrows(IllegalArgumentException::class.java) {
            TagEditPlan(
                sourceId = sourceId,
                nodeId = NodeId("file:1"),
                original = TagSnapshot("ID3v2.4"),
                patch = TagPatch(mapOf(TagField.TITLE to TagMutation.Set("Title"))),
            )
        }
    }

    private fun input(
        node: String,
        fields: Map<TagField, MetadataValue> = emptyMap(),
    ) = TagEditPlanInput(
        sourceId = sourceId,
        nodeId = NodeId(node),
        expectedContentHash = "hash-$node",
        snapshot = TagSnapshot("ID3v2.4", fields),
    )

    private fun embedded(value: String) = MetadataValue(value, MetadataProvenance.EMBEDDED)
}
