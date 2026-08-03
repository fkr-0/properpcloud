from __future__ import annotations

import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SMOKE = ROOT / "scripts" / "flatpak-smoke.sh"


class FlatpakSmokeTest(unittest.TestCase):
    def test_retries_playback_property_after_identity_is_available(self) -> None:
        with tempfile.TemporaryDirectory(prefix="properpcloud-flatpak-smoke-") as raw:
            work = Path(raw)
            bin_dir = work / "bin"
            bin_dir.mkdir()
            counter = work / "playback-attempts"

            flatpak = bin_dir / "flatpak"
            flatpak.write_text(
                textwrap.dedent(
                    """\
                    #!/usr/bin/env bash
                    set -euo pipefail
                    if [[ " $* " == *" --mpris-smoke "* ]]; then
                      echo "fake MPRIS smoke ready"
                      sleep 10
                      exit 0
                    fi
                    if [[ " $* " == *" --command=sh "* ]]; then
                      exit 0
                    fi
                    exit 2
                    """
                ),
                encoding="utf-8",
            )
            flatpak.chmod(0o755)

            gdbus = bin_dir / "gdbus"
            gdbus.write_text(
                textwrap.dedent(
                    f"""\
                    #!/usr/bin/env bash
                    set -euo pipefail
                    if [[ " $* " == *" org.mpris.MediaPlayer2 Identity "* ]]; then
                      echo "(<'properpcloud'>,)"
                      exit 0
                    fi
                    if [[ " $* " == *" org.mpris.MediaPlayer2.Player PlaybackStatus "* ]]; then
                      attempts=0
                      [[ -f {counter!s} ]] && attempts=$(cat {counter!s})
                      attempts=$((attempts + 1))
                      printf '%s' "$attempts" > {counter!s}
                      if [[ "$attempts" -lt 3 ]]; then
                        echo "ServiceUnknown" >&2
                        exit 1
                      fi
                      echo "(<'Paused'>,)"
                      exit 0
                    fi
                    exit 2
                    """
                ),
                encoding="utf-8",
            )
            gdbus.chmod(0o755)

            environment = os.environ.copy()
            environment["PATH"] = f"{bin_dir}:{environment['PATH']}"
            environment["XDG_RUNTIME_DIR"] = str(work / "runtime")
            result = subprocess.run(
                [str(SMOKE), "dev.properpcloud.app"],
                text=True,
                capture_output=True,
                env=environment,
                timeout=15,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            self.assertEqual("3", counter.read_text(encoding="utf-8"))
            self.assertIn("properpcloud Flatpak smoke: OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
