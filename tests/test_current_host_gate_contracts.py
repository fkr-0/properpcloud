import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class CurrentHostGateContractsTest(unittest.TestCase):
    def test_locked_keyring_gate_is_isolated_bounded_and_redacted(self) -> None:
        script = (ROOT / "scripts" / "desktop-locked-keyring-smoke.sh").read_text(encoding="utf-8")
        vault = (ROOT / "desktop-app" / "src" / "main" / "kotlin" / "dev" / "properpcloud" / "desktop" / "security" / "SecretServiceVault.kt").read_text(encoding="utf-8")
        self.assertIn("mktemp -d", script)
        self.assertIn("org.freedesktop.Secret.Service.Lock", script)
        self.assertIn("secrets.token_urlsafe(32)", script)
        self.assertIn("cleanup_keyring", script)
        self.assertIn('"real_user_keyring_touched": False', script)
        self.assertIn("lookupTimeoutSeconds", vault)
        self.assertEqual(0, subprocess.run(["bash", "-n", str(ROOT / "scripts" / "desktop-locked-keyring-smoke.sh")]).returncode)

    def test_accessibility_gate_uses_exact_scale_contrast_and_nonblank_checks(self) -> None:
        script = (ROOT / "scripts" / "desktop-accessibility-audit.sh").read_text(encoding="utf-8")
        ui = (ROOT / "desktop-app" / "src" / "main" / "kotlin" / "dev" / "properpcloud" / "desktop" / "DesktopUi.kt").read_text(encoding="utf-8")
        self.assertIn("-Dsun.java2d.uiScale=2", script)
        self.assertIn("PROPERPCLOUD_HIGH_CONTRAST=1", script)
        self.assertIn('(width, height) != (1280, 820)', script)
        self.assertIn("normalized_mean_luminance", script)
        self.assertIn("stateDescription", ui)
        self.assertIn('Text("Selected"', ui)
        self.assertIn('"Current".takeIf', ui)
        self.assertEqual(0, subprocess.run(["bash", "-n", str(ROOT / "scripts" / "desktop-accessibility-audit.sh")]).returncode)

    def test_logind_sleep_policy_and_flatpak_permission_remain_explicit(self) -> None:
        monitor = (ROOT / "desktop-app" / "src" / "main" / "kotlin" / "dev" / "properpcloud" / "desktop" / "platform" / "LogindSleepMonitor.kt").read_text(encoding="utf-8")
        controller = (ROOT / "desktop-app" / "src" / "main" / "kotlin" / "dev" / "properpcloud" / "desktop" / "DesktopController.kt").read_text(encoding="utf-8")
        flatpak = (ROOT / "scripts" / "package-flatpak.sh").read_text(encoding="utf-8")
        self.assertIn("PrepareForSleep", monitor)
        self.assertIn('getDBusOwnerName("org.freedesktop.login1")', monitor)
        self.assertIn('signal.path == "/org/freedesktop/login1"', monitor)
        self.assertIn("forceCheckpoint = true", monitor)
        self.assertIn("refreshAndResume = resume", monitor)
        self.assertIn("playCurrent()", controller)
        self.assertIn("runBlocking { mpv.pause(true) }", controller)
        self.assertIn("pCloudRestoreGeneration", controller)
        self.assertIn("cancelPCloudRestore()", controller)
        self.assertIn("--system-talk-name=org.freedesktop.login1", flatpak)

    def test_mpris_smoke_invokes_external_control_methods(self) -> None:
        script = (ROOT / "scripts" / "desktop-mpris-smoke.sh").read_text(encoding="utf-8")
        verification = (ROOT / "desktop-app" / "src" / "main" / "kotlin" / "dev" / "properpcloud" / "desktop" / "DesktopVerification.kt").read_text(encoding="utf-8")
        for method in ("PlayPause", "Play", "Pause", "Stop", "Next", "Previous", "Seek", "SetPosition"):
            self.assertIn(method, script)
        self.assertIn('"raise"', verification)
        self.assertIn('"position:12000"', verification)


if __name__ == "__main__":
    unittest.main()
