from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from importlib.util import module_from_spec, spec_from_file_location  # noqa: E402


spec = spec_from_file_location("render_arch_pkgbuild", ROOT / "scripts" / "render-arch-pkgbuild.py")
assert spec and spec.loader
module = module_from_spec(spec)
spec.loader.exec_module(module)


class RenderArchPkgbuildTest(unittest.TestCase):
    def test_renders_pinned_https_source_without_skip(self) -> None:
        template = (ROOT / "packaging" / "arch" / "PKGBUILD.in").read_text(encoding="utf-8")
        rendered = module.render(template, "0.1.9", "https://example.invalid/properpcloud.tar.gz", "a" * 64)
        self.assertIn("pkgver=0.1.9", rendered)
        self.assertIn("sha256sums=('" + "a" * 64 + "')", rendered)
        self.assertNotIn("SKIP", rendered)
        self.assertNotIn("@VERSION@", rendered)

    def test_rejects_insecure_source(self) -> None:
        template = (ROOT / "packaging" / "arch" / "PKGBUILD.in").read_text(encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            module.render(template, "0.1.9", "http://example.invalid/source.tar.gz", "a" * 64)


if __name__ == "__main__":
    unittest.main()
