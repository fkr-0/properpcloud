package dev.properpcloud.app.metadata

import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MetadataCandidate
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagMutation
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.core.model.TagSnapshot
import dev.properpcloud.metadata.online.MetadataSearchQuery

data class BatchFieldDraft(
    val enabled: Boolean = false,
    val value: String = "",
    val clear: Boolean = false,
)

object MetadataDraftPlanner {
    val editableFields: List<TagField> = listOf(
        TagField.TITLE,
        TagField.ARTIST,
        TagField.ALBUM,
        TagField.ALBUM_ARTIST,
        TagField.GENRE,
        TagField.YEAR,
        TagField.TRACK_NUMBER,
        TagField.TRACK_TOTAL,
        TagField.DISC_NUMBER,
        TagField.DISC_TOTAL,
        TagField.COMPOSER,
        TagField.COMMENT,
        TagField.ISRC,
        TagField.MUSICBRAINZ_RECORDING_ID,
        TagField.MUSICBRAINZ_RELEASE_ID,
        TagField.LYRICS,
    )

    val commonBatchFields: List<TagField> = listOf(
        TagField.ARTIST,
        TagField.ALBUM,
        TagField.ALBUM_ARTIST,
        TagField.GENRE,
        TagField.YEAR,
        TagField.DISC_NUMBER,
        TagField.DISC_TOTAL,
        TagField.COMPOSER,
    )

    val onlineCandidateFields: Set<TagField> = setOf(
        TagField.TITLE,
        TagField.ARTIST,
        TagField.ALBUM,
        TagField.YEAR,
        TagField.MUSICBRAINZ_RECORDING_ID,
        TagField.MUSICBRAINZ_RELEASE_ID,
    )

    fun draft(snapshot: TagSnapshot): Map<TagField, String> = editableFields.associateWith { field ->
        snapshot.fields[field]?.value.orEmpty()
    }

    fun patch(snapshot: TagSnapshot, draft: Map<TagField, String>): TagPatch = TagPatch(
        editableFields.associateWith { field ->
            val current = snapshot.fields[field]?.value.orEmpty()
            val proposed = draft[field].orEmpty().trim()
            when {
                proposed == current -> TagMutation.Keep
                proposed.isEmpty() -> if (current.isEmpty()) TagMutation.Keep else TagMutation.Clear
                else -> TagMutation.Set(proposed)
            }
        },
    )

    fun searchQuery(
        track: AudioTrack,
        snapshot: TagSnapshot,
        draft: Map<TagField, String>,
    ): MetadataSearchQuery = MetadataSearchQuery(
        title = draft[TagField.TITLE].nonBlank()
            ?: snapshot.fields[TagField.TITLE]?.value
            ?: track.taggedTitle
            ?: track.filenameStem,
        artist = draft[TagField.ARTIST].nonBlank() ?: snapshot.fields[TagField.ARTIST]?.value,
        album = draft[TagField.ALBUM].nonBlank() ?: snapshot.fields[TagField.ALBUM]?.value,
        isrc = draft[TagField.ISRC].nonBlank() ?: snapshot.fields[TagField.ISRC]?.value,
        durationMillis = track.durationMillis,
    )

    fun applyCandidate(
        draft: Map<TagField, String>,
        candidate: MetadataCandidate,
        acceptedFields: Set<TagField>,
    ): Map<TagField, String> = draft.toMutableMap().apply {
        acceptedFields.forEach { field -> candidate.fields[field]?.value?.let { put(field, it) } }
    }

    fun batchPatch(
        snapshot: TagSnapshot,
        candidate: MetadataCandidate?,
        acceptedCandidateFields: Set<TagField>,
        commonFields: Map<TagField, BatchFieldDraft>,
        sequenceNumber: Int?,
        sequenceTotal: Int?,
    ): TagPatch {
        val mutations = editableFields.associateWithTo(linkedMapOf()) { TagMutation.Keep as TagMutation }
        candidate?.let { selected ->
            acceptedCandidateFields.forEach { field ->
                selected.fields[field]?.value?.let { mutations[field] = TagMutation.Set(it) }
            }
        }
        commonFields.forEach { (field, edit) ->
            if (edit.enabled) {
                mutations[field] = when {
                    edit.clear -> TagMutation.Clear
                    edit.value.isNotBlank() -> TagMutation.Set(edit.value.trim())
                    else -> TagMutation.Keep
                }
            }
        }
        sequenceNumber?.let { mutations[TagField.TRACK_NUMBER] = TagMutation.Set(it.toString()) }
        sequenceTotal?.let { mutations[TagField.TRACK_TOTAL] = TagMutation.Set(it.toString()) }

        val draft = editableFields.associateWith { field ->
            when (val mutation = mutations.getValue(field)) {
                TagMutation.Keep -> snapshot.fields[field]?.value.orEmpty()
                TagMutation.Clear -> ""
                is TagMutation.Set -> mutation.value
            }
        }
        return patch(snapshot, draft)
    }
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotBlank)
