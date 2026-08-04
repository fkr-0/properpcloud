import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = (ROOT / "desktop-app" / "src" / "main" / "kotlin" / "dev" / "properpcloud" / "desktop" / "DesktopController.kt").read_text(encoding="utf-8")
MPV = (ROOT / "desktop-app" / "src" / "main" / "kotlin" / "dev" / "properpcloud" / "desktop" / "playback" / "MpvController.kt").read_text(encoding="utf-8")


class StreamRefreshContractTest(unittest.TestCase):
    def test_refresh_uses_stable_identity_and_fixed_status(self) -> None:
        self.assertIn("streamRetryGate.acquire(mediaId", CONTROLLER)
        self.assertIn("currentIdentity != mediaId", CONTROLLER)
        self.assertIn("Refreshing the temporary stream link", CONTROLLER)
        self.assertNotIn("refreshed.url", CONTROLLER)
        self.assertIn('"mpv playback failed"', MPV)
        self.assertIn("eof-reached", MPV)
        self.assertIn("expectedIdle", MPV)


if __name__ == "__main__":
    unittest.main()
