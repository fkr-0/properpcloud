import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class EvidencePathsIgnoredTest(unittest.TestCase):
    def test_generated_evidence_and_arch_packages_are_ignored(self) -> None:
        for path in (
            "build/evidence/0.2.0-current-session.json",
            "build/evidence/0.2.0-resilience-soak.json",
            "build/arch-gate/0.1.9/example.pkg.tar.zst",
        ):
            result = subprocess.run(["git", "check-ignore", "-q", path], cwd=ROOT)
            self.assertEqual(0, result.returncode, path)


if __name__ == "__main__":
    unittest.main()
