import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TEXT = (ROOT / "docs" / "reviews" / "0.2.0-promotion-matrix.yml").read_text(encoding="utf-8")


class ManualGateLabelsTest(unittest.TestCase):
    def test_manual_and_protected_gates_are_not_marked_passed(self) -> None:
        self.assertIn("screen_reader_review:\n      status: pending_manual", TEXT)
        self.assertIn("physical_media_keys:\n      status: pending_manual", TEXT)
        self.assertIn("physical_suspend_resume:\n      status: pending_manual", TEXT)
        self.assertIn("protected_provider_accounts:\n      status: blocked_maintainer_credentials", TEXT)


if __name__ == "__main__":
    unittest.main()
