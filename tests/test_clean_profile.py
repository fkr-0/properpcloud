from __future__ import annotations

import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class CleanProfileTest(unittest.TestCase):
    def test_command_receives_isolated_writable_xdg_profile(self) -> None:
        command = """
        set -eu
        test "$PROPERPCLOUD_CLEAN_PROFILE" = 1
        test -d "$HOME"
        test -d "$XDG_CONFIG_HOME"
        test -d "$XDG_DATA_HOME"
        test -d "$XDG_CACHE_HOME"
        test -d "$XDG_STATE_HOME"
        test -d "$TMPDIR"
        case "$HOME" in /tmp/properpcloud-clean-profile.*/*) ;; *) exit 9 ;; esac
        case "$TMPDIR" in /tmp/properpcloud-clean-profile.*/tmp) ;; *) exit 10 ;; esac
        """
        result = subprocess.run(
            [str(ROOT / "scripts" / "run-clean-profile.sh"), "sh", "-c", command],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
