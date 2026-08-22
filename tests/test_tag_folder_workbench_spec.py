import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


class TagFolderWorkbenchSpecTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.document = yaml.safe_load(
            (ROOT / "spec" / "tag-folder-workbench.yml").read_text(encoding="utf-8")
        )["folder_tag_workbench"]
        cls.text = (ROOT / "spec" / "tag-folder-workbench.yml").read_text(encoding="utf-8")

    def test_scope_is_one_direct_folder_and_shared_core_slice(self) -> None:
        self.assertEqual("core_vertical_slice_implemented", self.document["status"])
        self.assertIn("one media-library folder", self.document["purpose"])
        self.assertIn("recursion is never implicit", self.document["terminology"]["direct_scope"])
        core = " ".join(self.document["implemented_vertical_slice"]["shared_core"])
        self.assertIn("per-file online candidates", core)
        self.assertIn("addDirectory as the sole add command", core)
        self.assertIn("ancestor-depth", core)
        self.assertIn("explicit recursive tree preview", core)

    def test_autocorrect_never_means_unattended_write(self) -> None:
        autocorrect = self.document["terminology"]["autocorrect"]
        self.assertIn("never writes media bytes", autocorrect)
        self.assertIn("unattended", self.text.lower())
        self.assertIn("explicit file and field approval", self.text)

    def test_watcher_is_gap_free_and_overflow_rescans(self) -> None:
        bootstrap = " ".join(self.document["watching"]["bootstrap_without_gap"])
        rules = " ".join(self.document["watching"]["event_model"]["rules"])
        self.assertIn("register the observer", bootstrap)
        self.assertIn("drain and coalesce", bootstrap)
        self.assertIn("overflow", rules)
        self.assertIn("full snapshot reconciliation", rules)

    def test_neutral_jvm_watcher_requires_truthful_local_root_and_never_auto_applies_tags(self) -> None:
        core = " ".join(self.document["implemented_vertical_slice"]["shared_core"])
        watcher = self.document["watching"]["current_jvm_host"]
        clients = " ".join(self.document["implemented_vertical_slice"]["clients"])
        host = (
            ROOT
            / "metadata-tags/src/main/java/dev/properpcloud/metadata/tags/LocalFolderWorkbenchHost.kt"
        ).read_text()
        build = (ROOT / "metadata-tags/build.gradle.kts").read_text()

        self.assertIn("LocalFolderRootCapability", core)
        self.assertIn("real WatchService lease registered before scan", core)
        self.assertIn("synchronously before initial metadata scan", watcher["lease"])
        self.assertIn("revoke current session reviews before debounce", watcher["immediate_invalidation"])
        self.assertIn("no newer watcher event raced", watcher["reconciliation"])
        self.assertIn("4096", watcher["storm_bound"])
        self.assertIn("at most one background reconciliation worker", watcher["worker_bound"])
        self.assertIn("native-desktop local-directory selection", watcher["client_binding"])
        self.assertIn("desktop provider/demo sources remain outside", watcher["client_binding"])
        self.assertIn("native desktop now exposes", clients)
        self.assertIn("Android prepared copies", clients)
        self.assertIn("Flatpak packaging keeps local-root selection unavailable", clients)
        self.assertIn("class LocalFolderRootCapability", host)
        self.assertIn("class LocalFolderWorkbenchHost", host)
        self.assertIn("FileSystems.getDefault().newWatchService()", host)
        self.assertIn("session.invalidateForFilesystemChange", host)
        self.assertIn("operationMutex.withLock", host)
        self.assertIn("MAX_RAW_EVENTS_PER_BATCH = 4_096", host)
        self.assertIn("reconcileWorkerScheduled.compareAndSet", host)
        self.assertNotIn("stagePatch(", host)
        self.assertIn("implementation(libs.coroutines.core)", build)

    def test_apply_is_atomic_verified_and_rollback_capable(self) -> None:
        steps = " ".join(self.document["local_apply"]["per_file_transaction"])
        failure = " ".join(self.document["local_apply"]["failure_and_rollback"])
        self.assertIn("atomic-move", steps)
        self.assertIn("reread final tags", steps)
        self.assertIn("restore the exact rollback bytes atomically", failure)
        self.assertIn("export", " ".join(self.document["invariants"]))

    def test_remote_overwrite_remains_forbidden(self) -> None:
        forbidden = " ".join(self.document["remote_sources"]["forbidden"])
        self.assertIn("expected-revision", forbidden)
        self.assertIn("check-then-overwrite", forbidden)

    def test_playlist_export_is_relative_deterministic_and_url_free(self) -> None:
        playlist = self.document["playlist_export"]
        self.assertIn(".m3u8", playlist["default_name"])
        self.assertIn("./", playlist["entries"])
        self.assertIn("natural_filename", playlist["sort_modes"])
        self.assertIn("tag_track_number", playlist["sort_modes"])
        self.assertIn("tagged_title", playlist["sort_modes"])
        self.assertIn("modification_time", playlist["sort_modes"])
        self.assertIn("EXTINF -1", playlist["duration"]["fallback"])
        self.assertIn("album", playlist["default_name"].lower())
        self.assertIn("never determines media path identity", playlist["identity_rule"])
        security = " ".join(playlist["security"]).lower()
        self.assertIn("provider stream", security)
        self.assertIn("absolute media path", security)
        self.assertIn("symbolic-link escapes", security)

    def test_playlist_batch_and_regeneration_are_explicit_bounded_and_tag_write_free(self) -> None:
        playlist = self.document["playlist_export"]
        batch = playlist["batch_generation"]
        self.assertIn("no recursive traversal", batch["direct_default"])
        self.assertIn("separate explicit opt-in", batch["recursive_opt_in"])
        self.assertIn("never opts into recursive tag writes", batch["recursive_opt_in"])
        self.assertIn("CD, Disc, Disk, or Part", batch["one_playlist_per_album"])
        self.assertIn("completed/total progress", batch["materialization"])
        regeneration = playlist["post_sync_regeneration"]
        self.assertEqual(250, regeneration["quiet_window_millis"])
        self.assertEqual(16, regeneration["pending_batch_bound"])
        self.assertEqual(256, regeneration["playlists_per_batch_bound"])
        self.assertIn("no tag toolkit or tag-apply dependency", regeneration["invariant"])
        self.assertIn("remains pending", regeneration["trigger"])

    def test_playlist_application_boundary_is_preview_first_and_stale_safe(self) -> None:
        playlist = self.document["playlist_export"]
        boundaries = playlist["application_boundaries"]
        self.assertIn("revision-bound reviews", boundaries["shared_local_workflow"])
        self.assertIn("explicit confirmation", boundaries["shared_local_workflow"])
        self.assertIn("never derives", boundaries["recursive_local_workflow"])
        self.assertIn("invalidates the current session revision", boundaries["reconciliation_session"])
        self.assertIn("fresh post-write scan", boundaries["post_tag_apply"])
        self.assertIn("verified ZIP", boundaries["android_tag_studio_export"])
        self.assertIn("genuinely own", boundaries["capability_boundary"])
        self.assertIn("stale evidence rejects", playlist["batch_generation"]["materialization"])

        workflow = (ROOT / "metadata-tags/src/main/java/dev/properpcloud/metadata/tags/FolderAutoTagWorkflow.kt").read_text()
        session = (ROOT / "metadata-tags/src/main/java/dev/properpcloud/metadata/tags/FolderMetadataSuiteSession.kt").read_text()
        regeneration = (ROOT / "metadata-tags/src/main/java/dev/properpcloud/metadata/tags/FolderPlaylistRegenerationService.kt").read_text()
        writer = (ROOT / "metadata-tags/src/main/java/dev/properpcloud/metadata/tags/FolderPlaylistWriter.kt").read_text()
        actions = (ROOT / "app/src/main/java/dev/properpcloud/app/ui/ProperpcloudApp.kt").read_text()
        activity = (ROOT / "app/src/main/java/dev/properpcloud/app/MainActivity.kt").read_text()
        editor = (ROOT / "app/src/main/java/dev/properpcloud/app/ui/MetadataEditorScreen.kt").read_text()
        view_model = (ROOT / "app/src/main/java/dev/properpcloud/app/ui/MainViewModel.kt").read_text()
        workspace = (ROOT / "app/src/main/java/dev/properpcloud/app/metadata/MetadataEditingWorkspace.kt").read_text()

        self.assertIn("fun writePlaylist(plan: FolderPlaylistPlan)", workflow)
        self.assertNotIn("fun writePlaylist(command: FolderPlaylistWriteCommand)", workflow)
        self.assertNotIn("fun applyApproved(", workflow)
        self.assertGreaterEqual(workflow.count("revalidateForApproval(file)"), 2)
        self.assertIn("expectedSha256", writer)
        self.assertIn("expectedAudioFileNames", writer)
        self.assertIn("reviewedDirectories", writer)
        self.assertIn("reviewedDirectoryEvidence", writer)
        self.assertIn("including empty directories", playlist["batch_generation"]["materialization"])
        self.assertIn("updateBatchPlaylist", actions)
        self.assertIn("updateBatchPlaylist = viewModel::updateBatchPlaylist", activity)
        self.assertIn('testTag("batch-playlist-options")', editor)
        self.assertIn('testTag("batch-playlist-review")', editor)
        self.assertIn('testTag("batch-metadata-progress")', editor)
        self.assertIn('error.userMessage("Batch metadata export failed")', view_model)
        self.assertIn("catch (error: CancellationException)", view_model)
        self.assertIn("FolderPlaylistOrder.MODIFICATION_TIME", workspace)
        self.assertIn("snapshot.durationMillis", workspace)
        self.assertIn("class FolderMetadataSuiteSession", session)
        self.assertIn("confirmWrite: Boolean", session)
        self.assertIn("recursivePlaylistOptIn", session)
        self.assertIn("recursiveTagOptIn", session)
        self.assertIn("invalidateForFilesystemChange", session)
        self.assertIn("regeneration.cancelAll()", session)
        self.assertIn("fun cancelAll(): Int", regeneration)
        self.assertNotIn("stagePatch(", regeneration)

    def test_recursive_batch_is_explicit_dry_run_first_and_progress_visible(self) -> None:
        boundary = self.document["scanning"]["recursive_tree_boundary"]
        self.assertIn("one direct folder", boundary["default"])
        self.assertIn("explicitly request recursive", boundary["opt_in"])
        batch = " ".join(self.document["local_apply"]["batch_policy"])
        self.assertIn("opt-in twice", batch)
        self.assertIn("dry-run is the default", batch)
        self.assertIn("progress reports", batch)
        self.assertIn("expected content SHA-256", batch)

    def test_hierarchy_conflicts_are_review_only(self) -> None:
        review_rules = " ".join(self.document["proposal_engine"]["review_required_rules"])
        self.assertIn("configurable ancestor depths", review_rules)
        self.assertIn("natural case-insensitive filename order", review_rules)
        invariants = " ".join(self.document["invariants"])
        self.assertIn("conflicting embedded value", invariants)
        self.assertIn("never silently replaced", invariants)


if __name__ == "__main__":
    unittest.main()
