import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOAK = ROOT / "desktop-app" / "src" / "main" / "kotlin" / "dev" / "properpcloud" / "desktop" / "ResilienceSoak.kt"
MAIN = ROOT / "desktop-app" / "src" / "main" / "kotlin" / "dev" / "properpcloud" / "desktop" / "Main.kt"


class ResilienceSoakContractTest(unittest.TestCase):
    def test_soak_keeps_release_critical_assertions(self) -> None:
        content = SOAK.read_text(encoding="utf-8")
        self.assertIn("terminateProcessForSmoke", content)
        self.assertIn("selected queue identity", content)
        self.assertIn("five-second drift", content)
        self.assertIn("192 MiB", content)
        self.assertIn("PROPERPCLOUD_SOAK_SECONDS", content)

    def test_main_exposes_explicit_noninteractive_mode(self) -> None:
        self.assertIn("--resilience-soak", MAIN.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
