import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TEXT = (ROOT / "docs" / "reviews" / "0.2.0-promotion-matrix.yml").read_text(encoding="utf-8")


class CurrentSessionStatusTest(unittest.TestCase):
    def test_current_i3_automation_is_passed_without_overclaiming_manual_steps(self) -> None:
        self.assertIn("current_i3_session:\n      status: passed_current_session", TEXT)
        self.assertIn("locked_keyring:\n      status: passed_current_host", TEXT)
        self.assertIn("mpris_control_path:\n      status: passed_current_session", TEXT)
        self.assertIn("suspend_resume_implementation:\n      status: passed_current_host", TEXT)
        self.assertIn("physical_media_keys:\n      status: pending_manual", TEXT)
        self.assertIn("physical_suspend_resume:\n      status: pending_manual", TEXT)
        self.assertIn("alternate_desktops:\n      status: pending_external_session", TEXT)


if __name__ == "__main__":
    unittest.main()
