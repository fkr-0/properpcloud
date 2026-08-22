from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


class Phase8LocalWorkbenchPowerLossRecoveryTest(unittest.TestCase):
    def test_shared_apply_arms_recovery_before_final_guard_and_destructive_replace(self) -> None:
        source = read("metadata-tags/src/main/java/dev/properpcloud/metadata/tags/FolderTagApplyService.kt")
        arm = source.index("recoveryAuthority.arm(")
        final_guard = source.index("val immediatelyBeforeReplaceHash")
        replace = source.index("atomicReplaceOperation(staged.stagedFile, canonicalFile)")
        self.assertLess(arm, final_guard)
        self.assertLess(final_guard, replace)
        self.assertIn("Could not durably arm local recovery before replacement", source)

    def test_shared_recovery_is_opt_in_and_legacy_replacement_constructor_remains(self) -> None:
        source = read("metadata-tags/src/main/java/dev/properpcloud/metadata/tags/FolderTagApplyService.kt")
        self.assertIn("interface LocalTagRecoveryAuthority", source)
        self.assertIn("object NoopLocalTagRecoveryAuthority", source)
        self.assertIn("constructor(toolkit: AudioTagToolkit)", source)
        self.assertIn("atomicReplaceOperation: (File, File) -> Boolean,", source)
        self.assertIn("recoveryAuthority = NoopLocalTagRecoveryAuthority", source)

    def test_desktop_recovery_record_persists_only_encoded_basenames_and_hashes(self) -> None:
        source = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/metadata/DesktopLocalTagRecoveryAuthority.kt")
        encoded = source[source.index("private fun encodeRecord(") : source.index("private fun decodeRecord(")]
        self.assertIn('appendLine("target=${encodeName(targetName)}")', encoded)
        self.assertIn('appendLine("rollback=${encodeName(rollbackName)}")', encoded)
        self.assertIn("original_sha256", encoded)
        self.assertIn("expected_result_sha256", encoded)
        self.assertNotIn("canonicalRoot", encoded)
        self.assertNotIn("absolutePath", encoded)
        self.assertNotIn("relativePath", encoded)

    def test_discovery_requires_explicit_selected_root_identity_and_never_follows_symlinks(self) -> None:
        recovery = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/metadata/DesktopLocalTagRecoveryAuthority.kt")
        binding = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/metadata/DesktopLocalFolderBinding.kt")
        self.assertIn("fun discover(identity: DesktopLocalFilesystemIdentity)", recovery)
        self.assertIn("Files.walkFileTree(root", recovery)
        self.assertIn("Files.isSymbolicLink(dir)", recovery)
        self.assertIn("recoveryAuthority.discover(source.identity)", binding)
        self.assertLess(binding.index("recoveryAuthority.discover(source.identity)"), binding.index("host.open()"))

    def test_hash_reconciliation_cleans_only_proven_original_and_blocks_unknown_current_bytes(self) -> None:
        source = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/metadata/DesktopLocalTagRecoveryAuthority.kt")
        self.assertIn("currentHash.equals(parsed.originalSha256", source)
        self.assertIn("RecordInspection.Cleaned", source)
        self.assertIn("rollbackHash.equals(parsed.originalSha256", source)
        self.assertIn("currentHash.equals(parsed.expectedResultSha256", source)
        self.assertIn("no rollback overwrite is authorized", source)
        malformed = source[source.index("val parsed = runCatching") : source.index("val parent = record.parent")]
        self.assertIn("return blocked", malformed)
        self.assertNotIn("delete", malformed.lower())

    def test_binding_fail_closes_all_native_metadata_writes_until_recovery_resolves(self) -> None:
        source = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/metadata/DesktopLocalFolderBinding.kt")
        self.assertIn("if (!dryRun && recoveryState.recoveryRequired) return blockedForRecovery()", source)
        self.assertGreaterEqual(source.count("if (confirmWrite && recoveryState.recoveryRequired) return blockedForRecovery()"), 2)
        self.assertIn("suspend fun rollbackTag", source)
        self.assertIn("recoveryState = recoveryAuthority.discover(source.identity)", source)
        self.assertNotIn("forceRollback", source)

    def test_controller_reassociates_durable_results_without_persisting_selected_root(self) -> None:
        source = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/DesktopController.kt")
        self.assertIn("syncDurableLocalRecovery(candidate)", source)
        self.assertIn("syncDurableLocalRecovery(binding)", source)
        self.assertIn("durable.recoverableResults", source)
        self.assertIn("durable.issues", source)
        self.assertIn('repository.setSetting("source", "demo")', source)
        self.assertNotIn("setSetting(\"localRoot", source)
        self.assertNotIn("rollbackFile.absolutePath", source)

    def test_restart_regressions_cover_crash_reselection_external_edit_and_verified_cleanup(self) -> None:
        recovery_test = read("desktop-app/src/test/kotlin/dev/properpcloud/desktop/metadata/DesktopLocalTagRecoveryAuthorityTest.kt")
        binding_test = read("desktop-app/src/test/kotlin/dev/properpcloud/desktop/metadata/DesktopLocalFolderBindingTest.kt")
        shared_test = read("metadata-tags/src/test/java/dev/properpcloud/metadata/tags/FolderTagApplyServiceTest.kt")
        self.assertIn("terminate after replacement then restart and reselect recovers exact guarded rollback", recovery_test)
        self.assertIn("external edit after interrupted replacement remains blocked", recovery_test)
        self.assertIn("malformed durable recovery record blocks and is never silently deleted", recovery_test)
        self.assertIn("normal verified apply disarms cross-process record", recovery_test)
        self.assertIn("reselection discovers interrupted replacement blocks writes", binding_test)
        self.assertIn("durableRecoveryAuthorityIsArmedBeforeReplaceAndDisarmedAfterVerifiedApply", shared_test)
        self.assertIn("recoveryArmFailureStopsBeforeMediaReplacement", shared_test)

    def test_transaction_files_remain_hidden_from_local_browse_and_watcher_contract(self) -> None:
        source = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/data/DesktopLocalFolderAudioSource.kt")
        watcher = read("metadata-tags/src/main/java/dev/properpcloud/metadata/tags/LocalFolderWorkbenchHost.kt")
        self.assertIn('const val TRANSACTION_FILE_PREFIX = ".properpcloud-"', source)
        self.assertIn('if (name.startsWith(".properpcloud-")) return false', watcher)

    def test_flatpak_permissions_and_fail_closed_selector_are_unchanged(self) -> None:
        package = read("scripts/package-flatpak.sh")
        selector = read("desktop-app/src/main/kotlin/dev/properpcloud/desktop/platform/LocalFolderSelector.kt")
        self.assertNotIn("--filesystem=host", package)
        self.assertNotIn("--filesystem=home", package)
        self.assertIn("--filesystem=xdg-run/properpcloud:create", package)
        self.assertIn("FLATPAK_ID", selector)
        self.assertIn("/.flatpak-info", selector)
        self.assertIn("document-portal", selector.lower())


if __name__ == "__main__":
    unittest.main()
