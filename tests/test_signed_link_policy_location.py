import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SHARED = ROOT / "core-model" / "src" / "main" / "java" / "dev" / "properpcloud" / "core" / "model" / "SignedLinkRetryGate.kt"
ANDROID = ROOT / "app" / "src" / "main" / "java" / "dev" / "properpcloud" / "app" / "playback" / "PlaybackService.kt"
DESKTOP = ROOT / "desktop-app" / "src" / "main" / "kotlin" / "dev" / "properpcloud" / "desktop" / "DesktopController.kt"


class SignedLinkPolicyLocationTest(unittest.TestCase):
    def test_android_and_linux_use_shared_policy(self) -> None:
        self.assertTrue(SHARED.is_file())
        shared_import = "dev.properpcloud.core.model.SignedLinkRetryGate"
        self.assertIn(shared_import, ANDROID.read_text(encoding="utf-8"))
        self.assertIn(shared_import, DESKTOP.read_text(encoding="utf-8"))
        self.assertFalse((ANDROID.parent / "SignedLinkRetryGate.kt").exists())


if __name__ == "__main__":
    unittest.main()
