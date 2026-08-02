package dev.properpcloud.app.ui

import dev.properpcloud.app.metadata.BatchFieldDraft
import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.core.model.MetadataCandidate
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagSnapshot

enum class MetadataPhase {
    READY,
    SEARCHING,
    STAGING,
    STAGED,
}

data class MetadataArtifactUi(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val itemCount: Int,
    val sha256: String,
)

sealed interface MetadataEditorUiState {
    data class Loading(
        val title: String,
        val completed: Int = 0,
        val total: Int = 1,
    ) : MetadataEditorUiState

    data class Failure(
        val title: String,
        val message: String,
    ) : MetadataEditorUiState

    data class Single(
        val track: AudioTrack,
        val original: TagSnapshot,
        val draft: Map<TagField, String>,
        val sourceRevision: String?,
        val sourceHash: String,
        val phase: MetadataPhase = MetadataPhase.READY,
        val candidates: List<MetadataCandidate> = emptyList(),
        val selectedCandidateId: String? = null,
        val acceptedCandidateFields: Set<TagField> = emptySet(),
        val artifact: MetadataArtifactUi? = null,
        val status: String? = null,
    ) : MetadataEditorUiState

    data class BatchItem(
        val track: AudioTrack,
        val original: TagSnapshot,
        val candidates: List<MetadataCandidate> = emptyList(),
        val selectedCandidateId: String? = null,
        val acceptedCandidateFields: Set<TagField> = emptySet(),
        val status: String? = null,
    )

    data class Batch(
        val items: List<BatchItem>,
        val commonFields: Map<TagField, BatchFieldDraft>,
        val sequenceTracks: Boolean = false,
        val sequenceStart: String = "1",
        val includeTrackTotal: Boolean = true,
        val phase: MetadataPhase = MetadataPhase.READY,
        val progressCompleted: Int = 0,
        val progressTotal: Int = items.size,
        val artifact: MetadataArtifactUi? = null,
        val status: String? = null,
    ) : MetadataEditorUiState
}
