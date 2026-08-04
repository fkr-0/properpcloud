import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MAKEFILE = (ROOT / "Makefile").read_text(encoding="utf-8")


class MakeTargets020Test(unittest.TestCase):
    def test_closeout_targets_remain_available(self) -> None:
        for target in (
            "release-020-readiness:",
            "release-020-pretag:",
            "release-020-readiness-strict:",
            "desktop-resilience-soak:",
            "desktop-session-audit:",
            "arch-package-gate:",
        ):
            self.assertIn(target, MAKEFILE)
        self.assertIn("write-soak-evidence.py", MAKEFILE)


if __name__ == "__main__":
    unittest.main()
