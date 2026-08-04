import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "arch-package-gate.sh"
TEMPLATE = ROOT / "packaging" / "arch" / "PKGBUILD.in"


class ArchPackageGateTest(unittest.TestCase):
    def test_shell_gate_has_valid_syntax(self) -> None:
        result = subprocess.run(["bash", "-n", str(SCRIPT)], text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_recipe_keeps_immutable_source_and_real_checksum_contract(self) -> None:
        content = TEMPLATE.read_text(encoding="utf-8")
        self.assertIn("@SOURCE_URL@", content)
        self.assertIn("@SOURCE_SHA256@", content)
        self.assertNotIn("SKIP", content)
        self.assertIn(":desktop-app:createDistributable", content)
        self.assertIn("dev.properpcloud.app.png", content)
        self.assertNotIn("dev.properpcloud.app.svg", content)
        self.assertIn("THIRD_PARTY_NOTICES.md", content)


if __name__ == "__main__":
    unittest.main()
