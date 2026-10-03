package dev.properpcloud.core.model

import java.io.File
import java.util.UUID

/**
 * Content evidence for a scanned file. The fast variant is used during enumeration;
 * the authoritative SHA-256 is captured before any apply operation.
 */
data class ContentEvidence(
    val sizeBytes: Long,
    val modifiedTimeNanos: Long,
    val sha256: String? = null,
) {
    init {
        require(sizeBytes >= 0) { "content evidence size must not be negative" }
    }

    val isAuthoritative: Boolean get() = sha256 != null
}

/** Textual values disclosed to a metadata provider for one file. Audio bytes and paths are excluded. */
data class FolderMetadataQuery(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val isrc: String? = null,
    val durationMillis: Long? = null,
) {
    init {
        require(listOf(title, artist, album, isrc).any { !it.isNullOrBlank() }) {
            "folder metadata lookup requires title, artist, album, or ISRC"
        }
        require(durationMillis == null || durationMillis > 0) { "duration must be positive" }
    }
}

/** Source-neutral port used by the shared Android/Linux folder workflow. */
fun interface FolderMetadataLookup {
    suspend fun search(query: FolderMetadataQuery, limit: Int): List<MetadataCandidate>
}

data class MusicLibraryTrack(
    val sourceId: SourceId,
    val nodeId: NodeId,
    val folderId: NodeId,
    val filename: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val trackNumber: Int?,
)

data class MusicLibraryFolder(
    val sourceId: SourceId,
    val folderId: NodeId,
    val displayName: String,
    val tracks: List<MusicLibraryTrack>,
)

/**
 * Browsable metadata projection. The only mutation accepts exactly one already-scanned
 * direct-folder snapshot, making recursive or loose-file imports impossible by construction.
 */
data class MusicLibraryCatalog(
    val folders: Map<Pair<SourceId, NodeId>, MusicLibraryFolder> = emptyMap(),
) {
    fun addDirectory(snapshot: FolderTagSnapshot): MusicLibraryCatalog {
        val folder = MusicLibraryFolder(
            sourceId = snapshot.sourceId,
            folderId = snapshot.folderId,
            displayName = snapshot.folderPath.name,
            tracks = snapshot.files.map { file ->
                val fields = file.originalSnapshot.fields
                MusicLibraryTrack(
                    sourceId = snapshot.sourceId,
                    nodeId = file.identity.nodeId,
                    folderId = snapshot.folderId,
                    filename = file.identity.filename,
                    title = fields[TagField.TITLE]?.value ?: file.identity.file.nameWithoutExtension,
                    artist = fields[TagField.ARTIST]?.value,
                    album = fields[TagField.ALBUM]?.value,
                    trackNumber = fields[TagField.TRACK_NUMBER]?.value?.substringBefore('/')?.toIntOrNull(),
                )
            }.sortedWith(compareBy<MusicLibraryTrack> { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.filename.lowercase() }),
        )
        return copy(folders = folders + ((snapshot.sourceId to snapshot.folderId) to folder))
    }

    fun browseFolders(): List<MusicLibraryFolder> = folders.values.sortedBy { it.displayName.lowercase() }

    fun browseArtists(): Map<String, List<MusicLibraryTrack>> = folders.values
        .flatMap(MusicLibraryFolder::tracks)
        .filter { !it.artist.isNullOrBlank() }
        .groupBy { it.artist!! }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)

    fun browseAlbums(): Map<String, List<MusicLibraryTrack>> = folders.values
        .flatMap(MusicLibraryFolder::tracks)
        .filter { !it.album.isNullOrBlank() }
        .groupBy { it.album!! }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
}

/**
 * Identity of a local file within a folder session. Stable across rescans
 * as long as the file remains in the same directory with the same name.
 */
data class LocalFileIdentity(
    val sourceId: SourceId,
    val nodeId: NodeId,
    val file: File,
    val filename: String,
    val contentEvidence: ContentEvidence,
) {
    init {
        require(filename.isNotBlank()) { "filename must not be blank" }
    }
}

/**
 * Row state for a file in the tag workbench table.
 */
enum class TagRowState {
    CLEAN,
    MISSING_FIELDS,
    INCONSISTENT_WITH_FOLDER,
    MALFORMED_OR_UNSUPPORTED,
    PROPOSED,
    APPROVED,
    CHANGED_ON_DISK,
    APPLYING,
    VERIFIED,
    CONFLICTED,
    FAILED,
}

enum class TagScanFailureKind {
    UNREADABLE,
    MALFORMED_OR_UNSUPPORTED,
}

data class TagScanFailure(
    val kind: TagScanFailureKind,
    val message: String,
) {
    init {
        require(message.isNotBlank()) { "scan failure message must not be blank" }
    }
}

/**
 * A single field-level proposal for one file.
 */
data class TagFieldProposal(
    val field: TagField,
    val ruleId: String,
    val currentValue: String?,
    val proposedValue: String?,
    val confidence: Double,
    val autoPreselected: Boolean,
    val explanation: String,
    val warnings: List<String> = emptyList(),
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be between zero and one" }
        require(currentValue != null || proposedValue != null) { "at least one of current or proposed must be set" }
    }

    /** True when a derived candidate differs from a non-empty embedded value. */
    val conflictsWithExistingValue: Boolean get() =
        !currentValue.isNullOrBlank() &&
            !proposedValue.isNullOrBlank() &&
            currentValue != proposedValue
}

/**
 * Configures deterministic inference from an Artist/Album[/Disc]/Track.ext style hierarchy.
 *
 * Depths are counted from the effective track folder: depth zero is the folder containing
 * the track, except that a recognized disc folder may be skipped so its parent becomes depth
 * zero. This keeps the common Artist/Album/CD 1/Track.ext layout equivalent to
 * Artist/Album/Track.ext for artist/album inference while still exposing the disc number.
 */
data class FolderStructureTagConfig(
    val enabled: Boolean = true,
    val albumAncestorDepth: Int = 0,
    val artistAncestorDepth: Int = 1,
    val recognizeDiscFolders: Boolean = true,
    val inferTitleFromFilename: Boolean = true,
    val inferTrackNumberFromNaturalOrder: Boolean = true,
    val inferTrackTotal: Boolean = true,
    val inferDiscNumber: Boolean = true,
) {
    init {
        require(albumAncestorDepth in 0..32) { "album ancestor depth must be between 0 and 32" }
        require(artistAncestorDepth in 0..32) { "artist ancestor depth must be between 0 and 32" }
    }
}

/**
 * The complete proposal set for one file.
 */
data class FileTagProposals(
    val identity: LocalFileIdentity,
    val originalSnapshot: TagSnapshot,
    val fieldProposals: List<TagFieldProposal>,
    /** Ranked external candidates. They remain inert evidence until fields are approved. */
    val onlineCandidates: List<MetadataCandidate> = emptyList(),
    val formatWarnings: List<String> = emptyList(),
    val scanFailure: TagScanFailure? = null,
) {
    val hasProposals: Boolean get() = fieldProposals.isNotEmpty()
    val canApply: Boolean get() = scanFailure == null
    val missingFields: Set<TagField> = fieldProposals
        .filter { it.currentValue.isNullOrBlank() && it.proposedValue != null }
        .map { it.field }
        .toSet()
}

/**
 * User's per-field decision for a proposal.
 */
enum class FieldDecision {
    KEEP,
    CLEAR,
    SET,
}

/**
 * A user-approved field edit, combining the proposal with the user's explicit decision.
 */
data class ApprovedFieldEdit(
    val field: TagField,
    val decision: FieldDecision,
    val finalValue: String?,
    val proposal: TagFieldProposal,
) {
    init {
        require(decision != FieldDecision.SET || !finalValue.isNullOrBlank()) {
            "SET decision requires a non-blank final value"
        }
        require(decision != FieldDecision.CLEAR || finalValue.isNullOrBlank()) {
            "CLEAR decision must not carry a replacement value"
        }
    }
}

/**
 * Approval state for one file's proposals.
 */
data class FileApproval(
    val identity: LocalFileIdentity,
    val approvedFields: Map<TagField, ApprovedFieldEdit>,
    val originalSnapshot: TagSnapshot,
    val expectedContentHash: String,
) {
    init {
        require(approvedFields.isNotEmpty()) { "file approval must contain at least one reviewed field" }
        require(expectedContentHash.isNotBlank()) { "expected content hash must not be blank" }
    }

    val hasApprovals: Boolean get() = approvedFields.values.any { it.decision != FieldDecision.KEEP }

    fun toTagPatch(): TagPatch {
        val mutations = approvedFields.mapValues { (_, edit) ->
            when (edit.decision) {
                FieldDecision.KEEP -> TagMutation.Keep
                FieldDecision.CLEAR -> TagMutation.Clear
                FieldDecision.SET -> TagMutation.Set(edit.finalValue!!)
            }
        }
        return TagPatch(mutations)
    }
}

/**
 * Result of applying one file's approved changes.
 */
enum class ApplyResultStatus {
    VERIFIED,
    CONFLICTED,
    FAILED,
    EXPORTED,
    INDETERMINATE,
}

data class FileApplyResult(
    val identity: LocalFileIdentity,
    val status: ApplyResultStatus,
    val message: String,
    val originalSha256: String,
    val resultSha256: String? = null,
    val rollbackFile: File? = null,
    val exportFile: File? = null,
    val verifiedFields: Set<TagField> = emptySet(),
    val failedFields: Set<TagField> = emptySet(),
)

/**
 * Snapshot generation counter for a folder session.
 */
@JvmInline
value class SnapshotGeneration(val value: Long) {
    init {
        require(value >= 0) { "snapshot generation must not be negative" }
    }
}

/**
 * A complete, coherent snapshot of one folder's tag state.
 */
data class FolderTagSnapshot(
    val generation: SnapshotGeneration,
    val folderPath: File,
    val sourceId: SourceId,
    val folderId: NodeId,
    val files: List<FileTagProposals>,
    val scanTimeEpochMillis: Long,
    val warnings: List<String> = emptyList(),
) {
    val fileCount: Int get() = files.size
    val proposalCount: Int get() = files.count { it.hasProposals }
    val warningCount: Int get() = files.sumOf { it.formatWarnings.size } + warnings.size
    val missingFieldsCount: Int get() = files.count { it.missingFields.isNotEmpty() }

    fun findByNodeId(nodeId: NodeId): FileTagProposals? = files.find { it.identity.nodeId == nodeId }
}

/**
 * The mutable session state for a folder tag workbench.
 */
data class FolderTagSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val sourceId: SourceId,
    val folderId: NodeId,
    val folderPath: File,
    val snapshot: FolderTagSnapshot? = null,
    val approvals: Map<NodeId, FileApproval> = emptyMap(),
    val applyResults: Map<NodeId, FileApplyResult> = emptyMap(),
    val draftSaved: Boolean = false,
) {
    val isScanning: Boolean get() = snapshot == null
    val hasApprovals: Boolean get() = approvals.values.any { it.hasApprovals }
    val approvedFileCount: Int get() = approvals.values.count { it.hasApprovals }
    val approvedChangeCount: Int get() = approvals.values.sumOf { approval ->
        approval.approvedFields.values.count { it.decision != FieldDecision.KEEP }
    }

    fun withApproval(nodeId: NodeId, approval: FileApproval): FolderTagSession =
        copy(approvals = approvals + (nodeId to approval))

    fun withoutApproval(nodeId: NodeId): FolderTagSession =
        copy(approvals = approvals - nodeId)

    fun withApplyResult(nodeId: NodeId, result: FileApplyResult): FolderTagSession =
        copy(applyResults = applyResults + (nodeId to result))
}
