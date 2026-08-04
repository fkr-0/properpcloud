import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / ".github" / "workflows" / "linux.yml").read_text(encoding="utf-8")


class ShortSoakCiArtifactTest(unittest.TestCase):
    def test_ci_uploads_machine_readable_soak_evidence(self) -> None:
        self.assertIn("build/evidence/0.2.0-resilience-soak.*", WORKFLOW)
        self.assertIn("if: always()", WORKFLOW)


if __name__ == "__main__":
    unittest.main()
