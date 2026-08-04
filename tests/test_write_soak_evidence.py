import json
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "write-soak-evidence.py"


class WriteSoakEvidenceTest(unittest.TestCase):
    def test_fixed_summary_becomes_redacted_json(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "soak.log"
            output = Path(directory) / "soak.json"
            log.write_text(
                "properpcloud resilience soak: OK (seconds=15 cycles=40 forced_exits=1 max_drift_ms=500 memory_growth_bytes=1024)\n",
                encoding="utf-8",
            )
            result = subprocess.run(
                ["python3", str(SCRIPT), "--log", str(log), "--output", str(output)],
                cwd=ROOT,
                text=True,
                capture_output=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            payload = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(0, payload["automatic_process_restarts"])
            self.assertEqual("not_exercised", payload["protected_provider_capability_expiry"])

    def test_missing_or_out_of_bound_summary_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "soak.log"
            output = Path(directory) / "soak.json"
            log.write_text(
                "properpcloud resilience soak: OK (seconds=15 cycles=2 forced_exits=0 max_drift_ms=6000 memory_growth_bytes=0)\n",
                encoding="utf-8",
            )
            result = subprocess.run(
                ["python3", str(SCRIPT), "--log", str(log), "--output", str(output)],
                cwd=ROOT,
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main()
