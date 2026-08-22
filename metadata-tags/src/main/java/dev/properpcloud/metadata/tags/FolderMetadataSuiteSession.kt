package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.ApplyResultStatus
import dev.properpcloud.core.model.FileApproval
import dev.properpcloud.core.model.FileApplyResult
import dev.properpcloud.core.model.FolderTagSnapshot
import java.io.File

/**
 * Presentation-friendly state for one selected local metadata workbench session.
 *
 * This is deliberately a coordination boundary rather than a filesystem watcher. A client
 * watcher calls [invalidateForFilesystemChange] when it observes a relevant source event, then
 * runs a fresh [previewTree] after its own event drain/reconciliation barrier. The session
 * makes reviews minted before that signal unusable and cancels queued derived-playlist work;
 * it never turns a watcher signal into a tag write.
 */
data class FolderMetadataSuiteStatus(
    val revision: Long,
    val reconciliationRequired: Boolean,
    val folderCount: Int,
    val fileCount: Int,
    val pendingPlaylistRegenerations: Int,
    val message: String,
)

/** Result shape suitable for a UI, CLI, or agent surface without exception-only feedback. */
data class FolderMetadataSuiteOperation<T>(
    val value: T?,
    val message: String,
    val reconciliationRequired: Boolean = false,
) {
    val succeeded: Boolean get() = value != null
}

data class ReviewedFolderApproval internal constructor(
    internal val revision: Long,
    val approval: FileApproval,
)

data class ReviewedFolderTagBatch internal constructor(
    internal val revision: Long,
    val plan: FolderTagBatchPlan,
)

data class ReviewedFolderPlaylist internal constructor(
    internal val revision: Long,
    val plan: FolderPlaylistPlan,
)

data class ReviewedFolderPlaylistBatch internal constructor(
    internal val revision: Long,
    val plan: FolderPlaylistBatchPlan,
)

/**
 * User/agent-facing local-filesystem application boundary for the metadata suite.
 *
 * The lower-level workflow deliberately remains reusable and side-effect explicit. This
 * session adds the missing application semantics around it:
 * - every approval or playlist materialization is tied to one current preview revision;
 * - direct and recursive playlist plans are review-first and require explicit confirmation;
 * - recursive playlist opt-in and recursive tag-write opt-in are separate parameters;
 * - a filesystem reconciliation signal revokes old approvals/reviews and queued regeneration;
 * - post-sync automation exposes playlist regeneration only, never tag staging/apply.
 *
 * Android prepared-copy exports do not use this class because they do not own a writable local
 * library root. A Linux/local client should instantiate it only after establishing that root
 * capability and wiring its real watcher/reconciliation adapter.
 */
class FolderMetadataSuiteSession(
    private val workflow: FolderAutoTagWorkflow,
    private val regeneration: FolderPlaylistRegenerationService = FolderPlaylistRegenerationService(),
) {
    private val lock = Any()
    private var revision = 0L
    private var currentPreview: FolderTreeTagPreview? = null
    private var reconciliationRequired = false
    private var message = "Preview a selected local folder before reviewing metadata changes."

    fun status(): FolderMetadataSuiteStatus {
        val snapshot = synchronized(lock) {
            val preview = currentPreview
            FolderMetadataSuiteStatus(
                revision = revision,
                reconciliationRequired = reconciliationRequired,
                folderCount = preview?.folderCount ?: 0,
                fileCount = preview?.fileCount ?: 0,
                pendingPlaylistRegenerations = 0,
                message = message,
            )
        }
        return snapshot.copy(pendingPlaylistRegenerations = regeneration.pendingCount())
    }

    internal fun currentPreviewSnapshot(): FolderTreeTagPreview? = synchronized(lock) { currentPreview }

    /**
     * Scan/reconcile one direct folder or explicitly selected tree. If an adapter reports a
     * source event while scanning, the result is not published as current; the caller must
     * drain/reconcile and retry. This provides the generation barrier but does not pretend to
     * implement the platform observer registration/drain protocol itself.
     */
    suspend fun previewTree(
        command: FolderTreeTagPreviewCommand,
    ): FolderMetadataSuiteOperation<FolderTreeTagPreview> {
        val startedRevision = synchronized(lock) { revision }
        val preview = try {
            workflow.previewTree(command)
        } catch (error: RuntimeException) {
            return failure(error.message ?: "Folder preview failed.")
        }

        return synchronized(lock) {
            if (revision != startedRevision) {
                FolderMetadataSuiteOperation(
                    value = null,
                    message = "The selected folder changed while it was being scanned; reconcile and preview again.",
                    reconciliationRequired = true,
                )
            } else {
                revision = Math.addExact(revision, 1L)
                currentPreview = preview
                reconciliationRequired = false
                message = "Previewed ${preview.fileCount} audio file(s) in ${preview.folderCount} folder(s)."
                FolderMetadataSuiteOperation(preview, message)
            }
        }
    }

    suspend fun reconcile(
        command: FolderTreeTagPreviewCommand,
    ): FolderMetadataSuiteOperation<FolderTreeTagPreview> = previewTree(command)

    /**
     * Adapter hook for a relevant local watcher/source event. It only invalidates review state
     * and queued derived playlist work. It never scans, stages, or applies tags.
     */
    fun invalidateForFilesystemChange(reason: String): FolderMetadataSuiteStatus {
        require(reason.isNotBlank()) { "reconciliation reason must not be blank" }
        synchronized(lock) {
            revision = Math.addExact(revision, 1L)
            currentPreview = null
            reconciliationRequired = true
            message = "$reason Reconcile and preview again before approving or writing anything."
        }
        regeneration.cancelAll()
        return status()
    }

    fun approveCandidate(
        command: ApproveCandidateCommand,
    ): FolderMetadataSuiteOperation<ReviewedFolderApproval> = reviewApproval(command.snapshot) {
        workflow.approveCandidate(command)
    }

    fun approveLocalProposals(
        command: ApproveLocalProposalsCommand,
    ): FolderMetadataSuiteOperation<ReviewedFolderApproval> = reviewApproval(command.snapshot) {
        workflow.approveLocalProposals(command)
    }

    /** Freeze explicit tag approvals. Recursive mutation requires its own independent opt-in. */
    fun reviewTagBatch(
        approvals: List<ReviewedFolderApproval>,
        recursiveTagOptIn: Boolean = false,
    ): FolderMetadataSuiteOperation<ReviewedFolderTagBatch> {
        val captured = captureCurrentPreview()
            ?: return noCurrentPreview("Preview/reconcile the folder before reviewing tag writes.")
        val (reviewRevision, preview) = captured
        if (approvals.any { it.revision != reviewRevision }) {
            return staleReview("One or more tag approvals belong to an older folder preview.")
        }
        return try {
            val plan = workflow.planBatch(preview, approvals.map(ReviewedFolderApproval::approval), recursiveTagOptIn)
            issueAtRevision(reviewRevision, ReviewedFolderTagBatch(reviewRevision, plan), "Tag batch is ready for dry-run review.")
        } catch (error: RuntimeException) {
            failure(error.message ?: "Could not build the tag batch review.")
        }
    }

    /**
     * Execute an exact reviewed tag plan. Dry-run remains the default. Any real successful
     * mutation invalidates the session afterwards so playlist planning must use a verified
     * post-write rescan rather than pre-write tag evidence.
     */
    fun executeTagBatch(
        review: ReviewedFolderTagBatch,
        stagingDirectory: File,
        dryRun: Boolean = true,
        confirmWrite: Boolean = false,
        onProgress: (FolderTagBatchProgress) -> Unit = {},
    ): FolderMetadataSuiteOperation<FolderTagBatchExecutionResult> {
        if (!reviewIsCurrent(review.revision)) {
            return staleReview("The tag batch review is stale; reconcile and preview again.")
        }
        val result = try {
            workflow.executeBatchPlan(review.plan, stagingDirectory, dryRun, confirmWrite, onProgress)
        } catch (error: RuntimeException) {
            return failure(error.message ?: "Tag batch execution failed.")
        }

        val stale = result.preflight.any { !it.ready } ||
            result.results.any { it.status == ApplyResultStatus.CONFLICTED || it.status == ApplyResultStatus.INDETERMINATE }
        if (stale) {
            invalidateForFilesystemChange("Tag review evidence is no longer current.")
            return FolderMetadataSuiteOperation(
                value = result,
                message = "Tag batch found stale or indeterminate source evidence; reconcile before continuing.",
                reconciliationRequired = true,
            )
        }
        if (!dryRun && result.results.isNotEmpty()) {
            invalidateForFilesystemChange("Confirmed tag work changed or revalidated source media.")
            return FolderMetadataSuiteOperation(
                value = result,
                message = "Tag batch completed; reconcile the resulting media before deriving playlists.",
                reconciliationRequired = true,
            )
        }
        return FolderMetadataSuiteOperation(result, "Tag batch dry-run completed without mutating media.")
    }

    /**
     * Restore one exact verified apply result. Recovery deliberately accepts no force flag:
     * [FolderTagApplyService] rehashes the current file against the previously verified result
     * and refuses to overwrite a later edit. An indeterminate apply is recoverable here only
     * when the replacement path proved that the exact staged candidate is currently present;
     * native-desktop restart recovery reconstructs exactly such a guarded result only after an
     * explicitly reselected root proves target/result/original hashes from durable sibling evidence.
     */
    fun rollbackTagResult(
        result: FileApplyResult,
    ): FolderMetadataSuiteOperation<FileApplyResult> {
        val guardedStatus = result.status == ApplyResultStatus.VERIFIED || result.status == ApplyResultStatus.INDETERMINATE
        if (!guardedStatus || result.resultSha256 == null || result.rollbackFile == null) {
            return failure("Rollback requires proven current-result bytes and exact retained rollback bytes.")
        }
        val rolledBack = try {
            workflow.rollback(result)
        } catch (error: RuntimeException) {
            return failure(error.message ?: "Rollback could not be attempted.")
        }
        invalidateForFilesystemChange("A guarded local tag rollback was attempted.")
        return FolderMetadataSuiteOperation(
            value = rolledBack,
            message = "Rollback ${rolledBack.status.name.lowercase()}: ${rolledBack.message}",
            reconciliationRequired = true,
        )
    }

    /** Build a direct-folder derived playlist review. No filesystem bytes change here. */
    fun reviewDirectPlaylist(
        order: FolderPlaylistOrder = FolderPlaylistOrder.TAG_TRACK_NUMBER,
    ): FolderMetadataSuiteOperation<ReviewedFolderPlaylist> {
        val captured = captureCurrentPreview()
            ?: return noCurrentPreview("Preview/reconcile the folder before reviewing a playlist.")
        val (reviewRevision, preview) = captured
        if (preview.recursive || preview.snapshots.size != 1) {
            return failure("Direct playlist review requires a non-recursive one-folder preview.")
        }
        return try {
            val plan = workflow.planPlaylist(FolderPlaylistWriteCommand(preview.snapshots.single(), order))
            issueAtRevision(reviewRevision, ReviewedFolderPlaylist(reviewRevision, plan), playlistReviewMessage(plan))
        } catch (error: RuntimeException) {
            failure(error.message ?: "Could not build the playlist review.")
        }
    }

    /** Materialize only the exact direct-folder review after explicit confirmation. */
    fun materializePlaylist(
        review: ReviewedFolderPlaylist,
        confirmWrite: Boolean,
    ): FolderMetadataSuiteOperation<FolderPlaylistWriteResult> {
        if (!confirmWrite) return failure("Playlist materialization requires explicit confirmation.")
        if (!reviewIsCurrent(review.revision)) {
            return staleReview("The playlist review is stale; reconcile and preview again.")
        }
        return try {
            val result = workflow.writePlaylist(review.plan)
            if (!reviewIsCurrent(review.revision)) {
                FolderMetadataSuiteOperation(
                    value = result,
                    message = "Playlist was derived from reviewed evidence, but the folder changed during materialization; reconcile before continuing.",
                    reconciliationRequired = true,
                )
            } else {
                FolderMetadataSuiteOperation(result, "Wrote ${result.entryCount} reviewed playlist entr${if (result.entryCount == 1) "y" else "ies"}.")
            }
        } catch (error: RuntimeException) {
            materializationFailure(error, "Playlist materialization failed")
        }
    }

    /**
     * Build a direct or recursive playlist batch review. [recursivePlaylistOptIn] is never
     * reused as the recursive tag-write opt-in.
     */
    fun reviewPlaylistBatch(
        recursivePlaylistOptIn: Boolean = false,
        onePlaylistPerAlbum: Boolean = false,
        order: FolderPlaylistOrder = FolderPlaylistOrder.TAG_TRACK_NUMBER,
    ): FolderMetadataSuiteOperation<ReviewedFolderPlaylistBatch> {
        val captured = captureCurrentPreview()
            ?: return noCurrentPreview("Preview/reconcile the folder before reviewing playlist generation.")
        val (reviewRevision, preview) = captured
        return try {
            val plan = workflow.planPlaylistBatch(
                preview = preview,
                recursiveOptIn = recursivePlaylistOptIn,
                onePlaylistPerAlbum = onePlaylistPerAlbum,
                order = order,
            )
            issueAtRevision(
                reviewRevision,
                ReviewedFolderPlaylistBatch(reviewRevision, plan),
                "Playlist batch review contains ${plan.playlistCount} derived file(s) and ${plan.entryCount} media entr${if (plan.entryCount == 1) "y" else "ies"}.",
            )
        } catch (error: RuntimeException) {
            failure(error.message ?: "Could not build the playlist batch review.")
        }
    }

    /** Materialize an exact reviewed playlist batch after explicit confirmation. */
    fun materializePlaylistBatch(
        review: ReviewedFolderPlaylistBatch,
        confirmWrite: Boolean,
        onProgress: (FolderPlaylistBatchProgress) -> Unit = {},
    ): FolderMetadataSuiteOperation<FolderPlaylistBatchWriteResult> {
        if (!confirmWrite) return failure("Playlist batch materialization requires explicit confirmation.")
        if (!reviewIsCurrent(review.revision)) {
            return staleReview("The playlist batch review is stale; reconcile and preview again.")
        }
        return try {
            val result = workflow.writePlaylistBatch(review.plan, onProgress)
            if (!reviewIsCurrent(review.revision)) {
                FolderMetadataSuiteOperation(
                    value = result,
                    message = "Playlist batch used reviewed evidence, but the folder changed during materialization; reconcile before continuing.",
                    reconciliationRequired = true,
                )
            } else {
                FolderMetadataSuiteOperation(result, "Wrote ${result.results.size} reviewed playlist file(s).")
            }
        } catch (error: RuntimeException) {
            materializationFailure(error, "Playlist batch materialization failed")
        }
    }

    /**
     * Queue a current reviewed playlist batch for post-sync regeneration. This path accepts no
     * tag approval or tag plan type and delegates only to the playlist-only regeneration service.
     */
    fun schedulePostSyncPlaylistRegeneration(
        key: String,
        review: ReviewedFolderPlaylistBatch,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): FolderMetadataSuiteOperation<FolderPlaylistRegenerationService.ScheduledRegeneration> {
        if (!reviewIsCurrent(review.revision)) {
            return staleReview("The post-sync playlist review is stale; reconcile and plan it again.")
        }
        val scheduled = try {
            regeneration.schedule(key, review.plan, nowEpochMillis)
        } catch (error: RuntimeException) {
            return failure(error.message ?: "Could not schedule playlist regeneration.")
        }
        if (!reviewIsCurrent(review.revision)) {
            // A watcher/reconciliation signal raced with scheduling. Revoke the just-enqueued
            // derived work instead of allowing an old review to survive the barrier.
            regeneration.cancelAll()
            return staleReview("The folder changed while post-sync playlist work was being scheduled; reconcile and plan it again.")
        }
        val successMessage = "Queued ${scheduled.playlistCount} derived playlist file(s) for bounded post-sync regeneration."
        synchronized(lock) { message = successMessage }
        return FolderMetadataSuiteOperation(scheduled, successMessage)
    }

    /** Flush due derived work only; this method has no tag staging/apply call. */
    fun flushPostSyncPlaylistRegeneration(
        nowEpochMillis: Long = System.currentTimeMillis(),
        onProgress: (FolderPlaylistBatchProgress) -> Unit = {},
    ): FolderMetadataSuiteOperation<List<FolderPlaylistBatchWriteResult>> {
        val startedRevision = synchronized(lock) {
            if (reconciliationRequired) {
                return FolderMetadataSuiteOperation(
                    value = null,
                    message = "Post-sync playlist work is paused until the selected folder is reconciled.",
                    reconciliationRequired = true,
                )
            }
            revision
        }
        return try {
            val results = regeneration.flushDue(nowEpochMillis, onProgress)
            synchronized(lock) {
                if (reconciliationRequired || revision != startedRevision) {
                    return FolderMetadataSuiteOperation(
                        value = results,
                        message = "The folder changed while post-sync playlists were being regenerated; reconcile before continuing.",
                        reconciliationRequired = true,
                    )
                }
                message = if (results.isEmpty()) {
                    "No post-sync playlist regeneration is due yet."
                } else {
                    "Regenerated ${results.sumOf { it.results.size }} derived playlist file(s)."
                }
                FolderMetadataSuiteOperation(results, message)
            }
        } catch (error: RuntimeException) {
            invalidateForFilesystemChange("Post-sync playlist evidence became stale or unsafe.")
            FolderMetadataSuiteOperation(
                value = null,
                message = "Post-sync playlist regeneration failed: ${error.message ?: "reconciliation required"}",
                reconciliationRequired = true,
            )
        }
    }

    private fun reviewApproval(
        snapshot: FolderTagSnapshot,
        approve: () -> FileApproval,
    ): FolderMetadataSuiteOperation<ReviewedFolderApproval> {
        val captured = captureCurrentPreview()
            ?: return noCurrentPreview("Preview/reconcile the folder before approving tag fields.")
        val (reviewRevision, preview) = captured
        if (preview.snapshots.none { it == snapshot }) {
            return staleReview("The tag row belongs to an older or different folder preview.")
        }
        return try {
            val approval = approve()
            issueAtRevision(reviewRevision, ReviewedFolderApproval(reviewRevision, approval), "Reviewed tag approval is frozen to the current preview.")
        } catch (error: RuntimeException) {
            val detail = error.message ?: "Could not approve the selected tag fields."
            if (staleEvidenceMessage(detail)) {
                invalidateForFilesystemChange("Tag source evidence changed during approval.")
                FolderMetadataSuiteOperation(value = null, message = detail, reconciliationRequired = true)
            } else {
                failure(detail)
            }
        }
    }

    private fun captureCurrentPreview(): Pair<Long, FolderTreeTagPreview>? = synchronized(lock) {
        if (reconciliationRequired) return@synchronized null
        currentPreview?.let { revision to it }
    }

    private fun reviewIsCurrent(reviewRevision: Long): Boolean = synchronized(lock) {
        reviewIsCurrentLocked(reviewRevision)
    }

    private fun reviewIsCurrentLocked(reviewRevision: Long): Boolean =
        !reconciliationRequired && currentPreview != null && reviewRevision == revision

    private fun <T> issueAtRevision(
        reviewRevision: Long,
        value: T,
        successMessage: String,
    ): FolderMetadataSuiteOperation<T> = synchronized(lock) {
        if (!reviewIsCurrentLocked(reviewRevision)) {
            FolderMetadataSuiteOperation(
                value = null,
                message = "The folder changed while the review was being prepared; reconcile and preview again.",
                reconciliationRequired = true,
            )
        } else {
            message = successMessage
            FolderMetadataSuiteOperation(value, successMessage)
        }
    }

    private fun playlistReviewMessage(plan: FolderPlaylistPlan): String =
        "Playlist review contains ${plan.entries.size} media entr${if (plan.entries.size == 1) "y" else "ies"} for ${plan.fileName}; confirm before writing."

    private fun <T> noCurrentPreview(detail: String): FolderMetadataSuiteOperation<T> = synchronized(lock) {
        FolderMetadataSuiteOperation(value = null, message = detail, reconciliationRequired = reconciliationRequired)
    }

    private fun <T> staleReview(detail: String): FolderMetadataSuiteOperation<T> =
        FolderMetadataSuiteOperation(value = null, message = detail, reconciliationRequired = true)

    private fun <T> failure(detail: String): FolderMetadataSuiteOperation<T> =
        FolderMetadataSuiteOperation(value = null, message = detail, reconciliationRequired = false)

    private fun <T> materializationFailure(
        error: RuntimeException,
        prefix: String,
    ): FolderMetadataSuiteOperation<T> {
        val detail = error.message ?: prefix
        if (staleEvidenceMessage(detail)) {
            invalidateForFilesystemChange("Reviewed playlist evidence changed before materialization.")
            return FolderMetadataSuiteOperation(
                value = null,
                message = "$prefix: $detail",
                reconciliationRequired = true,
            )
        }
        return failure("$prefix: $detail")
    }

    private fun staleEvidenceMessage(detail: String): Boolean {
        val normalized = detail.lowercase()
        return "changed after preview" in normalized ||
            "changed after review" in normalized ||
            "reconcile" in normalized ||
            "rescan" in normalized ||
            "membership changed" in normalized ||
            "no longer" in normalized ||
            "symbolic link" in normalized ||
            "symlink" in normalized
    }

}
