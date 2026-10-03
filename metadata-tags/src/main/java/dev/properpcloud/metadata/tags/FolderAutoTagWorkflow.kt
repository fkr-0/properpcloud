package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.ApprovedFieldEdit
import dev.properpcloud.core.model.FieldDecision
import dev.properpcloud.core.model.FileApproval
import dev.properpcloud.core.model.FileApplyResult
import dev.properpcloud.core.model.FolderMetadataLookup
import dev.properpcloud.core.model.FolderMetadataQuery
import dev.properpcloud.core.model.FolderTagSnapshot
import dev.properpcloud.core.model.FolderStructureTagConfig
import dev.properpcloud.core.model.MetadataCandidate
import dev.properpcloud.core.model.NaturalTextComparator
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.SnapshotGeneration
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagFieldProposal
import java.io.File
import java.nio.file.Files

data class FolderTagPreviewCommand(
    val directory: File,
    val sourceId: SourceId,
    val generation: SnapshotGeneration,
    val structureConfig: FolderStructureTagConfig = FolderStructureTagConfig(),
    val onlineLookupConsent: Boolean = false,
    val candidateLimitPerFile: Int = 5,
) {
    init { require(candidateLimitPerFile in 1..20) }
}

data class FolderTreeTagPreviewCommand(
    val directory: File,
    val sourceId: SourceId,
    val generation: SnapshotGeneration,
    val recursive: Boolean = false,
    val structureConfig: FolderStructureTagConfig = FolderStructureTagConfig(),
    val onlineLookupConsent: Boolean = false,
    val candidateLimitPerFile: Int = 5,
) {
    init { require(candidateLimitPerFile in 1..20) }
}

data class FolderTreeTagPreview(
    val rootDirectory: File,
    val recursive: Boolean,
    val snapshots: List<FolderTagSnapshot>,
) {
    val folderCount: Int get() = snapshots.size
    val fileCount: Int get() = snapshots.sumOf { it.fileCount }
    val proposalCount: Int get() = snapshots.sumOf { it.proposalCount }
}

data class ApproveCandidateCommand(
    val snapshot: FolderTagSnapshot,
    val nodeId: NodeId,
    val candidateId: String,
    val acceptedFields: Set<TagField>,
)

/** Explicitly selects one local deterministic proposal per field from a shown preview row. */
data class ApproveLocalProposalsCommand(
    val snapshot: FolderTagSnapshot,
    val nodeId: NodeId,
    val acceptedRuleByField: Map<TagField, String>,
)

/**
 * Shared end-to-end application service used by Android and Linux forms:
 * scan one direct directory, optionally collect ranked online evidence, iterate previews,
 * approve fields, then perform guarded and verified local apply.
 */
class FolderAutoTagWorkflow(
    private val scanner: FolderTagScanner,
    private val lookup: FolderMetadataLookup,
    private val applyService: FolderTagApplyService,
    private val playlistWriter: FolderPlaylistWriter = FolderPlaylistWriter(),
) {
    suspend fun preview(command: FolderTagPreviewCommand): FolderTagSnapshot {
        val local = scanner.scan(command.directory, command.sourceId, command.generation, command.structureConfig)
        return enrichOnline(local, command.onlineLookupConsent, command.candidateLimitPerFile)
    }

    /**
     * Preview a selected folder tree. The default remains one direct folder; recursion occurs
     * only when [FolderTreeTagPreviewCommand.recursive] is explicitly true. Symbolic-link
     * directories are never followed.
     */
    suspend fun previewTree(command: FolderTreeTagPreviewCommand): FolderTreeTagPreview {
        val root = command.directory.canonicalFile
        require(root.isDirectory) { "preview target must be a directory: $root" }
        val directories = selectedDirectories(root, command.recursive)
        val snapshots = mutableListOf<FolderTagSnapshot>()
        directories.forEachIndexed { index, directory ->
            val generation = SnapshotGeneration(command.generation.value + index)
            val local = scanner.scan(directory, command.sourceId, generation, command.structureConfig)
            snapshots += enrichOnline(local, command.onlineLookupConsent, command.candidateLimitPerFile)
        }
        return FolderTreeTagPreview(root, command.recursive, snapshots)
    }

    private suspend fun enrichOnline(
        local: FolderTagSnapshot,
        onlineLookupConsent: Boolean,
        candidateLimitPerFile: Int,
    ): FolderTagSnapshot {
        if (!onlineLookupConsent) return local
        val enriched = local.files.map { file ->
            val fields = file.originalSnapshot.fields
            val title = fields[TagField.TITLE]?.value ?: inferTitle(file.identity.filename)
            val query = runCatching {
                FolderMetadataQuery(
                    title = title,
                    artist = fields[TagField.ARTIST]?.value,
                    album = fields[TagField.ALBUM]?.value,
                    isrc = fields[TagField.ISRC]?.value,
                    durationMillis = file.originalSnapshot.durationMillis,
                )
            }.getOrNull()
            val candidates = if (query == null) emptyList() else lookup.search(query, candidateLimitPerFile)
                .sortedByDescending(MetadataCandidate::score)
            file.copy(onlineCandidates = candidates)
        }
        return local.copy(files = enriched)
    }

    fun approveLocalProposals(command: ApproveLocalProposalsCommand): FileApproval {
        require(command.acceptedRuleByField.isNotEmpty()) { "approve at least one local proposal" }
        val file = command.snapshot.findByNodeId(command.nodeId) ?: error("file is not in this snapshot")
        require(file.canApply) { "malformed or unreadable rows cannot be approved" }
        val expectedContentHash = applyService.revalidateForApproval(file)
        val edits = command.acceptedRuleByField.mapValues { (field, ruleId) ->
            val matches = file.fieldProposals.filter { proposal -> proposal.field == field && proposal.ruleId == ruleId }
            val proposal = matches.singleOrNull()
                ?: error("preview does not contain exactly one $field proposal from rule $ruleId")
            val value = proposal.proposedValue ?: error("selected proposal does not contain a replacement value")
            ApprovedFieldEdit(field, FieldDecision.SET, value, proposal)
        }
        return FileApproval(
            identity = file.identity,
            approvedFields = edits,
            originalSnapshot = file.originalSnapshot,
            expectedContentHash = expectedContentHash,
        )
    }

    fun approveCandidate(command: ApproveCandidateCommand): FileApproval {
        require(command.acceptedFields.isNotEmpty()) { "approve at least one candidate field" }
        val file = command.snapshot.findByNodeId(command.nodeId) ?: error("file is not in this snapshot")
        val expectedContentHash = applyService.revalidateForApproval(file)
        val candidate = file.onlineCandidates.singleOrNull { it.id == command.candidateId }
            ?: error("candidate is not in this file preview")
        val edits = command.acceptedFields.associateWith { field ->
            val value = candidate.fields[field]?.value ?: error("candidate does not contain $field")
            val proposal = TagFieldProposal(
                field = field,
                ruleId = "online:${candidate.provider.name.lowercase()}:${candidate.id}",
                currentValue = file.originalSnapshot.fields[field]?.value,
                proposedValue = value,
                confidence = candidate.fields[field]?.confidence ?: candidate.score,
                autoPreselected = false,
                explanation = "Reviewed ${candidate.provider} candidate ${candidate.id}.",
                warnings = listOf("Online metadata is evidence; verify this field before applying."),
            )
            ApprovedFieldEdit(field, FieldDecision.SET, value, proposal)
        }
        return FileApproval(
            identity = file.identity,
            approvedFields = edits,
            originalSnapshot = file.originalSnapshot,
            expectedContentHash = expectedContentHash,
        )
    }

    /**
     * Freeze explicit approvals into a side-effect-free batch plan. Recursive plans require a
     * second explicit opt-in so a direct-folder preview cannot accidentally widen its scope.
     */
    fun planBatch(
        preview: FolderTreeTagPreview,
        approvals: List<FileApproval>,
        recursiveOptIn: Boolean = false,
    ): FolderTagBatchPlan {
        require(!preview.recursive || recursiveOptIn) { "recursive batch planning requires explicit opt-in" }
        val root = preview.rootDirectory.canonicalFile
        val rowsByNode = preview.snapshots.flatMap { it.files }.associateBy { it.identity.nodeId }
        require(approvals.map { it.identity.nodeId }.distinct().size == approvals.size) {
            "batch approvals must contain each file at most once"
        }
        val items = approvals.map { approval ->
            require(approval.hasApprovals) { "batch plan contains an approval with no selected changes" }
            val row = rowsByNode[approval.identity.nodeId] ?: error("approved file is outside the shown tree preview")
            require(row.identity == approval.identity) { "approved file identity/evidence differs from the shown preview" }
            require(row.originalSnapshot == approval.originalSnapshot) { "approved tags differ from the shown preview snapshot" }
            val relativePath = root.toPath().relativize(approval.identity.file.canonicalFile.toPath()).toString()
            require(relativePath.isNotBlank() && !relativePath.startsWith("..")) {
                "approved file escaped the selected folder tree"
            }
            FolderTagBatchPlanItem(approval, relativePath.replace(File.separatorChar, '/'))
        }
        return FolderTagBatchPlan(
            rootDirectory = root,
            recursive = preview.recursive,
            recursiveOptInConfirmed = !preview.recursive || recursiveOptIn,
            items = items,
        )
    }

    fun executeBatchPlan(
        plan: FolderTagBatchPlan,
        stagingDirectory: File,
        dryRun: Boolean = true,
        confirmWrite: Boolean = false,
        onProgress: (FolderTagBatchProgress) -> Unit = {},
    ): FolderTagBatchExecutionResult =
        applyService.executeBatchPlan(plan, stagingDirectory, dryRun, confirmWrite, onProgress)

    /** Restore only a result whose exact current hash still matches the recorded apply result. */
    fun rollback(result: FileApplyResult): FileApplyResult = applyService.rollback(result)

    /** Build a side-effect-free playlist plan suitable for a review/confirmation surface. */
    fun planPlaylist(command: FolderPlaylistWriteCommand): FolderPlaylistPlan =
        playlistWriter.plan(command)

    /**
     * Materialize an exact plan that was already shown to the user. The shared application
     * boundary intentionally has no command-to-write shortcut: callers must plan first so the
     * confirmation surface and the write operate on the same frozen playlist evidence.
     */
    fun writePlaylist(plan: FolderPlaylistPlan): FolderPlaylistWriteResult =
        playlistWriter.write(plan)

    /**
     * Freeze playlist generation for a previously shown tree preview. Recursive playlist
     * generation requires its own opt-in and is completely separate from recursive tag apply.
     */
    fun planPlaylistBatch(
        preview: FolderTreeTagPreview,
        recursiveOptIn: Boolean = false,
        onePlaylistPerAlbum: Boolean = false,
        order: FolderPlaylistOrder = FolderPlaylistOrder.TAG_TRACK_NUMBER,
    ): FolderPlaylistBatchPlan = playlistWriter.planBatch(
        FolderPlaylistBatchCommand(
            rootDirectory = preview.rootDirectory,
            snapshots = preview.snapshots,
            recursive = preview.recursive,
            recursiveOptIn = recursiveOptIn,
            onePlaylistPerAlbum = onePlaylistPerAlbum,
            order = order,
        ),
    )

    /** Materialize only derived playlist files from an exact reviewed batch plan. */
    fun writePlaylistBatch(
        plan: FolderPlaylistBatchPlan,
        onProgress: (FolderPlaylistBatchProgress) -> Unit = {},
    ): FolderPlaylistBatchWriteResult = playlistWriter.writeBatch(plan, onProgress)

    private fun inferTitle(filename: String): String? = filename.substringBeforeLast('.').trim()
        .replace(Regex("^\\d+\\s*[-._ ]+"), "")
        .takeIf(String::isNotBlank)

    private fun selectedDirectories(root: File, recursive: Boolean): List<File> {
        if (!recursive) return listOf(root)
        val directories = mutableListOf<File>()

        fun visit(directory: File) {
            directories += directory
            val children = directory.listFiles()
                ?.filter { child -> child.isDirectory && !Files.isSymbolicLink(child.toPath()) }
                ?.sortedWith { left, right -> NaturalTextComparator.compare(left.name, right.name) }
                .orEmpty()
            children.forEach(::visit)
        }

        visit(root)
        return directories
    }
}
