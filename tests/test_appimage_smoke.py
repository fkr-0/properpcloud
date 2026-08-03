from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERSION = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
SMOKE = ROOT / "scripts" / "appimage-smoke.sh"


class AppImageSmokeTest(unittest.TestCase):
    def fake_image(self, root: Path, *, tamper_launcher: bool = False) -> Path:
        image = root / f"properpcloud-{VERSION}-x86_64.AppImage"
        reviewed_launcher = ROOT / "packaging" / "linux" / "AppRun"
        launcher_command = (
            "printf '#!/usr/bin/env sh\\nexit 99\\n' > squashfs-root/AppRun"
            if tamper_launcher
            else f"cp '{reviewed_launcher}' squashfs-root/AppRun"
        )
        image.write_text(
            f"""#!/usr/bin/env sh
set -eu
test "${{1:-}}" = "--appimage-extract"
mkdir -p \
  squashfs-root/usr/lib/properpcloud/bin \
  squashfs-root/usr/share/applications
{launcher_command}
chmod 0755 squashfs-root/AppRun
cat > squashfs-root/usr/lib/properpcloud/bin/properpcloud <<'APP'
#!/usr/bin/env sh
set -eu
test "${{1:-}}" = "--smoke"
test "${{PROPERPCLOUD_CLEAN_PROFILE:-}}" = 1
echo fake-package-smoke-ok
APP
chmod 0755 squashfs-root/usr/lib/properpcloud/bin/properpcloud
cat > squashfs-root/usr/share/applications/dev.properpcloud.app.desktop <<'DESKTOP'
[Desktop Entry]
Name=properpcloud
X-AppImage-Version={VERSION}
DESKTOP
""",
            encoding="utf-8",
        )
        image.chmod(0o755)
        return image

    def run_smoke(self, image: Path) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["PROPERPCLOUD_CLEAN_PROFILE"] = "1"
        return subprocess.run(
            [str(SMOKE), str(image)],
            text=True,
            capture_output=True,
            env=environment,
            check=False,
        )

    def test_explicit_extract_verifies_and_executes_reviewed_launcher(self) -> None:
        with tempfile.TemporaryDirectory(prefix="properpcloud-fake-appimage-") as raw:
            result = self.run_smoke(self.fake_image(Path(raw)))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("fake-package-smoke-ok", result.stdout)

    def test_rejects_tampered_apprun(self) -> None:
        with tempfile.TemporaryDirectory(prefix="properpcloud-fake-appimage-") as raw:
            result = self.run_smoke(self.fake_image(Path(raw), tamper_launcher=True))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("differs from the reviewed launcher", result.stderr)


if __name__ == "__main__":
    unittest.main()
