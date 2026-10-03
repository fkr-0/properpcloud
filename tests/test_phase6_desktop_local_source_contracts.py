import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class Phase6DesktopLocalSourceContractsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        desktop = ROOT / "desktop-app/src/main/kotlin/dev/properpcloud/desktop"
        cls.controller = (desktop / "DesktopController.kt").read_text(encoding="utf-8")
        cls.ui = (desktop / "DesktopUi.kt").read_text(encoding="utf-8")
        cls.selector = (desktop / "platform/LocalFolderSelector.kt").read_text(encoding="utf-8")
        cls.source = (desktop / "data/DesktopLocalFolderAudioSource.kt").read_text(encoding="utf-8")
        cls.binding = (desktop / "metadata/DesktopLocalFolderBinding.kt").read_text(encoding="utf-8")
        cls.host = (
            ROOT
            / "metadata-tags/src/main/java/dev/properpcloud/metadata/tags/LocalFolderWorkbenchHost.kt"
        ).read_text(encoding="utf-8")
        cls.flatpak = (ROOT / "scripts/package-flatpak.sh").read_text(encoding="utf-8")

    def test_explicit_selection_precedes_root_capability_creation(self) -> None:
        self.assertIn("LocalFolderSelection.Selected -> openSelectedLocalFolder", self.controller)
        self.assertIn("localBindingFactory(directory, recursive)", self.controller)
        self.assertIn("LocalFolderRootCapability.open(selectedRoot", self.binding)
        self.assertNotIn("LocalFolderRootCapability", self.selector)
        selected_branch = self.controller.index("LocalFolderSelection.Selected -> openSelectedLocalFolder")
        binding_create = self.controller.index("localBindingFactory(directory, recursive)")
        self.assertLess(selected_branch, binding_create)

    def test_local_identity_is_filesystem_first_opaque_and_path_redacted(self) -> None:
        self.assertIn('SourceId("local:${sha256(', self.source)
        self.assertIn('NodeId("local-${if (directory) "folder" else "track"}', self.source)
        self.assertIn("canonicalRoot.toPath().relativize", self.source)
        self.assertIn('"filesystemIdentity" to "root-relative opaque path identity"', self.source)
        self.assertNotIn('"path" to', self.source)
        self.assertNotIn("TagField", self.source)

    def test_local_browse_and_stream_keep_provider_semantics_separate(self) -> None:
        self.assertIn("FolderQueueBuilder.sortNodes(nodes)", self.source)
        self.assertIn("FolderTagScanner.SUPPORTED_EXTENSIONS", self.source)
        self.assertIn("LinkOption.NOFOLLOW_LINKS", self.source)
        self.assertIn("file.toURI().toString()", self.source)
        self.assertIn("runCatching { Files.probeContentType", self.source)
        self.assertIn("failedSource is DesktopLocalFolderAudioSource", self.controller)
        self.assertIn("local file handles are not temporary provider links", self.controller)

    def test_source_switch_and_close_close_observer_and_remove_local_queue_authority(self) -> None:
        self.assertIn("runCatching { binding.close() }", self.controller)
        self.assertIn("sources.remove(binding.source.id)", self.controller)
        self.assertIn("updateQueue(PlaybackQueue", self.controller)
        self.assertIn("detachLocalBinding()", self.controller)
        self.assertIn("override fun close() = host.close()", self.binding)

    def test_watcher_states_and_review_invalidation_are_presented(self) -> None:
        for state in ("STARTING", "SCANNING", "LIVE", "STALE", "OVERFLOW_RESCANNING", "FAILED"):
            self.assertIn(state, self.host)
        self.assertIn("hostState.name.lowercase().replace('_', '-')", self.ui)
        self.assertIn("previous.sessionRevision != hostStatus.sessionRevision", self.controller)
        self.assertIn("clearLocalReviews()", self.controller)
        self.assertIn("session.invalidateForFilesystemChange", self.host)

    def test_playlist_and_recursive_tag_confirmations_are_independent(self) -> None:
        self.assertIn("recursiveTagOptIn", self.ui)
        self.assertIn("recursivePlaylistOptIn", self.ui)
        self.assertIn("Dry run", self.ui)
        self.assertIn("Apply reviewed tags", self.ui)
        self.assertIn("Write reviewed playlist", self.ui)
        self.assertIn("confirmWrite = true", self.controller)
        self.assertIn("localTagDryRunReady", self.controller)
        self.assertIn("selected.groupBy { it.nodeId to it.field }", self.controller)
        self.assertIn("filterNot { it.nodeId == proposal.nodeId && it.field == proposal.field }", self.ui)

    def test_desktop_projects_tag_and_playlist_operation_progress(self) -> None:
        self.assertIn("operationLabel", self.controller)
        self.assertIn('projectLocalOperationProgress("Tag dry run"', self.controller)
        self.assertIn('projectLocalOperationProgress("Tag apply"', self.controller)
        self.assertIn('projectLocalOperationProgress("Playlist write"', self.controller)
        self.assertIn("onProgress: (FolderPlaylistBatchProgress) -> Unit", self.binding)
        self.assertIn("state.operationCompleted", self.ui)
        self.assertIn("state.operationTotal", self.ui)

    def test_tag_staging_uses_selected_root_without_persistent_recursive_scratch_directory(self) -> None:
        self.assertIn("val stagingDirectory: File = source.capability.rootDirectory", self.binding)
        self.assertNotIn('File(source.capability.rootDirectory, ".properpcloud-stage")', self.binding)

    def test_local_root_is_session_scoped_and_flatpak_fails_closed_without_portal(self) -> None:
        self.assertIn('repository.setSetting("source", "demo")', self.controller)
        self.assertIn('environment["FLATPAK_ID"]', self.selector)
        self.assertIn('Path.of("/.flatpak-info")', self.selector)
        self.assertIn("document-portal directory lease", self.selector)
        self.assertNotIn("--filesystem=host", self.flatpak)
        self.assertNotIn("--filesystem=home", self.flatpak)
        self.assertNotIn("--filesystem=/home", self.flatpak)
        self.assertNotIn("selectedRoot.absolutePath", self.controller)
        self.assertIn("Local folder rejected: the selected directory did not satisfy", self.controller)
        self.assertIn("localUserMessage(candidate, opened.message)", self.controller)

    def test_watcher_and_post_sync_host_have_no_tag_staging_path(self) -> None:
        self.assertNotIn("stagePatch(", self.host)
        self.assertIn("schedulePostSyncPlaylistRegeneration", self.host)
        self.assertIn("flushPostSyncPlaylistRegeneration", self.host)


if __name__ == "__main__":
    unittest.main()
