import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ROADMAP = (ROOT / "docs" / "roadmap.md").read_text(encoding="utf-8")
LINUX = (ROOT / "docs" / "linux-client.md").read_text(encoding="utf-8")


class LinuxDocs020Test(unittest.TestCase):
    def test_roadmap_and_client_docs_reference_canonical_promotion_gate(self) -> None:
        for content in (ROADMAP, LINUX):
            self.assertIn("0.2.0-promotion-matrix.yml", content)
            self.assertIn("validate-020-readiness.py --pre-tag", content)
            self.assertIn("--strict", content)
        self.assertIn("Ctrl+L", LINUX)
        self.assertIn("bounded capability", LINUX)


if __name__ == "__main__":
    unittest.main()
