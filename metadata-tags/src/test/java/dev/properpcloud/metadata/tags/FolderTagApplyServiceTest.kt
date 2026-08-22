package dev.properpcloud.metadata.tags

import dev.properpcloud.core.model.ApplyResultStatus
import dev.properpcloud.core.model.ApprovedFieldEdit
import dev.properpcloud.core.model.ContentEvidence
import dev.properpcloud.core.model.FieldDecision
import dev.properpcloud.core.model.FileApproval
import dev.properpcloud.core.model.LocalFileIdentity
import dev.properpcloud.core.model.MetadataProvenance
import dev.properpcloud.core.model.MetadataValue
import dev.properpcloud.core.model.NodeId
import dev.properpcloud.core.model.SourceId
import dev.properpcloud.core.model.TagField
import dev.properpcloud.core.model.TagFieldProposal
import dev.properpcloud.core.model.TagPatch
import dev.properpcloud.core.model.TagSnapshot
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderTagApplyServiceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun unavailableAtomicReplaceRetainsVerifiedExportAndLeavesSourceUntouched() {
        val source = temporary.newFile("track.mp3").apply { writeText("original") }
        val approval = approval(source)
        val service = FolderTagApplyService(FakeToolkit()) { _, _ -> false }

        val result = service.apply(approval, temporary.newFolder("scratch"))

        assertEquals(ApplyResultStatus.EXPORTED, result.status)
        assertEquals("original", source.readText())
        assertNotNull(result.exportFile)
        assertTrue(result.exportFile!!.isFile)
        assertTrue(result.exportFile!!.name.startsWith(".properpcloud-stage-"))
        assertFalse(result.exportFile!!.absolutePath.contains("http"))
    }

    @Test
    fun failedReadbackAutomaticallyRestoresExactOriginalBytes() {
        val source = temporary.newFile("rollback.mp3").apply { writeText("original") }
        val approval = approval(source)
        val service = FolderTagApplyService(FakeToolkit(finalInspectionMatches = false))

        val result = service.apply(approval, temporary.newFolder("scratch-rollback"))

        assertEquals(ApplyResultStatus.FAILED, result.status)
        assertEquals("original", source.readText())
        assertEquals(approval.expectedContentHash, source.sha256())
        assertTrue(result.message.contains("restored automatically"))
    }

    @Test
    fun userRollbackRefusesToOverwriteAFileChangedAfterVerifiedApply() {
        val source = temporary.newFile("changed-after-apply.mp3").apply { writeText("original") }
        val approval = approval(source)
        val service = FolderTagApplyService(FakeToolkit())
        val applied = service.apply(approval, temporary.newFolder("scratch-applied"))
        assertEquals(ApplyResultStatus.VERIFIED, applied.status)
        assertNotNull(applied.rollbackFile)

        source.writeText("external-change")
        val rolledBack = service.rollback(applied)

        assertEquals(ApplyResultStatus.CONFLICTED, rolledBack.status)
        assertEquals("external-change", source.readText())
        assertTrue(applied.rollbackFile!!.exists())
    }

    @Test
    fun userRollbackRefusesUnknownCurrentResultHashEvenWhenRecoveryBytesExist() {
        val source = temporary.newFile("unknown-current.mp3").apply { writeText("original") }
        val approval = approval(source)
        val service = FolderTagApplyService(FakeToolkit())
        val applied = service.apply(approval, temporary.newFolder("scratch-unknown-current"))
        assertEquals(ApplyResultStatus.VERIFIED, applied.status)
        assertNotNull(applied.rollbackFile)
        assertEquals("candidate", source.readText())

        val unknown = applied.copy(
            status = ApplyResultStatus.INDETERMINATE,
            resultSha256 = null,
        )
        val rolledBack = service.rollback(unknown)

        assertEquals(ApplyResultStatus.INDETERMINATE, rolledBack.status)
        assertEquals("candidate", source.readText())
        assertTrue(applied.rollbackFile!!.exists())
        assertTrue(rolledBack.message.contains("current result hash was never proven"))
    }

    @Test
    fun hashConflictStopsBeforeAnyTagStaging() {
        val source = temporary.newFile("hash-conflict.mp3").apply { writeText("original") }
        val approval = approval(source)
        val toolkit = FakeToolkit()
        val service = FolderTagApplyService(toolkit)

        source.writeText("changed-after-review")
        val result = service.apply(approval, temporary.newFolder("scratch-conflict"))

        assertEquals(ApplyResultStatus.CONFLICTED, result.status)
        assertEquals(0, toolkit.stagePatchCalls)
        assertEquals("changed-after-review", source.readText())
    }

    @Test
    fun durableRecoveryAuthorityIsArmedBeforeReplaceAndDisarmedAfterVerifiedApply() {
        val source = temporary.newFile("durable-order.mp3").apply { writeText("original") }
        val approval = approval(source)
        val toolkit = FakeToolkit()
        val recovery = FakeRecoveryAuthority()
        var replaceObservedArmed = false
        val service = FolderTagApplyService(
            toolkit = toolkit,
            atomicReplaceOperation = { from, to ->
                replaceObservedArmed = recovery.armCalls == 1 && recovery.disarmCalls == 0
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            },
            recoveryAuthority = recovery,
        )

        val result = service.apply(approval, temporary.newFolder("durable-order-scratch"))

        assertEquals(ApplyResultStatus.VERIFIED, result.status)
        assertTrue(replaceObservedArmed)
        assertEquals(1, recovery.armCalls)
        assertEquals(1, recovery.disarmCalls)
        assertEquals(approval.expectedContentHash, recovery.originalSha256)
        assertEquals(result.resultSha256, recovery.expectedResultSha256)
    }

    @Test
    fun recoveryArmFailureStopsBeforeMediaReplacement() {
        val source = temporary.newFile("arm-failure.mp3").apply { writeText("original") }
        val approval = approval(source)
        val recovery = FakeRecoveryAuthority(failArm = true)
        var replaceCalls = 0
        val service = FolderTagApplyService(
            toolkit = FakeToolkit(),
            atomicReplaceOperation = { _, _ -> replaceCalls += 1; true },
            recoveryAuthority = recovery,
        )

        val result = service.apply(approval, temporary.newFolder("arm-failure-scratch"))

        assertEquals(ApplyResultStatus.FAILED, result.status)
        assertEquals("original", source.readText())
        assertEquals(0, replaceCalls)
        assertEquals(1, recovery.armCalls)
        assertEquals(1, recovery.disarmCalls)
    }

    @Test
    fun sourceChangeDuringStagingIsAConflictBeforeAtomicReplacement() {
        val source = temporary.newFile("stage-race.mp3").apply { writeText("original") }
        val approval = approval(source)
        var replaceCalls = 0
        val toolkit = FakeToolkit(afterStage = { it.writeText("external-after-stage") })
        val service = FolderTagApplyService(toolkit) { _, _ ->
            replaceCalls += 1
            true
        }

        val result = service.apply(approval, temporary.newFolder("scratch-stage-race"))

        assertEquals(ApplyResultStatus.CONFLICTED, result.status)
        assertEquals("external-after-stage", source.readText())
        assertEquals(1, toolkit.stagePatchCalls)
        assertEquals(0, replaceCalls)
    }

    @Test
    fun ambiguousReplacementRetainsGuardedRollbackAndBatchStopsUntilRecovery() {
        val root = temporary.newFolder("ambiguous-root")
        val first = File(root, "1-first.mp3").apply { writeText("original") }
        val second = File(root, "2-second.mp3").apply { writeText("original") }
        val toolkit = FakeToolkit()
        val recovery = FakeRecoveryAuthority()
        var replaceCalls = 0
        val service = FolderTagApplyService(
            toolkit = toolkit,
            atomicReplaceOperation = { from, to ->
                replaceCalls += 1
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                replaceCalls != 1
            },
            recoveryAuthority = recovery,
        )
        val plan = FolderTagBatchPlan(
            rootDirectory = root,
            recursive = false,
            recursiveOptInConfirmed = true,
            items = listOf(
                FolderTagBatchPlanItem(approval(first), first.name),
                FolderTagBatchPlanItem(approval(second), second.name),
            ),
        )

        val execution = service.executeBatchPlan(
            plan = plan,
            stagingDirectory = temporary.newFolder("ambiguous-scratch"),
            dryRun = false,
            confirmWrite = true,
        )

        assertEquals(1, execution.results.size)
        val interrupted = execution.results.single()
        assertEquals(ApplyResultStatus.INDETERMINATE, interrupted.status)
        assertEquals("candidate", first.readText())
        assertEquals("original", second.readText())
        assertEquals(first.sha256(), interrupted.resultSha256)
        assertNotNull(interrupted.rollbackFile)
        assertTrue(interrupted.rollbackFile!!.isFile)
        assertEquals(1, toolkit.stagePatchCalls)
        assertEquals(1, recovery.armCalls)
        assertEquals(0, recovery.disarmCalls)

        val recovered = service.rollback(interrupted)

        assertEquals(ApplyResultStatus.VERIFIED, recovered.status)
        assertEquals("original", first.readText())
        assertEquals(interrupted.originalSha256, first.sha256())
        assertEquals(2, replaceCalls)
        assertEquals(1, recovery.disarmCalls)
    }

    @Test
    fun batchDryRunSurfacesHashConflictWithoutCreatingStagingBytes() {
        val root = temporary.newFolder("dry-run-root")
        val source = File(root, "track.mp3").apply { writeText("original") }
        val approval = approval(source)
        val toolkit = FakeToolkit()
        val service = FolderTagApplyService(toolkit)
        val plan = FolderTagBatchPlan(
            rootDirectory = root,
            recursive = false,
            recursiveOptInConfirmed = true,
            items = listOf(FolderTagBatchPlanItem(approval, source.name)),
        )
        source.writeText("changed-after-review")
        val scratch = File(root, "dry-run-scratch")

        val result = service.executeBatchPlan(plan, scratch, dryRun = true)

        assertTrue(result.dryRun)
        assertEquals(1, result.preflight.size)
        assertFalse(result.preflight.single().ready)
        assertEquals(source.sha256(), result.preflight.single().actualSha256)
        assertEquals(0, toolkit.stagePatchCalls)
        assertFalse(scratch.exists())
    }

    @Test
    fun confirmedBatchApplyReportsProgressAndRetainsRollbackEvidence() {
        val root = temporary.newFolder("batch-root")
        val first = File(root, "1-first.mp3").apply { writeText("original") }
        val second = File(root, "2-second.mp3").apply { writeText("original") }
        val toolkit = FakeToolkit()
        val service = FolderTagApplyService(toolkit)
        val plan = FolderTagBatchPlan(
            rootDirectory = root,
            recursive = true,
            recursiveOptInConfirmed = true,
            items = listOf(
                FolderTagBatchPlanItem(approval(first), first.name),
                FolderTagBatchPlanItem(approval(second), second.name),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.executeBatchPlan(plan, temporary.newFolder("batch-rejected"), dryRun = false)
        }

        val progress = mutableListOf<FolderTagBatchProgress>()
        val execution = service.executeBatchPlan(
            plan = plan,
            stagingDirectory = temporary.newFolder("batch-scratch"),
            dryRun = false,
            confirmWrite = true,
            onProgress = progress::add,
        )

        assertFalse(execution.dryRun)
        assertEquals(2, execution.results.size)
        assertTrue(execution.results.all { it.status == ApplyResultStatus.VERIFIED && it.rollbackFile != null })
        assertEquals(2, toolkit.stagePatchCalls)
        assertEquals(listOf(1, 2), progress.map { it.completed })
        assertEquals(
            listOf(first.name, second.name),
            progress.map { it.identity.filename },
        )

        val rolledBack = service.rollback(execution.results.last())
        assertEquals(ApplyResultStatus.VERIFIED, rolledBack.status)
        assertEquals("original", second.readText())
    }

    private fun approval(source: File): FileApproval {
        val original = TagSnapshot(
            format = "ID3v2.4",
            fields = mapOf(TagField.TITLE to MetadataValue("Old", MetadataProvenance.EMBEDDED)),
        )
        val proposal = TagFieldProposal(
            field = TagField.TITLE,
            ruleId = "test",
            currentValue = "Old",
            proposedValue = "New",
            confidence = 1.0,
            autoPreselected = false,
            explanation = "test review",
        )
        return FileApproval(
            identity = LocalFileIdentity(
                sourceId = SourceId("local"),
                nodeId = NodeId("file:${source.name}"),
                file = source.canonicalFile,
                filename = source.name,
                contentEvidence = ContentEvidence(source.length(), source.lastModified() * 1_000_000L),
            ),
            approvedFields = mapOf(
                TagField.TITLE to ApprovedFieldEdit(TagField.TITLE, FieldDecision.SET, "New", proposal),
            ),
            originalSnapshot = original,
            expectedContentHash = source.sha256(),
        )
    }

    private class FakeRecoveryAuthority(
        private val failArm: Boolean = false,
    ) : LocalTagRecoveryAuthority {
        var armCalls = 0
        var disarmCalls = 0
        var originalSha256: String? = null
        var expectedResultSha256: String? = null

        override fun arm(
            target: File,
            rollbackFile: File,
            originalSha256: String,
            expectedResultSha256: String,
        ) {
            armCalls += 1
            this.originalSha256 = originalSha256
            this.expectedResultSha256 = expectedResultSha256
            if (failArm) error("simulated durable recovery failure")
        }

        override fun disarm(target: File, rollbackFile: File) {
            disarmCalls += 1
        }
    }

    private class FakeToolkit(
        private val finalInspectionMatches: Boolean = true,
        private val afterStage: (File) -> Unit = {},
    ) : AudioTagToolkit {
        var stagePatchCalls: Int = 0

        override fun inspect(file: File): TagSnapshot {
            val title = when (file.readText()) {
                "original" -> "Old"
                "candidate" -> if (finalInspectionMatches) "New" else "Unexpected"
                else -> "Unexpected"
            }
            return TagSnapshot(
                "ID3v2.4",
                mapOf(TagField.TITLE to MetadataValue(title, MetadataProvenance.EMBEDDED)),
            )
        }

        override fun stagePatch(
            source: File,
            stagingDirectory: File,
            patch: TagPatch,
            expectedSourceSha256: String?,
        ): StagedTagResult {
            stagePatchCalls += 1
            check(expectedSourceSha256 == source.sha256())
            val sourceHash = source.sha256()
            val staged = File(stagingDirectory, ".properpcloud-stage-test-${UUID.randomUUID()}.mp3")
            source.copyTo(staged)
            staged.writeText("candidate")
            val stagedHash = staged.sha256()
            afterStage(source)
            return StagedTagResult(
                stagedFile = staged,
                sourceSha256 = sourceHash,
                stagedSha256 = stagedHash,
                snapshot = TagSnapshot(
                    "ID3v2.4",
                    mapOf(TagField.TITLE to MetadataValue("New", MetadataProvenance.EMBEDDED)),
                ),
                changedFields = setOf(TagField.TITLE),
            )
        }
    }
}
