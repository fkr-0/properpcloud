import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "validate-020-readiness.py"


class ReadinessStrictContractTest(unittest.TestCase):
    def test_repository_matrix_is_valid_but_not_yet_strict_ready(self) -> None:
        summary = subprocess.run(["python3", str(SCRIPT)], cwd=ROOT, text=True, capture_output=True)
        strict = subprocess.run(["python3", str(SCRIPT), "--strict"], cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(0, summary.returncode, summary.stderr)
        self.assertEqual(1, strict.returncode)
        self.assertIn("protected_provider_accounts", strict.stdout)


if __name__ == "__main__":
    unittest.main()
