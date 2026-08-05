import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "linux.yml"


class LinuxWorkflowPromotionTest(unittest.TestCase):
    def test_linux_ci_runs_bounded_soak_and_hardened_package_smokes(self) -> None:
        content = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("make release-020-readiness", content)
        self.assertIn("make desktop-locked-keyring-smoke", content)
        self.assertIn("make desktop-accessibility-audit", content)
        self.assertIn("PROPERPCLOUD_SOAK_SECONDS=15 make desktop-resilience-soak", content)
        self.assertIn("gnome-keyring", content)
        self.assertIn("xvfb", content)
        self.assertIn("imagemagick", content)
        self.assertIn("make desktop-appimage-smoke PREBUILT_DESKTOP_IMAGE=1", content)
        self.assertIn("make desktop-flatpak-smoke PREBUILT_DESKTOP_IMAGE=1", content)
        self.assertNotIn('APPIMAGE_EXTRACT_AND_RUN=1 "$appimage" --smoke', content)
        self.assertNotIn("flatpak install --user --noninteractive --reinstall", content)


if __name__ == "__main__":
    unittest.main()
