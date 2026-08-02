package dev.properpcloud.core.model

import java.util.UUID

enum class TagField {
    TITLE,
    ARTIST,
    ALBUM,
    ALBUM_ARTIST,
    GENRE,
    YEAR,
    TRACK_NUMBER,
    TRACK_TOTAL,
    DISC_NUMBER,
    DISC_TOTAL,
    COMMENT,
    COMPOSER,
    ISRC,
    MUSICBRAINZ_RECORDING_ID,
    MUSICBRAINZ_RELEASE_ID,
    LYRICS,
}

enum class MetadataProvenance {
    PROVIDER,
    EMBEDDED,
    FILENAME,
    PATH,
    MUSICBRAINZ,
    ACOUSTID,
    USER,
}

data class MetadataValue(
    val value: String,
    val provenance: MetadataProvenance,
    val confidence: Double = 1.0,
) {
    init {
        require(value.isNotBlank()) { "metadata value must not be blank" }
        require(confidence in 0.0..1.0) { "confidence must be between zero and one" }
    }
}

data class ArtworkSummary(
    val mimeType: String?,
    val byteCount: Long,
    val width: Int? = null,
    val height: Int? = null,
    val description: String? = null,
) {
    init {
        require(byteCount >= 0) { "artwork byte count must not be negative" }
        require(width == null || width > 0) { "artwork width must be positive" }
        require(height == null || height > 0) { "artwork height must be positive" }
    }
}

data class TagSnapshot(
    val format: String,
    val fields: Map<TagField, MetadataValue> = emptyMap(),
    val artwork: List<ArtworkSummary> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    init {
        require(format.isNotBlank()) { "tag format must not be blank" }
    }
}

sealed interface TagMutation {
    data object Keep : TagMutation
    data object Clear : TagMutation
    data class Set(val value: String) : TagMutation {
        init {
            require(value.isNotBlank()) { "tag mutation value must not be blank" }
        }
    }
}

data class TagPatch(
    val mutations: Map<TagField, TagMutation>,
) {
    init {
        require(mutations.isNotEmpty()) { "tag patch must contain at least one mutation" }
    }

    fun changedFields(snapshot: TagSnapshot): Set<TagField> = mutations.mapNotNullTo(linkedSetOf()) { (field, mutation) ->
        val current = snapshot.fields[field]?.value
        when (mutation) {
            TagMutation.Keep -> null
            TagMutation.Clear -> field.takeIf { current != null }
            is TagMutation.Set -> field.takeIf { current != mutation.value }
        }
    }
}

data class TagEditPlan(
    val planId: String = UUID.randomUUID().toString(),
    val sourceId: SourceId,
    val nodeId: NodeId,
    val expectedRevision: String? = null,
    val expectedContentHash: String? = null,
    val original: TagSnapshot,
    val patch: TagPatch,
    val candidateId: String? = null,
) {
    init {
        require(planId.isNotBlank()) { "plan id must not be blank" }
        require(expectedRevision != null || expectedContentHash != null) {
            "an expected revision or content hash is required before a remote mutation"
        }
    }

    val changedFields: Set<TagField> = patch.changedFields(original)
}

data class BatchTagEditPlan(
    val plans: List<TagEditPlan>,
    val warnings: List<String> = emptyList(),
) {
    init {
        require(plans.isNotEmpty()) { "batch edit plan must contain at least one item" }
        require(plans.map { it.sourceId to it.nodeId }.distinct().size == plans.size) {
            "batch edit plan must not contain duplicate media identities"
        }
    }

    val changedItemCount: Int = plans.count { it.changedFields.isNotEmpty() }
    val changedFieldCount: Int = plans.sumOf { it.changedFields.size }
}

data class MetadataCandidate(
    val id: String,
    val provider: MetadataProvenance,
    val score: Double,
    val fields: Map<TagField, MetadataValue>,
    val releaseId: String? = null,
    val coverArtUrl: String? = null,
) {
    init {
        require(id.isNotBlank()) { "candidate id must not be blank" }
        require(provider == MetadataProvenance.MUSICBRAINZ || provider == MetadataProvenance.ACOUSTID) {
            "online candidate must come from MusicBrainz or AcoustID"
        }
        require(score in 0.0..1.0) { "candidate score must be between zero and one" }
    }
}

object BatchTagPlanner {
    fun commonPatch(
        items: List<TagEditPlanInput>,
        mutations: Map<TagField, TagMutation>,
    ): BatchTagEditPlan = BatchTagEditPlan(
        plans = items.map { input ->
            TagEditPlan(
                sourceId = input.sourceId,
                nodeId = input.nodeId,
                expectedRevision = input.expectedRevision,
                expectedContentHash = input.expectedContentHash,
                original = input.snapshot,
                patch = TagPatch(mutations),
            )
        },
    )

    fun sequenceTracks(
        items: List<TagEditPlanInput>,
        startAt: Int = 1,
        includeTotal: Boolean = true,
    ): BatchTagEditPlan {
        require(startAt > 0) { "track sequence must start above zero" }
        val total = startAt + items.size - 1
        return BatchTagEditPlan(
            plans = items.mapIndexed { index, input ->
                val mutations = linkedMapOf<TagField, TagMutation>(
                    TagField.TRACK_NUMBER to TagMutation.Set((startAt + index).toString()),
                )
                if (includeTotal) mutations[TagField.TRACK_TOTAL] = TagMutation.Set(total.toString())
                TagEditPlan(
                    sourceId = input.sourceId,
                    nodeId = input.nodeId,
                    expectedRevision = input.expectedRevision,
                    expectedContentHash = input.expectedContentHash,
                    original = input.snapshot,
                    patch = TagPatch(mutations),
                )
            },
        )
    }

    fun fromCandidate(
        input: TagEditPlanInput,
        candidate: MetadataCandidate,
        acceptedFields: Set<TagField> = candidate.fields.keys,
    ): TagEditPlan {
        val patch = acceptedFields.associateWith { field ->
            candidate.fields[field]?.value?.let(TagMutation::Set) ?: TagMutation.Keep
        }
        return TagEditPlan(
            sourceId = input.sourceId,
            nodeId = input.nodeId,
            expectedRevision = input.expectedRevision,
            expectedContentHash = input.expectedContentHash,
            original = input.snapshot,
            patch = TagPatch(patch),
            candidateId = candidate.id,
        )
    }
}

data class TagEditPlanInput(
    val sourceId: SourceId,
    val nodeId: NodeId,
    val expectedRevision: String? = null,
    val expectedContentHash: String? = null,
    val snapshot: TagSnapshot,
) {
    init {
        require(expectedRevision != null || expectedContentHash != null) {
            "an expected revision or content hash is required"
        }
    }
}
