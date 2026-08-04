import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHANGELOG = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")


class Changelog020CloseoutTest(unittest.TestCase):
    def test_unreleased_records_runtime_accessibility_and_evidence_changes(self) -> None:
        unreleased = CHANGELOG.split("## [0.1.9]", 1)[0]
        self.assertIn("Shared Android/Linux signed-link retry policy", unreleased)
        self.assertIn("Keyboard-first library and queue focus", unreleased)
        self.assertIn("current-session Secret Service/MPRIS evidence", unreleased)
        self.assertIn("protected-provider", unreleased)


if __name__ == "__main__":
    unittest.main()
