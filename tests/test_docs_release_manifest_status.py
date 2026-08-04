import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TEXT = (ROOT / "docs" / "releases" / "0.2.0.yml").read_text(encoding="utf-8")


class DocsReleaseManifestStatusTest(unittest.TestCase):
    def test_candidate_status_does_not_claim_release(self) -> None:
        self.assertIn("implementation_hardened_promotion_gates_explicit", TEXT)
        self.assertIn("no version bump or tag until pre-tag readiness passes", TEXT)
        self.assertIn("no publication until strict post-tag readiness passes", TEXT)


if __name__ == "__main__":
    unittest.main()
