import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs" / "development" / "0.2.0-promotion.md"
RELEASE = ROOT / "docs" / "releases" / "0.2.0.yml"


class PromotionDocsContractTest(unittest.TestCase):
    def test_docs_name_strict_and_protected_gates(self) -> None:
        content = DOC.read_text(encoding="utf-8")
        self.assertIn("validate-020-readiness.py --strict", content)
        self.assertIn("EU and US pCloud", content)
        self.assertIn("200% high-contrast", content)
        self.assertIn("AT-SPI screen-reader", content)
        self.assertIn("must never contain passwords", content)
        self.assertTrue(RELEASE.is_file())


if __name__ == "__main__":
    unittest.main()
