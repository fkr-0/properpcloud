import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs" / "releases" / "0.2.0.yml"


class Release020ManifestTest(unittest.TestCase):
    def test_manifest_keeps_publish_boundary_and_blockers(self) -> None:
        release = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))["release"]
        self.assertEqual("0.2.0", release["version"])
        self.assertEqual("v0.2.0", release["tag"])
        self.assertIn("no version bump", release["publication_rule"])
        blockers = "\n".join(release["remaining_blockers"])
        self.assertIn("Europe", blockers)
        self.assertIn("United States", blockers)
        self.assertIn("four-hour", blockers)
        self.assertIn("screen-reader", blockers)


if __name__ == "__main__":
    unittest.main()
