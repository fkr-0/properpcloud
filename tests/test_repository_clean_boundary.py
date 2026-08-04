import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAKEFILE = (ROOT / "Makefile").read_text(encoding="utf-8")


class RepositoryBoundaryTest(unittest.TestCase):
    def test_generated_evidence_is_routed_under_ignored_build_tree(self) -> None:
        session = (ROOT / "scripts" / "desktop-session-audit.py").read_text(encoding="utf-8")
        arch = (ROOT / "scripts" / "arch-package-gate.sh").read_text(encoding="utf-8")
        soak = (ROOT / "scripts" / "write-soak-evidence.py").read_text(encoding="utf-8")
        self.assertIn("build/evidence", session)
        self.assertIn("build/evidence", arch)
        self.assertIn('parser.add_argument("--output"', soak)
        self.assertIn("--output build/evidence/0.2.0-resilience-soak.json", MAKEFILE)


if __name__ == "__main__":
    unittest.main()
