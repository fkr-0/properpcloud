import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "docs" / "reviews" / "0.2.0-promotion-matrix.yml"


class PromotionMatrixContractTest(unittest.TestCase):
    def test_required_gate_categories_remain_explicit(self) -> None:
        promotion = yaml.safe_load(MATRIX.read_text(encoding="utf-8"))["promotion"]
        gates = promotion["gates"]
        self.assertEqual("0.2.0", promotion["version"])
        for required in (
            "shared_semantics",
            "desktop_runtime",
            "keyboard_implementation",
            "accessibility_scale_contrast",
            "screen_reader_review",
            "packages",
            "current_i3_session",
            "locked_keyring",
            "mpris_control_path",
            "physical_media_keys",
            "suspend_resume_implementation",
            "physical_suspend_resume",
            "bounded_demo_soak",
            "protected_provider_soak",
            "protected_provider_accounts",
            "oauth_decision",
        ):
            self.assertIn(required, gates)
        self.assertIn("strict", promotion["strict_promotion_rule"])


if __name__ == "__main__":
    unittest.main()
