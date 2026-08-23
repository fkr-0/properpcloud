package dev.properpcloud.desktop

import dev.properpcloud.core.model.ApplyResultStatus
import dev.properpcloud.core.model.ApprovedFieldEdit
import dev.properpcloud.core.model.ContentEvidence
import dev.properpcloud.core.model.FieldDecision
import dev.properpcloud.core.model.FileApproval
import dev.properpcloud.core.model.LocalFileIdentity
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagFieldProposal
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.core.model.TagSnapshot
import dev.properpcloud.desktop.data.DesktopLocalFilesystemIdentity
import dev.properpcloud.desktop.metadata.DesktopLocalFolderBinding
import dev.properpcloud.desktop.metadata.DesktopLocalTagRecoveryAuthority
import dev.properpcloud.metadata.tags.AudioTagToolkit
import dev.properpcloud.metadata.tags.FolderTagApplyService
import dev.properpcloud.metadata.tags.StagedTagResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

private const val PROCESS_RECOVERY_TRACK = "process-recovery-track.mp3"
private const val ORIGINAL_SMOKE_BYTES = "properpcloud-process-recovery-original"
private const val CANDIDATE_SMOKE_BYTES = "properpcloud-process-recovery-candidate"

/**
 * First half of the packaged cross-process recovery smoke.
 *
 * The shell harness launches this mode in the packaged executable, waits until the exact
 * candidate has crossed the destructive atomic-move boundary, then sends SIGKILL externally.
 * This function deliberately blocks after the move so normal verification/disarm cannot run.
 */
internal fun runLocalTagRecoveryPreKillSmoke(selectedRoot: String): Nothing {
    val root = File(selectedRoot)
    require(root.isDirectory && root.canRead() && root.canWrite()) {
        "recovery smoke root must be a writable directory"
    }
    val track = File(root, PROCESS_RECOVERY_TRACK)
    require(!track.exists()) { "recovery smoke root must not contain the fixture track" }
    track.writeText(ORIGINAL_SMOKE_BYTES)

    val identity = DesktopLocalFilesystemIdentity.forSelectedRoot(root)
    val authority = DesktopLocalTagRecoveryAuthority()
    val service = FolderTagApplyService(
        toolkit = ProcessRecoverySmokeToolkit(),
        atomicReplaceOperation = { from, to ->
            Files.move(
                from.toPath(),
                to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            println("properpcloud local tag recovery process smoke: replacement-complete; awaiting external kill")
            System.out.flush()
            while (true) Thread.sleep(60_000)
            @Suppress("UNREACHABLE_CODE")
            error("external kill smoke unexpectedly resumed")
        },
        recoveryAuthority = authority,
    )
    service.apply(processRecoveryApproval(track, identity), root)
    error("local tag recovery kill smoke returned past the destructive boundary")
}

/**
 * Second half of the packaged cross-process recovery smoke.
 *
 * A fresh packaged process receives the root again, which represents explicit reselection in
 * the non-interactive release harness. It rebuilds the normal DesktopLocalFolderBinding, lets
 * that binding rediscover durable recovery authority, and performs only the existing guarded
 * exact-hash rollback path.
 */
internal fun runLocalTagRecoveryRestartSmoke(selectedRoot: String) = runBlocking {
    val root = File(selectedRoot)
    val track = File(root, PROCESS_RECOVERY_TRACK)
    require(track.isFile) { "recovery smoke fixture is unavailable after restart" }

    DesktopLocalFolderBinding.createSelected(
        selectedRoot = root,
        recursive = false,
        toolkit = ProcessRecoverySmokeToolkit(),
    ).use { binding ->
        val opened = binding.open()
        check(opened.value != null) { "restarted local workbench could not open" }
        check(binding.recoveryState.recoveryRequired) {
            "restarted process did not rediscover interrupted recovery"
        }
        check(binding.recoveryState.issues.isEmpty()) {
            "restarted process found blocked instead of recoverable evidence"
        }
        val recovered = binding.recoveryState.recoverableResults.single()
        check(recovered.identity.filename == PROCESS_RECOVERY_TRACK) { "recovered fixture identity changed" }
        check(recovered.status == ApplyResultStatus.INDETERMINATE) {
            "interrupted replacement did not remain indeterminate"
        }

        val rolledBack = binding.rollbackTag(recovered)
        val result = requireNotNull(rolledBack.value) { "guarded rollback did not return a result" }
        check(result.status == ApplyResultStatus.VERIFIED) { "guarded rollback was not verified" }
        check(track.readText() == ORIGINAL_SMOKE_BYTES) {
            "guarded rollback did not restore exact original fixture bytes"
        }
        check(!binding.recoveryState.recoveryRequired) {
            "durable recovery authority remained armed after verified rollback"
        }
    }

    println(
        "properpcloud local tag recovery process smoke: OK " +
            "(fresh packaged process, explicit root reselection boundary, durable discovery, exact-hash guarded rollback)",
    )
}

private fun processRecoveryApproval(
    track: File,
    identity: DesktopLocalFilesystemIdentity,
): FileApproval {
    val original = TagSnapshot(
        format = "process-recovery-smoke",
        fields = mapOf(TagField.TITLE to MetadataValue("Old", MetadataProvenance.EMBEDDED)),
    )
    val proposal = TagFieldProposal(
        field = TagField.TITLE,
        ruleId = "process-recovery-smoke",
        currentValue = "Old",
        proposedValue = "New",
        confidence = 1.0,
        autoPreselected = false,
        explanation = "packaged process recovery smoke fixture",
    )
    return FileApproval(
        identity = LocalFileIdentity(
            sourceId = identity.sourceId,
            nodeId = identity.nodeId(track, directory = false),
            file = track.canonicalFile,
            filename = track.name,
            contentEvidence = ContentEvidence(
                sizeBytes = track.length(),
                modifiedTimeNanos = track.lastModified() * 1_000_000L,
            ),
        ),
        approvedFields = mapOf(
            TagField.TITLE to ApprovedFieldEdit(
                field = TagField.TITLE,
                decision = FieldDecision.SET,
                finalValue = "New",
                proposal = proposal,
            ),
        ),
        originalSnapshot = original,
        expectedContentHash = track.processRecoverySha256(),
    )
}

private class ProcessRecoverySmokeToolkit : AudioTagToolkit {
    override fun inspect(file: File): TagSnapshot = TagSnapshot(
        format = "process-recovery-smoke",
        fields = mapOf(
            TagField.TITLE to MetadataValue(
                value = if (file.readText() == ORIGINAL_SMOKE_BYTES) "Old" else "New",
                provenance = MetadataProvenance.EMBEDDED,
            ),
        ),
    )

    override fun stagePatch(
        source: File,
        stagingDirectory: File,
        patch: TagPatch,
        expectedSourceSha256: String?,
    ): StagedTagResult {
        check(expectedSourceSha256 == source.processRecoverySha256()) {
            "recovery smoke source changed before staging"
        }
        val staged = Files.createTempFile(
            stagingDirectory.toPath(),
            ".properpcloud-stage-process-recovery-",
            ".mp3",
        ).toFile()
        staged.writeText(CANDIDATE_SMOKE_BYTES)
        return StagedTagResult(
            stagedFile = staged,
            sourceSha256 = requireNotNull(expectedSourceSha256),
            stagedSha256 = staged.processRecoverySha256(),
            snapshot = TagSnapshot(
                format = "process-recovery-smoke",
                fields = mapOf(TagField.TITLE to MetadataValue("New", MetadataProvenance.EMBEDDED)),
            ),
            changedFields = setOf(TagField.TITLE),
        )
    }
}

private fun File.processRecoverySha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
