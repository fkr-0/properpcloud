from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WRAPPER = ROOT / "packaging" / "flatpak" / "mpv-host"


class FlatpakMpvHostTest(unittest.TestCase):
    def run_wrapper(self, *arguments: str) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory(prefix="properpcloud-flatpak-host-") as raw_root:
            root = Path(raw_root)
            captured = root / "captured.txt"
            runtime = root / "runtime"
            fake_spawn = root / "flatpak-spawn"
            fake_spawn.write_text(
                "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$CAPTURED\"\n",
                encoding="utf-8",
            )
            fake_spawn.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                PATH=f"{root}:{environment.get('PATH', '')}",
                CAPTURED=str(captured),
                XDG_RUNTIME_DIR=str(runtime),
            )
            resolved_arguments = [argument.replace("{runtime}", str(runtime)) for argument in arguments]
            process = subprocess.run(
                [str(WRAPPER), *resolved_arguments],
                text=True,
                capture_output=True,
                env=environment,
                check=False,
            )
            return process, captured.read_text(encoding="utf-8") if captured.exists() else ""

    def test_allows_only_the_application_mpv_contract(self) -> None:
        process, captured = self.run_wrapper(
            "--no-config",
            "--idle=yes",
            "--terminal=no",
            "--audio-display=no",
            "--force-window=no",
            "--input-ipc-server={runtime}/properpcloud/mpv.sock",
        )

        self.assertEqual(0, process.returncode, process.stderr)
        self.assertTrue(captured.startswith("--host\nmpv\n"))

    def test_rejects_host_script_injection(self) -> None:
        process, captured = self.run_wrapper(
            "--no-config",
            "--idle=yes",
            "--terminal=no",
            "--audio-display=no",
            "--force-window=no",
            "--input-ipc-server={runtime}/properpcloud/mpv.sock",
            "--script=/tmp/untrusted.lua",
        )

        self.assertEqual(64, process.returncode)
        self.assertEqual("", captured)

    def test_rejects_incomplete_deterministic_contract(self) -> None:
        process, captured = self.run_wrapper(
            "--no-config",
            "--idle=yes",
            "--input-ipc-server={runtime}/properpcloud/mpv.sock",
        )

        self.assertEqual(64, process.returncode)
        self.assertEqual("", captured)
        self.assertNotIn("untrusted.lua", process.stderr)

    def test_rejects_duplicate_private_socket_flags(self) -> None:
        process, captured = self.run_wrapper(
            "--no-config",
            "--input-ipc-server={runtime}/properpcloud/one.sock",
            "--input-ipc-server={runtime}/properpcloud/two.sock",
        )

        self.assertEqual(64, process.returncode)
        self.assertEqual("", captured)


if __name__ == "__main__":
    unittest.main()
