import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
MATRIX = yaml.safe_load((ROOT / "docs" / "reviews" / "0.2.0-promotion-matrix.yml").read_text(encoding="utf-8"))


class MatrixStatusPrefixesTest(unittest.TestCase):
    def test_every_status_is_passed_pending_blocked_or_selected_boundary(self) -> None:
        statuses = []

        def walk(value):
            if isinstance(value, dict):
                for key, child in value.items():
                    if key == "status":
                        statuses.append(child)
                    else:
                        walk(child)
            elif isinstance(value, list):
                for child in value:
                    walk(child)

        walk(MATRIX["promotion"]["gates"])
        self.assertTrue(statuses)
        for status in statuses:
            self.assertTrue(status.startswith(("passed", "pending", "blocked", "fallback_boundary_selected")), status)


if __name__ == "__main__":
    unittest.main()
