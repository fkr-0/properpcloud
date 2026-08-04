import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class NoPrivateEvidenceContentTest(unittest.TestCase):
    def test_tracked_promotion_files_contain_no_generated_secret_values(self) -> None:
        paths = [
            ROOT / "docs" / "reviews" / "0.2.0-promotion-matrix.yml",
            ROOT / "docs" / "releases" / "0.2.0.yml",
            ROOT / "docs" / "development" / "0.2.0-promotion.md",
        ]
        joined = "\n".join(path.read_text(encoding="utf-8") for path in paths)
        self.assertNotIn("DBUS_SESSION_BUS_ADDRESS=", joined)
        self.assertNotIn("pcloud-session", joined)
        self.assertNotIn("auth=", joined)


if __name__ == "__main__":
    unittest.main()
