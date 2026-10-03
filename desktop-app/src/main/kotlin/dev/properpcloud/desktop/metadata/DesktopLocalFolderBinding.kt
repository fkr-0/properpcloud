package dev.properpcloud.desktop.metadata

import dev.properpcloud.core.model.FileApplyResult
import dev.properpcloud.core.model.FolderMetadataLookup
import dev.properpcloud.desktop.data.DesktopLocalFilesystemIdentity
import dev.properpcloud.desktop.data.DesktopLocalFolderAudioSource
import dev.properpcloud.metadata.tags.ApproveLocalProposalsCommand
import dev.properpcloud.metadata.tags.AudioTagToolkit
import dev.properpcloud.metadata.tags.FolderAutoTagWorkflow
import dev.properpcloud.metadata.tags.FolderMetadataSuiteOperation
import dev.properpcloud.metadata.tags.FolderMetadataSuiteSession
import dev.properpcloud.metadata.tags.FolderPlaylistBatchProgress
import dev.properpcloud.metadata.tags.FolderPlaylistBatchWriteResult
import dev.properpcloud.metadata.tags.FolderPlaylistOrder
import dev.properpcloud.metadata.tags.FolderPlaylistWriteResult
import dev.properpcloud.metadata.tags.FolderTagApplyService
import dev.properpcloud.metadata.tags.FolderTagBatchExecutionResult
import dev.properpcloud.metadata.tags.FolderTagBatchProgress
import dev.properpcloud.metadata.tags.FolderTagScanner
import dev.properpcloud.metadata.tags.FolderTreeTagPreview
import dev.properpcloud.metadata.tags.JAudioTaggerToolkit
import dev.properpcloud.metadata.tags.JdkLocalFolderChangeObserverFactory
import dev.properpcloud.metadata.tags.LocalFolderChangeObserverFactory
import dev.properpcloud.metadata.tags.LocalFolderRootCapability
import dev.properpcloud.metadata.tags.LocalFolderWorkbenchHost
import dev.properpcloud.metadata.tags.LocalFolderWorkbenchStatus
import dev.properpcloud.metadata.tags.ReviewedFolderApproval
import dev.properpcloud.metadata.tags.ReviewedFolderPlaylist
import dev.properpcloud.metadata.tags.ReviewedFolderPlaylistBatch
import dev.properpcloud.metadata.tags.ReviewedFolderTagBatch
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** Owns one explicitly selected local source and the matching metadata watcher/session host. */
class DesktopLocalFolderBinding private constructor(
    val source: DesktopLocalFolderAudioSource,
    val host: LocalFolderWorkbenchHost,
    val recursive: Boolean,
    private val recoveryAuthority: DesktopLocalTagRecoveryAuthority,
) : AutoCloseable {
    val status: StateFlow<LocalFolderWorkbenchStatus> get() = host.status
    var recoveryState: DesktopLocalTagRecoveryState = DesktopLocalTagRecoveryState.EMPTY
        private set
    // FolderTagApplyService creates unique same-directory sibling candidates itself. Passing the
    // selected root avoids inventing a persistent scratch directory that recursive scans could see.
    val stagingDirectory: File = source.capability.rootDirectory

    suspend fun open(): FolderMetadataSuiteOperation<FolderTreeTagPreview> {
        recoveryState = recoveryAuthority.discover(source.identity)
        return projectRecoveryGate(host.open())
    }

    suspend fun currentPreview(): FolderTreeTagPreview? = host.currentPreview()

    suspend fun reconcileNow(): FolderMetadataSuiteOperation<FolderTreeTagPreview> {
        val reconciled = host.reconcileNow()
        recoveryState = recoveryAuthority.discover(source.identity)
        return projectRecoveryGate(reconciled)
    }

    fun approveLocal(command: ApproveLocalProposalsCommand): FolderMetadataSuiteOperation<ReviewedFolderApproval> =
        host.approveLocalProposals(command)

    fun reviewTags(
        approvals: List<ReviewedFolderApproval>,
        recursiveTagOptIn: Boolean,
    ): FolderMetadataSuiteOperation<ReviewedFolderTagBatch> = host.reviewTagBatch(approvals, recursiveTagOptIn)

    suspend fun executeTags(
        review: ReviewedFolderTagBatch,
        dryRun: Boolean,
        confirmWrite: Boolean = false,
        onProgress: (FolderTagBatchProgress) -> Unit = {},
    ): FolderMetadataSuiteOperation<FolderTagBatchExecutionResult> {
        if (!dryRun && recoveryState.recoveryRequired) return blockedForRecovery()
        val executed = host.executeTagBatch(
            review = review,
            stagingDirectory = stagingDirectory,
            dryRun = dryRun,
            confirmWrite = confirmWrite,
            onProgress = onProgress,
        )
        if (!dryRun) recoveryState = recoveryAuthority.discover(source.identity)
        return projectRecoveryGate(executed)
    }

    suspend fun rollbackTag(result: FileApplyResult): FolderMetadataSuiteOperation<FileApplyResult> {
        val rootPath = source.capability.rootDirectory.canonicalFile.toPath()
        val targetPath = result.identity.file.canonicalFile.toPath()
        require(targetPath != rootPath && targetPath.startsWith(rootPath)) {
            "rollback target escaped the selected local root"
        }
        val rolledBack = host.rollbackTagResult(result)
        recoveryState = recoveryAuthority.discover(source.identity)
        return projectRecoveryGate(rolledBack)
    }

    fun reviewDirectPlaylist(order: FolderPlaylistOrder): FolderMetadataSuiteOperation<ReviewedFolderPlaylist> =
        host.reviewDirectPlaylist(order)

    fun reviewPlaylistBatch(
        recursivePlaylistOptIn: Boolean,
        onePlaylistPerAlbum: Boolean,
        order: FolderPlaylistOrder,
    ): FolderMetadataSuiteOperation<ReviewedFolderPlaylistBatch> =
        host.reviewPlaylistBatch(recursivePlaylistOptIn, onePlaylistPerAlbum, order)

    fun materializePlaylist(
        review: ReviewedFolderPlaylist,
        confirmWrite: Boolean,
    ): FolderMetadataSuiteOperation<FolderPlaylistWriteResult> {
        if (confirmWrite && recoveryState.recoveryRequired) return blockedForRecovery()
        return host.materializePlaylist(review, confirmWrite)
    }

    fun materializePlaylistBatch(
        review: ReviewedFolderPlaylistBatch,
        confirmWrite: Boolean,
        onProgress: (FolderPlaylistBatchProgress) -> Unit = {},
    ): FolderMetadataSuiteOperation<FolderPlaylistBatchWriteResult> {
        if (confirmWrite && recoveryState.recoveryRequired) return blockedForRecovery()
        return host.materializePlaylistBatch(review, confirmWrite, onProgress)
    }

    private fun <T> projectRecoveryGate(operation: FolderMetadataSuiteOperation<T>): FolderMetadataSuiteOperation<T> =
        if (!recoveryState.recoveryRequired) {
            operation
        } else {
            operation.copy(
                message = "${operation.message} Interrupted local tag recovery must be resolved before additional metadata writes.",
                reconciliationRequired = true,
            )
        }

    private fun <T> blockedForRecovery(): FolderMetadataSuiteOperation<T> = FolderMetadataSuiteOperation(
        value = null,
        message = "Interrupted local tag recovery must be resolved before additional metadata writes.",
        reconciliationRequired = true,
    )

    override fun close() = host.close()

    companion object {
        fun createSelected(
            selectedRoot: File,
            recursive: Boolean = false,
            toolkit: AudioTagToolkit = JAudioTaggerToolkit(),
            observerFactory: LocalFolderChangeObserverFactory = JdkLocalFolderChangeObserverFactory,
            recoveryAuthority: DesktopLocalTagRecoveryAuthority = DesktopLocalTagRecoveryAuthority(),
        ): DesktopLocalFolderBinding {
            val identity = DesktopLocalFilesystemIdentity.forSelectedRoot(selectedRoot)
            // Validate the exact user-selected filesystem object. Canonical identity is useful for
            // opaque IDs, but canonicalizing first would turn a selected root symlink into its target
            // and bypass LocalFolderRootCapability's explicit non-symlink requirement.
            val capability = LocalFolderRootCapability.open(selectedRoot, identity.sourceId)
            val source = DesktopLocalFolderAudioSource(capability, identity)
            val scanner = FolderTagScanner(
                toolkit = toolkit,
                nodeIdentity = identity::nodeId,
            ).also { it.addAuthorizedRoot(capability.rootDirectory) }
            val workflow = FolderAutoTagWorkflow(
                scanner = scanner,
                lookup = FolderMetadataLookup { _, _ -> emptyList() },
                applyService = FolderTagApplyService(toolkit, recoveryAuthority),
            )
            val session = FolderMetadataSuiteSession(workflow)
            val host = LocalFolderWorkbenchHost(
                capability = capability,
                session = session,
                recursive = recursive,
                observerFactory = observerFactory,
            )
            return DesktopLocalFolderBinding(source, host, recursive, recoveryAuthority)
        }
    }
}
