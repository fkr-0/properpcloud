package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.ApplyResultStatus
import dev.properpcloud.core.model.FileApproval
import dev.properpcloud.core.model.FileApplyResult
import dev.properpcloud.core.model.FileTagProposals
import dev.properpcloud.core.model.LocalFileIdentity
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagMutation
import dev.properpcloud.core.model.TagPatch
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class FolderTagBatchPlanItem(
    val approval: FileApproval,
    val relativePath: String,
) {
    val expectedContentHash: String get() = approval.expectedContentHash
}

/** A frozen, previewable set of explicit approvals. Building this plan never writes media bytes. */
data class FolderTagBatchPlan(
    val rootDirectory: File,
    val recursive: Boolean,
    val recursiveOptInConfirmed: Boolean,
    val items: List<FolderTagBatchPlanItem>,
) {
    init {
        require(!recursive || recursiveOptInConfirmed) { "recursive batch plan must record explicit opt-in" }
    }

    val itemCount: Int get() = items.size
}

data class FolderTagBatchProgress(
    val completed: Int,
    val total: Int,
    val identity: LocalFileIdentity,
    val dryRun: Boolean,
    val status: ApplyResultStatus? = null,
)

data class FolderTagBatchExecutionResult(
    val plan: FolderTagBatchPlan,
    val dryRun: Boolean,
    val results: List<FileApplyResult>,
    val preflight: List<FolderTagBatchPreflightItem> = emptyList(),
)

data class FolderTagBatchPreflightItem(
    val identity: LocalFileIdentity,
    val expectedSha256: String,
    val actualSha256: String?,
    val ready: Boolean,
    val message: String,
)

/**
 * Optional durability hook used only by a client that can truthfully rediscover a selected
 * local root after restart. [arm] must durably bind the exact target, rollback bytes, reviewed
 * original hash, and expected replacement hash before the destructive replacement boundary.
 * [disarm] removes that durable cross-process authority after the byte outcome is proven safe.
 *
 * The shared workflow defaults to [NoopLocalTagRecoveryAuthority], so provider/prepared-copy
 * clients never gain filesystem recovery semantics merely by depending on this module.
 */
interface LocalTagRecoveryAuthority {
    fun arm(
        target: File,
        rollbackFile: File,
        originalSha256: String,
        expectedResultSha256: String,
    )

    fun disarm(target: File, rollbackFile: File)
}

object NoopLocalTagRecoveryAuthority : LocalTagRecoveryAuthority {
    override fun arm(
        target: File,
        rollbackFile: File,
        originalSha256: String,
        expectedResultSha256: String,
    ) = Unit

    override fun disarm(target: File, rollbackFile: File) = Unit
}

/**
 * Applies explicitly approved local tag edits through a fail-closed same-filesystem
 * transaction. There is deliberately no check-then-copy overwrite fallback: if the platform
 * cannot atomically replace the original, the verified staged candidate is returned as an
 * export and the source remains untouched.
 */
class FolderTagApplyService(
    private val toolkit: AudioTagToolkit,
    private val atomicReplaceOperation: (File, File) -> Boolean,
    private val recoveryAuthority: LocalTagRecoveryAuthority,
) {
    constructor(toolkit: AudioTagToolkit) : this(
        toolkit = toolkit,
        atomicReplaceOperation = ::atomicReplaceFile,
        recoveryAuthority = NoopLocalTagRecoveryAuthority,
    )

    constructor(
        toolkit: AudioTagToolkit,
        atomicReplaceOperation: (File, File) -> Boolean,
    ) : this(
        toolkit = toolkit,
        atomicReplaceOperation = atomicReplaceOperation,
        recoveryAuthority = NoopLocalTagRecoveryAuthority,
    )

    constructor(
        toolkit: AudioTagToolkit,
        recoveryAuthority: LocalTagRecoveryAuthority,
    ) : this(
        toolkit = toolkit,
        atomicReplaceOperation = ::atomicReplaceFile,
        recoveryAuthority = recoveryAuthority,
    )

    private val leases = ConcurrentHashMap<String, Any>()

    /**
     * Revalidate the exact row shown to the user before an approval can be frozen. This is
     * deliberately read-only: it never stages bytes and never invokes the replacement path.
     */
    fun revalidateForApproval(row: FileTagProposals): String {
        require(row.canApply) { "malformed or unreadable rows cannot be approved" }
        val file = row.identity.file.canonicalFile
        val path = file.toPath()
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "file changed after preview; rescan before approving"
        }
        require(file.name == row.identity.filename) {
            "file identity changed after preview; rescan before approving"
        }
        val instant = Files.getLastModifiedTime(path).toInstant()
        val currentModifiedTimeNanos = Math.addExact(
            Math.multiplyExact(instant.epochSecond, 1_000_000_000L),
            instant.nano.toLong(),
        )
        require(
            Files.size(path) == row.identity.contentEvidence.sizeBytes &&
                currentModifiedTimeNanos == row.identity.contentEvidence.modifiedTimeNanos
        ) {
            "file content evidence changed after preview; rescan before approving"
        }

        val beforeInspectHash = file.sha256()
        val currentSnapshot = toolkit.inspect(file)
        val afterInspectHash = file.sha256()
        require(beforeInspectHash.equals(afterInspectHash, ignoreCase = true)) {
            "file changed during approval revalidation; rescan before approving"
        }
        require(currentSnapshot == row.originalSnapshot) {
            "embedded tags changed after preview; rescan before approving"
        }
        return afterInspectHash
    }

    fun apply(
        approval: FileApproval,
        stagingDirectory: File,
    ): FileApplyResult {
        // Keep the caller's scratch-space contract explicit even though byte replacement must
        // use same-directory siblings to guarantee a single filesystem.
        require(stagingDirectory.exists() || stagingDirectory.mkdirs()) {
            "could not create staging directory: $stagingDirectory"
        }
        require(stagingDirectory.isDirectory) { "staging path must be a directory: $stagingDirectory" }

        val canonicalFile = approval.identity.file.canonicalFile
        val leaseKey = canonicalFile.absolutePath
        val lease = leases.computeIfAbsent(leaseKey) { Any() }
        synchronized(lease) {
            try {
                return applyInternal(approval, canonicalFile)
            } finally {
                leases.remove(leaseKey, lease)
            }
        }
    }

    private fun applyInternal(
        approval: FileApproval,
        canonicalFile: File,
    ): FileApplyResult {
        val identity = approval.identity
        val path = canonicalFile.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return failed(approval, "File no longer exists as a regular non-symlink file.")
        }
        if (canonicalFile.name != identity.filename) {
            return failed(approval, "File identity no longer matches the approved filename.")
        }

        val currentHash = canonicalFile.sha256()
        if (!currentHash.equals(approval.expectedContentHash, ignoreCase = true)) {
            return FileApplyResult(
                identity = identity,
                status = ApplyResultStatus.CONFLICTED,
                message = "File content changed after review; rescan before applying.",
                originalSha256 = currentHash,
            )
        }

        val patch = approval.toTagPatch()
        val changedFields = patch.changedFields(approval.originalSnapshot)
        if (changedFields.isEmpty()) {
            return FileApplyResult(
                identity = identity,
                status = ApplyResultStatus.VERIFIED,
                message = "No approved fields differ from the reviewed source.",
                originalSha256 = currentHash,
                resultSha256 = currentHash,
            )
        }

        val parent = canonicalFile.parentFile?.canonicalFile
            ?: return failed(approval, "Source file has no writable parent directory.", currentHash)
        if (!parent.isDirectory || !parent.canWrite()) {
            return failed(approval, "Source directory is not writable.", currentHash)
        }
        val metadata = captureMetadata(canonicalFile)

        val staged = try {
            toolkit.stagePatch(
                source = canonicalFile,
                stagingDirectory = parent,
                patch = patch,
                expectedSourceSha256 = currentHash,
            )
        } catch (error: Exception) {
            return failed(approval, "Staging failed: ${error.message}", currentHash)
        }

        // Recheck the original after the potentially expensive tag write. This closes the race
        // between approval/staging and replacement.
        val beforeReplaceHash = runCatching { canonicalFile.sha256() }.getOrNull()
        if (beforeReplaceHash == null || !beforeReplaceHash.equals(currentHash, ignoreCase = true)) {
            staged.stagedFile.delete()
            return FileApplyResult(
                identity = identity,
                status = ApplyResultStatus.CONFLICTED,
                message = "File content changed while the verified candidate was being prepared.",
                originalSha256 = beforeReplaceHash ?: currentHash,
            )
        }

        applyMetadata(staged.stagedFile, metadata)
        runCatching { forceFile(staged.stagedFile) }.getOrElse { error ->
            staged.stagedFile.delete()
            return failed(approval, "Could not flush staged candidate: ${error.message}", currentHash)
        }

        val rollback = try {
            createRollbackSibling(canonicalFile, currentHash)
        } catch (error: Exception) {
            staged.stagedFile.delete()
            return failed(approval, "Could not create verified rollback bytes: ${error.message}", currentHash)
        }

        // A native client that supports restart recovery must durably arm recovery before the
        // destructive rename. If arming fails, no media byte is replaced. Keep the verified
        // rollback sibling unless durable disarm succeeds, because an arm failure can itself be
        // ambiguous after an atomic record rename.
        try {
            recoveryAuthority.arm(
                target = canonicalFile,
                rollbackFile = rollback,
                originalSha256 = currentHash,
                expectedResultSha256 = staged.stagedSha256,
            )
        } catch (error: Exception) {
            staged.stagedFile.delete()
            val disarmed = runCatching { recoveryAuthority.disarm(canonicalFile, rollback) }.isSuccess
            if (disarmed) rollback.delete()
            return failed(
                approval,
                "Could not durably arm local recovery before replacement: ${error.message}",
                currentHash,
            )
        }

        // Creating/forcing the rollback copy and durable recovery authority can take long enough
        // for another process to replace the source after the post-staging check. Revalidate
        // again immediately before the one destructive boundary.
        val immediatelyBeforeReplaceHash = runCatching { canonicalFile.sha256() }.getOrNull()
        if (immediatelyBeforeReplaceHash == null ||
            !immediatelyBeforeReplaceHash.equals(currentHash, ignoreCase = true)
        ) {
            staged.stagedFile.delete()
            runCatching { recoveryAuthority.disarm(canonicalFile, rollback) }
            rollback.delete()
            return FileApplyResult(
                identity = identity,
                status = ApplyResultStatus.CONFLICTED,
                message = "File content changed immediately before atomic replacement; rescan before applying.",
                originalSha256 = immediatelyBeforeReplaceHash ?: currentHash,
            )
        }

        val replaced = atomicReplaceOperation(staged.stagedFile, canonicalFile)
        if (!replaced) {
            // The operation result itself can be ambiguous (for example an interrupted wrapper
            // after the rename reached the filesystem). Never destroy the verified rollback
            // copy until the original source bytes have been positively proved unchanged.
            val observedHash = runCatching {
                canonicalFile.takeIf(File::isFile)?.sha256()
            }.getOrNull()
            val sourceStillOriginal = observedHash?.equals(currentHash, ignoreCase = true) == true
            return if (sourceStillOriginal) {
                runCatching { recoveryAuthority.disarm(canonicalFile, rollback) }
                rollback.delete()
                FileApplyResult(
                    identity = identity,
                    status = ApplyResultStatus.EXPORTED,
                    message = "Atomic replacement is unavailable; source left unchanged and verified candidate retained for export.",
                    originalSha256 = currentHash,
                    resultSha256 = observedHash,
                    exportFile = staged.stagedFile.takeIf(File::isFile),
                    verifiedFields = changedFields,
                )
            } else {
                val candidateIsCurrent = observedHash?.equals(staged.stagedSha256, ignoreCase = true) == true
                FileApplyResult(
                    identity = identity,
                    status = ApplyResultStatus.INDETERMINATE,
                    message = if (candidateIsCurrent) {
                        "Atomic replacement reported failure, but the exact staged candidate is now present; guarded rollback is available while that hash remains current."
                    } else {
                        "Atomic replacement reported failure and current bytes match neither the reviewed original nor the staged candidate; preserve recovery evidence and reconcile manually."
                    },
                    originalSha256 = currentHash,
                    resultSha256 = observedHash.takeIf { candidateIsCurrent },
                    rollbackFile = rollback.takeIf(File::isFile),
                    exportFile = staged.stagedFile.takeIf(File::isFile),
                )
            }
        }

        applyMetadata(canonicalFile, metadata)
        runCatching {
            forceFile(canonicalFile)
            forceDirectory(parent)
        }.onFailure { error ->
            return rollbackAfterVerificationFailure(
                approval = approval,
                originalHash = currentHash,
                rollback = rollback,
                metadata = metadata,
                failedFields = changedFields,
                reason = "Could not durably flush replacement: ${error.message}",
            )
        }

        val finalHash = runCatching { canonicalFile.sha256() }.getOrNull()
            ?: return rollbackAfterVerificationFailure(
                approval,
                currentHash,
                rollback,
                metadata,
                changedFields,
                "Could not hash the replaced file.",
            )
        val finalSnapshot = runCatching { toolkit.inspect(canonicalFile) }.getOrElse { error ->
            return rollbackAfterVerificationFailure(
                approval,
                currentHash,
                rollback,
                metadata,
                changedFields,
                "Post-apply inspection failed: ${error.message}",
            )
        }
        val failedFields = verifyPatch(patch, finalSnapshot.fields.mapValues { it.value.value })
        if (failedFields.isNotEmpty()) {
            return rollbackAfterVerificationFailure(
                approval,
                currentHash,
                rollback,
                metadata,
                failedFields,
                "Post-apply verification did not match the approved field plan.",
            )
        }

        runCatching { recoveryAuthority.disarm(canonicalFile, rollback) }
        return FileApplyResult(
            identity = identity,
            status = ApplyResultStatus.VERIFIED,
            message = "Applied and reread ${changedFields.size} approved field(s).",
            originalSha256 = currentHash,
            resultSha256 = finalHash,
            rollbackFile = rollback,
            verifiedFields = changedFields,
        )
    }

    /**
     * Restore a user-requested rollback only if the current file still equals the result that
     * properpcloud previously verified. This prevents rollback from overwriting a later edit.
     */
    fun rollback(result: FileApplyResult): FileApplyResult {
        val rollbackFile = result.rollbackFile
            ?: return result.copy(status = ApplyResultStatus.FAILED, message = "No rollback bytes are available.")
        val canonicalFile = result.identity.file.canonicalFile
        val leaseKey = canonicalFile.absolutePath
        val lease = leases.computeIfAbsent(leaseKey) { Any() }
        synchronized(lease) {
            try {
                if (!rollbackFile.isFile || Files.isSymbolicLink(rollbackFile.toPath())) {
                    return result.copy(status = ApplyResultStatus.FAILED, message = "Rollback bytes are unavailable.")
                }
                val expectedCurrent = result.resultSha256
                    ?: return result.copy(
                        status = ApplyResultStatus.INDETERMINATE,
                        message = "Rollback refused because the current result hash was never proven; retained recovery bytes must not overwrite unknown current content.",
                    )
                val current = runCatching { canonicalFile.sha256() }.getOrNull()
                if (current == null || !current.equals(expectedCurrent, ignoreCase = true)) {
                    return result.copy(
                        status = ApplyResultStatus.CONFLICTED,
                        message = "File changed after apply; refusing to overwrite it with older rollback bytes.",
                    )
                }
                val metadata = captureMetadata(canonicalFile)
                val replaced = atomicReplaceOperation(rollbackFile, canonicalFile)
                if (!replaced) {
                    // A false/failed wrapper result is not proof that the rename did not happen.
                    // If the exact reviewed original is now present, prefer cryptographic proof
                    // over the transport result, but only after forcing the restored file and
                    // directory so interruption recovery does not overclaim crash durability.
                    val restored = runCatching { canonicalFile.sha256() }.getOrNull()
                    if (restored != null && restored.equals(result.originalSha256, ignoreCase = true)) {
                        val durable = runCatching {
                            applyMetadata(canonicalFile, metadata)
                            forceFile(canonicalFile)
                            canonicalFile.parentFile?.let(::forceDirectory)
                        }.isSuccess
                        if (durable) {
                            runCatching { recoveryAuthority.disarm(canonicalFile, rollbackFile) }
                            return result.copy(
                                status = ApplyResultStatus.VERIFIED,
                                message = "Rollback operation reported an interruption, but exact original bytes were durably verified as restored.",
                                resultSha256 = restored,
                                rollbackFile = null,
                                exportFile = null,
                                verifiedFields = emptySet(),
                                failedFields = emptySet(),
                            )
                        }
                    }
                    return result.copy(
                        status = ApplyResultStatus.INDETERMINATE,
                        message = "Atomic rollback reported failure and durable exact restoration could not be proven; retained rollback bytes remain the recovery authority when available.",
                        resultSha256 = restored,
                        rollbackFile = rollbackFile.takeIf(File::isFile),
                    )
                }
                applyMetadata(canonicalFile, metadata)
                forceFile(canonicalFile)
                canonicalFile.parentFile?.let(::forceDirectory)
                val restored = canonicalFile.sha256()
                return if (restored.equals(result.originalSha256, ignoreCase = true)) {
                    runCatching { recoveryAuthority.disarm(canonicalFile, rollbackFile) }
                    result.copy(
                        status = ApplyResultStatus.VERIFIED,
                        message = "Rollback verified; exact original bytes restored.",
                        resultSha256 = restored,
                        rollbackFile = null,
                        exportFile = null,
                        verifiedFields = emptySet(),
                        failedFields = emptySet(),
                    )
                } else {
                    result.copy(
                        status = ApplyResultStatus.INDETERMINATE,
                        message = "Rollback moved into place but the restored SHA-256 does not match the reviewed original.",
                        resultSha256 = restored,
                        rollbackFile = null,
                    )
                }
            } catch (error: Exception) {
                return result.copy(
                    status = ApplyResultStatus.INDETERMINATE,
                    message = "Rollback could not be proven: ${error.message}",
                )
            } finally {
                leases.remove(leaseKey, lease)
            }
        }
    }

    fun applyBatch(
        approvals: List<FileApproval>,
        stagingDirectory: File,
    ): List<FileApplyResult> {
        val results = mutableListOf<FileApplyResult>()
        for (approval in approvals) {
            val result = apply(approval, stagingDirectory)
            results += result
            if (result.status == ApplyResultStatus.INDETERMINATE) break
        }
        return results
    }

    /**
     * Execute one already-previewed batch plan sequentially. Dry-run is the default and
     * performs no tag staging or byte replacement. A real execution additionally requires an
     * explicit confirmation flag; every item still passes through its own SHA-256 guard.
     */
    fun executeBatchPlan(
        plan: FolderTagBatchPlan,
        stagingDirectory: File,
        dryRun: Boolean = true,
        confirmWrite: Boolean = false,
        onProgress: (FolderTagBatchProgress) -> Unit = {},
    ): FolderTagBatchExecutionResult {
        require(dryRun || confirmWrite) { "non-dry-run batch apply requires explicit confirmation" }
        validateBatchPlan(plan)
        if (plan.items.isEmpty()) return FolderTagBatchExecutionResult(plan, dryRun, emptyList())

        if (dryRun) {
            val preflight = mutableListOf<FolderTagBatchPreflightItem>()
            plan.items.forEachIndexed { index, item ->
                val file = item.approval.identity.file.canonicalFile
                val actualHash = runCatching {
                    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file.toPath())) {
                        null
                    } else {
                        file.sha256()
                    }
                }.getOrNull()
                val ready = actualHash != null && actualHash.equals(item.expectedContentHash, ignoreCase = true)
                preflight += FolderTagBatchPreflightItem(
                    identity = item.approval.identity,
                    expectedSha256 = item.expectedContentHash,
                    actualSha256 = actualHash,
                    ready = ready,
                    message = when {
                        actualHash == null -> "File is unavailable as a regular non-symlink file."
                        ready -> "Content hash still matches the reviewed approval."
                        else -> "File content changed after review; rescan before applying."
                    },
                )
                onProgress(
                    FolderTagBatchProgress(
                        completed = index + 1,
                        total = plan.items.size,
                        identity = item.approval.identity,
                        dryRun = true,
                        status = if (ready) null else ApplyResultStatus.CONFLICTED,
                    ),
                )
            }
            return FolderTagBatchExecutionResult(plan, true, emptyList(), preflight)
        }

        val results = mutableListOf<FileApplyResult>()
        for ((index, item) in plan.items.withIndex()) {
            val result = apply(item.approval, stagingDirectory)
            results += result
            onProgress(
                FolderTagBatchProgress(
                    completed = index + 1,
                    total = plan.items.size,
                    identity = item.approval.identity,
                    dryRun = false,
                    status = result.status,
                ),
            )
            if (result.status == ApplyResultStatus.INDETERMINATE) break
        }
        return FolderTagBatchExecutionResult(plan, false, results)
    }

    private fun validateBatchPlan(plan: FolderTagBatchPlan) {
        val root = plan.rootDirectory.canonicalFile
        require(root.isDirectory) { "batch root must remain a directory: $root" }
        val rootPath = root.toPath()
        val seen = mutableSetOf<String>()
        plan.items.forEach { item ->
            val file = item.approval.identity.file.canonicalFile
            val filePath = file.toPath()
            require(filePath.startsWith(rootPath) && filePath != rootPath) {
                "batch item escaped the selected root: ${item.relativePath}"
            }
            val relative = rootPath.relativize(filePath).toString().replace(File.separatorChar, '/')
            require(relative == item.relativePath) { "batch relative path no longer matches approved file identity" }
            require(seen.add(file.absolutePath)) { "batch plan contains the same file more than once" }
        }
    }

    private fun rollbackAfterVerificationFailure(
        approval: FileApproval,
        originalHash: String,
        rollback: File,
        metadata: OriginalFileMetadata,
        failedFields: Set<TagField>,
        reason: String,
    ): FileApplyResult {
        val canonicalFile = approval.identity.file.canonicalFile
        val replaced = atomicReplaceOperation(rollback, canonicalFile)
        if (!replaced) {
            val restored = runCatching { canonicalFile.sha256() }.getOrNull()
            if (restored != null && restored.equals(originalHash, ignoreCase = true)) {
                val durable = runCatching {
                    applyMetadata(canonicalFile, metadata)
                    forceFile(canonicalFile)
                    canonicalFile.parentFile?.let(::forceDirectory)
                }.isSuccess
                if (durable) {
                    runCatching { recoveryAuthority.disarm(canonicalFile, rollback) }
                    return FileApplyResult(
                        identity = approval.identity,
                        status = ApplyResultStatus.FAILED,
                        message = "$reason Automatic rollback reported an interruption, but exact original bytes were durably verified as restored.",
                        originalSha256 = originalHash,
                        resultSha256 = restored,
                        failedFields = failedFields,
                    )
                }
            }
            return FileApplyResult(
                identity = approval.identity,
                status = ApplyResultStatus.INDETERMINATE,
                message = "$reason Automatic rollback reported failure and durable exact restoration could not be proven; retained rollback bytes remain recovery evidence when available.",
                originalSha256 = originalHash,
                resultSha256 = restored,
                rollbackFile = rollback.takeIf(File::isFile),
                failedFields = failedFields,
            )
        }
        return try {
            applyMetadata(canonicalFile, metadata)
            forceFile(canonicalFile)
            canonicalFile.parentFile?.let(::forceDirectory)
            val restored = canonicalFile.sha256()
            if (restored.equals(originalHash, ignoreCase = true)) {
                runCatching { recoveryAuthority.disarm(canonicalFile, rollback) }
                FileApplyResult(
                    identity = approval.identity,
                    status = ApplyResultStatus.FAILED,
                    message = "$reason Exact original bytes were restored automatically.",
                    originalSha256 = originalHash,
                    resultSha256 = restored,
                    failedFields = failedFields,
                )
            } else {
                FileApplyResult(
                    identity = approval.identity,
                    status = ApplyResultStatus.INDETERMINATE,
                    message = "$reason Rollback completed but original SHA-256 could not be proven.",
                    originalSha256 = originalHash,
                    resultSha256 = restored,
                    failedFields = failedFields,
                )
            }
        } catch (error: Exception) {
            FileApplyResult(
                identity = approval.identity,
                status = ApplyResultStatus.INDETERMINATE,
                message = "$reason Rollback verification failed: ${error.message}",
                originalSha256 = originalHash,
                failedFields = failedFields,
            )
        }
    }

    private fun createRollbackSibling(source: File, expectedHash: String): File {
        val parent = source.parentFile.toPath()
        val suffix = source.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
        val rollback = Files.createTempFile(parent, ".properpcloud-rollback-", suffix).toFile()
        try {
            Files.copy(
                source.toPath(),
                rollback.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES,
            )
            forceFile(rollback)
            check(rollback.sha256().equals(expectedHash, ignoreCase = true)) {
                "rollback SHA-256 differs from reviewed original"
            }
            return rollback
        } catch (error: Throwable) {
            rollback.delete()
            throw error
        }
    }

    private fun verifyPatch(patch: TagPatch, actualFields: Map<TagField, String>): Set<TagField> =
        patch.mutations.mapNotNullTo(mutableSetOf()) { (field, mutation) ->
            val actual = actualFields[field]
            when (mutation) {
                TagMutation.Keep -> null
                TagMutation.Clear -> field.takeIf { actual != null }
                is TagMutation.Set -> field.takeIf { actual != mutation.value }
            }
        }

    private data class OriginalFileMetadata(
        val modifiedTime: FileTime,
        val permissions: Set<PosixFilePermission>?,
    )

    private fun captureMetadata(file: File): OriginalFileMetadata = OriginalFileMetadata(
        modifiedTime = Files.getLastModifiedTime(file.toPath(), LinkOption.NOFOLLOW_LINKS),
        permissions = runCatching { Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS) }.getOrNull(),
    )

    private fun applyMetadata(file: File, metadata: OriginalFileMetadata) {
        metadata.permissions?.let { permissions ->
            runCatching { Files.setPosixFilePermissions(file.toPath(), permissions) }
                .getOrElse { throw IllegalStateException("could not preserve POSIX permissions", it) }
        }
        Files.setLastModifiedTime(file.toPath(), metadata.modifiedTime)
    }

    private fun forceFile(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun forceDirectory(directory: File) {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun failed(
        approval: FileApproval,
        message: String,
        originalHash: String = approval.expectedContentHash,
    ) = FileApplyResult(
        identity = approval.identity,
        status = ApplyResultStatus.FAILED,
        message = message,
        originalSha256 = originalHash,
    )
}

private fun atomicReplaceFile(source: File, target: File): Boolean = try {
    Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
    true
} catch (_: Exception) {
    false
}

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
