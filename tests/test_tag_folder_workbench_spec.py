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

    def test_scope_is_one_direct_folder_and_spec_only(self) -> None:
        self.assertEqual("designed_not_implemented", self.document["status"])
        self.assertIn("one media-library folder", self.document["purpose"])
        self.assertIn("recursion is never implicit", self.document["terminology"]["direct_scope"])

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

    def test_apply_is_atomic_verified_and_rollback_capable(self) -> None:
        steps = " ".join(self.document["local_apply"]["per_file_transaction"])
        failure = " ".join(self.document["local_apply"]["failure_and_rollback"])
        self.assertIn("atomic-move", steps)
        self.assertIn("reread final tags", steps)
        self.assertIn("restore the exact rollback bytes atomically", failure)
        self.assertIn("export", self.document["invariants"][-1])

    def test_remote_overwrite_remains_forbidden(self) -> None:
        forbidden = " ".join(self.document["remote_sources"]["forbidden"])
        self.assertIn("expected-revision", forbidden)
        self.assertIn("check-then-overwrite", forbidden)


if __name__ == "__main__":
    unittest.main()
