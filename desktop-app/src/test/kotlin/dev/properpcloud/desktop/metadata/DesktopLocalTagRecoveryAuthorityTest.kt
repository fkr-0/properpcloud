package dev.properpcloud.desktop.metadata

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
import dev.properpcloud.metadata.tags.AudioTagToolkit
import dev.properpcloud.metadata.tags.FolderTagApplyService
import dev.properpcloud.metadata.tags.StagedTagResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopLocalTagRecoveryAuthorityTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `terminate after replacement then restart and reselect recovers exact guarded rollback`() {
        val root = temporary.newFolder("restart-root")
        val track = File(root, "01 private track.mp3").apply { writeText("original") }
        val identity = DesktopLocalFilesystemIdentity.forSelectedRoot(root)
        val authority = DesktopLocalTagRecoveryAuthority()
        val service = FolderTagApplyService(
            toolkit = RecoveryToolkit(),
            atomicReplaceOperation = { from, to ->
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                throw SimulatedPowerLoss()
            },
            recoveryAuthority = authority,
        )

        assertThrows(SimulatedPowerLoss::class.java) {
            service.apply(approval(track, identity), root)
        }
        assertEquals("candidate", track.readText())
        val record = recoveryRecords(root).single()
        val persisted = record.readText()
        assertFalse(persisted.contains(root.absolutePath))
        assertFalse(persisted.contains(track.absolutePath))
        assertFalse(persisted.contains(track.name))

        // Simulate a fresh process: reconstruct identity and authority only after the user has
        // explicitly reselected the same filesystem root.
        val restartedIdentity = DesktopLocalFilesystemIdentity.forSelectedRoot(root)
        val restartedAuthority = DesktopLocalTagRecoveryAuthority()
        val discovered = restartedAuthority.discover(restartedIdentity)

        assertTrue(discovered.recoveryRequired)
        assertTrue(discovered.issues.isEmpty())
        assertEquals(1, discovered.rollbackAvailableCount)
        val recovered = discovered.recoverableResults.single()
        assertEquals(ApplyResultStatus.INDETERMINATE, recovered.status)
        assertEquals(track.canonicalFile, recovered.identity.file.canonicalFile)
        assertEquals(track.sha256(), recovered.resultSha256)
        assertNotNull(recovered.rollbackFile)
        assertTrue(recovered.rollbackFile!!.isFile)

        val restartedService = FolderTagApplyService(RecoveryToolkit(), restartedAuthority)
        val rolledBack = restartedService.rollback(recovered)

        assertEquals(ApplyResultStatus.VERIFIED, rolledBack.status)
        assertEquals("original", track.readText())
        assertFalse(restartedAuthority.discover(restartedIdentity).recoveryRequired)
        assertTrue(recoveryRecords(root).isEmpty())
    }

    @Test
    fun `record armed before replacement is cleaned when restart proves original bytes are still current`() {
        val root = temporary.newFolder("pre-replace-root")
        val track = File(root, "track.mp3").apply { writeText("original") }
        val rollback = File(root, ".properpcloud-rollback-pre-replace.mp3").apply { writeText("original") }
        val authority = DesktopLocalTagRecoveryAuthority()
        authority.arm(
            target = track,
            rollbackFile = rollback,
            originalSha256 = track.sha256(),
            expectedResultSha256 = "candidate".sha256Text(),
        )
        assertEquals(1, recoveryRecords(root).size)

        val restarted = DesktopLocalTagRecoveryAuthority().discover(DesktopLocalFilesystemIdentity.forSelectedRoot(root))

        assertFalse(restarted.recoveryRequired)
        assertEquals(1, restarted.cleanedRecordCount)
        assertFalse(rollback.exists())
        assertTrue(recoveryRecords(root).isEmpty())
        assertEquals("original", track.readText())
    }

    @Test
    fun `external edit after interrupted replacement remains blocked and preserves recovery evidence`() {
        val root = temporary.newFolder("external-edit-root")
        val track = File(root, "track.mp3").apply { writeText("original") }
        val identity = DesktopLocalFilesystemIdentity.forSelectedRoot(root)
        val authority = DesktopLocalTagRecoveryAuthority()
        val service = FolderTagApplyService(
            toolkit = RecoveryToolkit(),
            atomicReplaceOperation = { from, to ->
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                throw SimulatedPowerLoss()
            },
            recoveryAuthority = authority,
        )
        assertThrows(SimulatedPowerLoss::class.java) { service.apply(approval(track, identity), root) }
        val record = recoveryRecords(root).single()
        val rollback = root.listFiles().orEmpty().single { it.name.startsWith(".properpcloud-rollback-") }

        track.writeText("external-change")
        val restarted = DesktopLocalTagRecoveryAuthority().discover(DesktopLocalFilesystemIdentity.forSelectedRoot(root))

        assertTrue(restarted.recoveryRequired)
        assertTrue(restarted.recoverableResults.isEmpty())
        assertEquals(1, restarted.issues.size)
        assertTrue(restarted.issues.single().message.contains("no rollback overwrite is authorized"))
        assertTrue(record.isFile)
        assertTrue(rollback.isFile)
        assertEquals("external-change", track.readText())
    }

    @Test
    fun `malformed durable recovery record blocks and is never silently deleted`() {
        val root = temporary.newFolder("malformed-root")
        File(root, "track.mp3").writeText("original")
        val malformed = File(root, ".properpcloud-recovery-malformed.v1").apply {
            writeText("not-a-recovery-schema\n")
        }

        val discovered = DesktopLocalTagRecoveryAuthority().discover(DesktopLocalFilesystemIdentity.forSelectedRoot(root))

        assertTrue(discovered.recoveryRequired)
        assertTrue(discovered.recoverableResults.isEmpty())
        assertEquals(1, discovered.issues.size)
        assertTrue(malformed.isFile)
    }

    @Test
    fun `normal verified apply disarms cross-process record but retains same-session rollback bytes`() {
        val root = temporary.newFolder("verified-root")
        val track = File(root, "track.mp3").apply { writeText("original") }
        val identity = DesktopLocalFilesystemIdentity.forSelectedRoot(root)
        val authority = DesktopLocalTagRecoveryAuthority()
        val service = FolderTagApplyService(
            toolkit = RecoveryToolkit(),
            atomicReplaceOperation = { from, to ->
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            },
            recoveryAuthority = authority,
        )

        val applied = service.apply(approval(track, identity), root)

        assertEquals(ApplyResultStatus.VERIFIED, applied.status)
        assertEquals("candidate", track.readText())
        assertNotNull(applied.rollbackFile)
        assertTrue(applied.rollbackFile!!.isFile)
        assertTrue(recoveryRecords(root).isEmpty())
        assertFalse(authority.discover(identity).recoveryRequired)
    }

    private fun approval(track: File, identity: DesktopLocalFilesystemIdentity): FileApproval {
        val original = TagSnapshot(
            format = "ID3v2.4",
            fields = mapOf(TagField.TITLE to MetadataValue("Old", MetadataProvenance.EMBEDDED)),
        )
        val proposal = TagFieldProposal(
            field = TagField.TITLE,
            ruleId = "test-power-loss",
            currentValue = "Old",
            proposedValue = "New",
            confidence = 1.0,
            autoPreselected = false,
            explanation = "power-loss fixture",
        )
        return FileApproval(
            identity = LocalFileIdentity(
                sourceId = identity.sourceId,
                nodeId = identity.nodeId(track, directory = false),
                file = track.canonicalFile,
                filename = track.name,
                contentEvidence = ContentEvidence(track.length(), track.lastModified() * 1_000_000L),
            ),
            approvedFields = mapOf(
                TagField.TITLE to ApprovedFieldEdit(TagField.TITLE, FieldDecision.SET, "New", proposal),
            ),
            originalSnapshot = original,
            expectedContentHash = track.sha256(),
        )
    }

    private fun recoveryRecords(root: File): List<File> = root.walkTopDown()
        .filter { file -> file.isFile && file.name.startsWith(".properpcloud-recovery-") && file.name.endsWith(".v1") }
        .toList()

    private class RecoveryToolkit : AudioTagToolkit {
        override fun inspect(file: File): TagSnapshot {
            val title = when (file.readText()) {
                "original" -> "Old"
                "candidate" -> "New"
                else -> "Unexpected"
            }
            return TagSnapshot(
                format = "ID3v2.4",
                fields = mapOf(TagField.TITLE to MetadataValue(title, MetadataProvenance.EMBEDDED)),
            )
        }

        override fun stagePatch(
            source: File,
            stagingDirectory: File,
            patch: TagPatch,
            expectedSourceSha256: String?,
        ): StagedTagResult {
            check(expectedSourceSha256 == source.sha256())
            val sourceSha = source.sha256()
            val staged = File(stagingDirectory, ".properpcloud-stage-power-loss-${UUID.randomUUID()}.mp3")
            source.copyTo(staged)
            staged.writeText("candidate")
            return StagedTagResult(
                stagedFile = staged,
                sourceSha256 = sourceSha,
                stagedSha256 = staged.sha256(),
                snapshot = TagSnapshot(
                    format = "ID3v2.4",
                    fields = mapOf(TagField.TITLE to MetadataValue("New", MetadataProvenance.EMBEDDED)),
                ),
                changedFields = setOf(TagField.TITLE),
            )
        }
    }

    private class SimulatedPowerLoss : Error("simulated abrupt process termination")
}

private fun File.sha256(): String = readBytes().sha256Bytes()

private fun String.sha256Text(): String = toByteArray(Charsets.UTF_8).sha256Bytes()

private fun ByteArray.sha256Bytes(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
