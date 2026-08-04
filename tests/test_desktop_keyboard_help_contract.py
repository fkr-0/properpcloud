import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
UI = (ROOT / "desktop-app" / "src" / "main" / "kotlin" / "dev" / "properpcloud" / "desktop" / "DesktopUi.kt").read_text(encoding="utf-8")


class DesktopKeyboardHelpContractTest(unittest.TestCase):
    def test_visible_help_and_focus_indicators_exist(self) -> None:
        self.assertIn("Keyboard controls", UI)
        self.assertIn("Keyboard focus", UI)
        self.assertIn("All queue operations have non-drag alternatives", UI)
        self.assertIn("accountDialog || keyboardHelp", UI)


if __name__ == "__main__":
    unittest.main()
