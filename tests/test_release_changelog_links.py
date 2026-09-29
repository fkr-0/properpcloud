import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
VERSION = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
CHANGELOG = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
RELEASE = yaml.safe_load(
    (ROOT / "docs" / "releases" / f"{VERSION}.yml").read_text(encoding="utf-8")
)


class ReleaseChangelogLinksTest(unittest.TestCase):
    def test_current_candidate_links_follow_release_manifest(self) -> None:
        tag = RELEASE["tag"]
        base_release = RELEASE["base_release"]

        self.assertEqual(f"v{VERSION}", tag)
        self.assertIn(
            f"[Unreleased]: https://github.com/fkr-0/properpcloud/compare/{tag}...HEAD",
            CHANGELOG,
        )
        self.assertIn(
            f"[{VERSION}]: https://github.com/fkr-0/properpcloud/compare/"
            f"{base_release}...{tag}",
            CHANGELOG,
        )


if __name__ == "__main__":
    unittest.main()
