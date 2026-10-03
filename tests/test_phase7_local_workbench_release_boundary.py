from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


class Phase7LocalWorkbenchReleaseBoundaryTest(unittest.TestCase):
    def test_apply_revalidates_after_rollback_copy_and_before_destructive_replace(self) -> None:
        source = read("metadata-tags/src/main/java/dev/properpcloud/metadata/tags/FolderTagApplyService.kt")
        rollback = source.index("createRollbackSibling(canonicalFile, currentHash)")
        final_guard = source.index("val immediatelyBeforeReplaceHash")
        replace = source.index("atomicReplaceOperation(staged.stagedFile, canonicalFile)")
        self.assertLess(rollback, final_guard)
        self.assertLess(final_guard, replace)
        self.assertIn("File content changed immediately before atomic replacement", source)

    def test_ambiguous_replace_keeps_recovery_bytes_and_only_guards_known_candidate(self) -> None:
        source = read("metadata-tags/src/main/java/dev/properpcloud/metadata/tags/FolderTagApplyService.kt")
        self.assertIn("val candidateIsCurrent = observedHash?.equals(staged.stagedSha256", source)
        self.assertIn("resultSha256 = observedHash.takeIf { candidateIsCurrent }", source)
        self.assertIn("rollbackFile = rollback.takeIf(File::isFile)", source)
        self.assertIn("current bytes match neither the reviewed original nor the staged candidate", source)
        self.assertIn("current result hash was never proven", source)
        self.assertIn("durably verified as restored", source)

    def test_session_and_host_have_no_force_rollback_escape_hatch(self) -> None:
        session = read("metadata-tags/src/main/java/dev/properpcloud/metadata/tags/FolderMetadataSuiteSession.kt")
        host = read("metadata-tags/src/main/java/dev/properpcloud/metadata/tags/LocalFolderWorkbenchHost.kt")
        self.assertIn("result.status == ApplyResultStatus.INDETERMINATE", session)
        self.assertIn("result.resultSha256 == null || result.rollbackFile == null", session)
        rollback_body = session[session.index("fun rollbackTagResult(") : session.index("/** Build a direct-folder", session.index("fun rollbackTagResult("))]
        self.assertNotIn("force", rollback_body.lower())
        self.assertIn("val indeterminate = execution.value.results.any", host)
        self.assertIn("reconciliationRequired = indeterminate", host)
        self.assertIn("suspend fun rollbackTagResult", host)

    def test_desktop_binding_confines_rollback_to_selected_root(self) -> None:
        source = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/metadata/DesktopLocalFolderBinding.kt")
        self.assertIn("targetPath != rootPath && targetPath.startsWith(rootPath)", source)
        self.assertIn("rollback target escaped the selected local root", source)
        self.assertIn("val rolledBack = host.rollbackTagResult(result)", source)
        self.assertIn("return projectRecoveryGate(rolledBack)", source)

    def test_controller_presents_redacted_outcomes_and_preserves_recovery_gate(self) -> None:
        source = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/DesktopController.kt")
        self.assertIn("data class DesktopLocalTagOutcome", source)
        self.assertIn("val recoveryRequired: Boolean = false", source)
        self.assertIn("localTagRecoveryResults.any { it.status == ApplyResultStatus.INDETERMINATE }", source)
        self.assertIn("fun rollbackLatestLocalTag", source)
        self.assertIn("localUserMessage(binding, applyResult.message)", source)
        self.assertIn('.replace(canonicalRoot, "<selected-root>")', source)
        self.assertIn('.replace(absoluteRoot, "<selected-root>")', source)
        self.assertNotIn("rollbackFile.absolutePath", source)

    def test_local_workbench_semantics_and_recovery_disable_mutations(self) -> None:
        source = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/DesktopUi.kt")
        self.assertIn("Local metadata workbench", source)
        self.assertIn("modifier = Modifier.semantics { heading() }", source)
        self.assertIn("&& !state.recoveryRequired", source)
        self.assertIn("Recovery required before additional metadata writes", source)
        self.assertIn("proposal.warnings.joinToString", source)
        self.assertIn('key = { _, proposal -> "${proposal.nodeId.value}:${proposal.field}:${proposal.ruleId}" }', source)
        self.assertIn("Rollback latest", source)
        self.assertIn("A later external edit causes a conflict instead of being overwritten", source)

    def test_large_text_layout_splits_consent_and_mutation_controls(self) -> None:
        source = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/DesktopUi.kt")
        tag_start = source.index("Allow recursive tag plan")
        playlist_start = source.index('Text("Playlist:')
        self.assertIn("Column(verticalArrangement = Arrangement.spacedBy(6.dp))", source[tag_start - 1200 : tag_start + 1800])
        self.assertIn("Column(verticalArrangement = Arrangement.spacedBy(6.dp))", source[playlist_start - 800 : playlist_start + 2800])
        self.assertIn('contentDescription = "Recursive playlists"', source)
        self.assertIn('contentDescription = "One playlist per album"', source)

    def test_accessibility_audit_uses_repository_evidence_scratch_not_bare_tmp(self) -> None:
        source = read("scripts/desktop-accessibility-audit.sh")
        self.assertIn('ACCESSIBILITY_TMP="$OUT_DIR/.accessibility-tmp"', source)
        self.assertIn('local log="$ACCESSIBILITY_TMP/', source)
        self.assertNotIn("/tmp/properpcloud-accessibility", source)
        self.assertIn("PROPERPCLOUD_HIGH_CONTRAST=1", source)
        self.assertIn("-Dsun.java2d.uiScale=2", source)
        self.assertIn("-Dcompose.accessibility.enable=true", source)

    def test_flatpak_permissions_remain_narrow_and_local_selector_fails_closed(self) -> None:
        package = read("scripts/package-flatpak.sh")
        selector = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/platform/LocalFolderSelector.kt")
        self.assertNotIn("--filesystem=host", package)
        self.assertNotIn("--filesystem=home", package)
        self.assertIn("--filesystem=xdg-run/properpcloud:create", package)
        self.assertIn("FLATPAK_ID", selector)
        self.assertIn("/.flatpak-info", selector)
        self.assertIn("document-portal", selector.lower())

    def test_kotlin_recovery_regressions_cover_conflict_interruption_and_host_recovery(self) -> None:
        service_test = read("metadata-tags/src/test/java/dev/properpcloud/metadata/tags/FolderTagApplyServiceTest.kt")
        host_test = read("metadata-tags/src/test/java/dev/properpcloud/metadata/tags/LocalFolderWorkbenchHostTest.kt")
        self.assertIn("userRollbackRefusesUnknownCurrentResultHashEvenWhenRecoveryBytesExist", service_test)
        self.assertIn("sourceChangeDuringStagingIsAConflictBeforeAtomicReplacement", service_test)
        self.assertIn("ambiguousReplacementRetainsGuardedRollbackAndBatchStopsUntilRecovery", service_test)
        self.assertIn("interruptedCandidateReplacementStaysStaleUntilGuardedRollbackReconciles", host_test)
        self.assertIn("assertEquals(LocalFolderWorkbenchWatchState.STALE", host_test)
        self.assertIn("assertEquals(LocalFolderWorkbenchWatchState.LIVE", host_test)


if __name__ == "__main__":
    unittest.main()
