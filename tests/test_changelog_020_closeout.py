import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHANGELOG = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")


class Changelog020CloseoutTest(unittest.TestCase):
    def test_0_1_10_records_runtime_accessibility_and_evidence_changes(self) -> None:
        release = CHANGELOG.split("## [0.1.10]", 1)[1].split("## [0.1.9]", 1)[0]
        self.assertIn("Shared Android/Linux signed-link retry policy", release)
        self.assertIn("Keyboard-first library and queue focus", release)
        self.assertIn("current-session Secret Service/MPRIS evidence", release)
        self.assertIn("folder-scoped Tag workbench specification", release)

    def test_unreleased_keeps_only_future_work(self) -> None:
        unreleased = CHANGELOG.split("## [0.1.10]", 1)[0]
        self.assertIn("Implement the specified folder-scoped Tag workbench", unreleased)
        self.assertIn("0.2.0", unreleased)


if __name__ == "__main__":
    unittest.main()
