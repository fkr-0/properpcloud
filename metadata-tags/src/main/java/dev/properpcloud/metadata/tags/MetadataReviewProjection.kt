package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.FieldDecision
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.TagField
import java.io.File

/** Semantic presence for an Earlier/Later value; UI text is never used as review authority. */
enum class FolderTagReviewValueKind {
    EMPTY,
    PRESENT,
}

data class FolderTagReviewValue(
    val value: String?,
    val kind: FolderTagReviewValueKind,
) {
    init {
        require((kind == FolderTagReviewValueKind.EMPTY) == value.isNullOrBlank()) {
            "tag review value kind must match value presence"
        }
    }

    companion object {
        fun of(value: String?): FolderTagReviewValue = FolderTagReviewValue(
            value = value?.takeUnless(String::isBlank),
            kind = if (value.isNullOrBlank()) FolderTagReviewValueKind.EMPTY else FolderTagReviewValueKind.PRESENT,
        )
    }
}

/** Explicit transition classification so destructive/empty changes cannot be rendered ambiguously. */
enum class FolderTagReviewTransition {
    CHANGED,
    ADDED_FROM_EMPTY,
    REMOVAL_TO_EMPTY,
    UNCHANGED,
}

data class FolderTagFieldReview(
    val field: TagField,
    val earlier: FolderTagReviewValue,
    val later: FolderTagReviewValue,
    val transition: FolderTagReviewTransition,
    val decision: FieldDecision,
    val ruleId: String,
    val confidence: Double,
    val explanation: String,
    val warnings: List<String>,
    val conflictsWithExistingValue: Boolean,
)

data class FolderTagFileReview(
    val nodeId: NodeId,
    /** Portable path relative to the selected workbench root; never an absolute private path. */
    val relativePath: String,
    val filename: String,
    val fields: List<FolderTagFieldReview>,
)

/** Frozen review corresponding exactly to one [ReviewedFolderTagBatch] revision and plan. */
data class FolderTagReviewProjection(
    val revision: Long,
    val files: List<FolderTagFileReview>,
) {
    val changedFieldCount: Int get() = files.sumOf { file ->
        file.fields.count { it.transition != FolderTagReviewTransition.UNCHANGED }
    }

    val hasDestructiveChanges: Boolean get() = files.any { file ->
        file.fields.any { it.transition == FolderTagReviewTransition.REMOVAL_TO_EMPTY }
    }
}

data class FolderPlaylistFileReview(
    /** Portable target path relative to the selected playlist root, always beginning with ./ . */
    val targetRelativePath: String,
    /** Every exact final M3U8 line in file order, excluding only line terminator bytes. */
    val finalLines: List<String>,
    /** Exact media entry lines in final order; each remains portable and begins with ./ . */
    val entryLines: List<String>,
)

/** Exact, revision-bound derived-playlist checkpoint shared by desktop and CLI surfaces. */
data class FolderPlaylistReviewProjection(
    val revision: Long,
    val order: FolderPlaylistOrder,
    val recursive: Boolean,
    val onePlaylistPerAlbum: Boolean,
    val files: List<FolderPlaylistFileReview>,
) {
    val playlistCount: Int get() = files.size
    val entryCount: Int get() = files.sumOf { it.entryLines.size }
}

internal fun tagReviewProjection(
    revision: Long,
    plan: FolderTagBatchPlan,
): FolderTagReviewProjection = FolderTagReviewProjection(
    revision = revision,
    files = plan.items.map { item ->
        val approval = item.approval
        val fields = approval.approvedFields.values
            .sortedBy { it.field.name }
            .map { edit ->
                val earlier = FolderTagReviewValue.of(approval.originalSnapshot.fields[edit.field]?.value)
                val later = FolderTagReviewValue.of(
                    when (edit.decision) {
                        FieldDecision.KEEP -> earlier.value
                        FieldDecision.CLEAR -> null
                        FieldDecision.SET -> edit.finalValue
                    },
                )
                val transition = when {
                    earlier.kind == FolderTagReviewValueKind.EMPTY && later.kind == FolderTagReviewValueKind.PRESENT ->
                        FolderTagReviewTransition.ADDED_FROM_EMPTY
                    earlier.kind == FolderTagReviewValueKind.PRESENT && later.kind == FolderTagReviewValueKind.EMPTY ->
                        FolderTagReviewTransition.REMOVAL_TO_EMPTY
                    earlier.value == later.value -> FolderTagReviewTransition.UNCHANGED
                    else -> FolderTagReviewTransition.CHANGED
                }
                FolderTagFieldReview(
                    field = edit.field,
                    earlier = earlier,
                    later = later,
                    transition = transition,
                    decision = edit.decision,
                    ruleId = edit.proposal.ruleId,
                    confidence = edit.proposal.confidence,
                    explanation = edit.proposal.explanation,
                    warnings = edit.proposal.warnings,
                    conflictsWithExistingValue = edit.proposal.conflictsWithExistingValue,
                )
            }
        FolderTagFileReview(
            nodeId = approval.identity.nodeId,
            relativePath = item.relativePath,
            filename = approval.identity.filename,
            fields = fields,
        )
    },
)

internal fun playlistReviewProjection(
    revision: Long,
    plan: FolderPlaylistPlan,
): FolderPlaylistReviewProjection = FolderPlaylistReviewProjection(
    revision = revision,
    order = plan.order,
    recursive = false,
    onePlaylistPerAlbum = false,
    files = listOf(playlistFileReview(plan.directory.canonicalFile, plan)),
)

internal fun playlistReviewProjection(
    revision: Long,
    plan: FolderPlaylistBatchPlan,
): FolderPlaylistReviewProjection = FolderPlaylistReviewProjection(
    revision = revision,
    order = plan.order,
    recursive = plan.recursive,
    onePlaylistPerAlbum = plan.onePlaylistPerAlbum,
    files = plan.playlists.map { playlist -> playlistFileReview(plan.rootDirectory.canonicalFile, playlist) },
)

private fun playlistFileReview(root: File, plan: FolderPlaylistPlan): FolderPlaylistFileReview {
    val target = plan.directory.canonicalFile.toPath().resolve(plan.fileName).normalize()
    val relative = root.toPath().relativize(target).toString().replace(File.separatorChar, '/')
    require(relative.isNotBlank() && relative != "." && relative != ".." && !relative.startsWith("../")) {
        "playlist review target escaped the selected root"
    }
    require(!File(relative).isAbsolute) { "playlist review target must remain relative" }
    require(plan.relativeEntries.all { it.startsWith("./") && !it.startsWith("./../") }) {
        "playlist review contains a non-portable media entry"
    }
    return FolderPlaylistFileReview(
        targetRelativePath = "./$relative",
        finalLines = plan.extendedM3u.removeSuffix("\n").split('\n'),
        entryLines = plan.relativeEntries,
    )
}
