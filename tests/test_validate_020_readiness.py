import json
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "validate-020-readiness.py"


class Validate020ReadinessTest(unittest.TestCase):
    def run_matrix(self, content: str, *extra: str) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            matrix = Path(directory) / "matrix.yml"
            matrix.write_text(textwrap.dedent(content), encoding="utf-8")
            return subprocess.run(
                ["python3", str(SCRIPT), "--matrix", str(matrix), *extra],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )

    def test_non_strict_summary_keeps_blockers_explicit(self) -> None:
        result = self.run_matrix(
            """
            promotion:
              version: 0.2.0
              gates:
                automated: {status: passed_automated}
                provider: {status: blocked_maintainer_credentials}
            """,
            "--json",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        payload = json.loads(result.stdout)
        self.assertFalse(payload["strict_ready"])
        self.assertEqual("provider", payload["pending"][0]["gate"])

    def test_strict_mode_fails_until_every_gate_passes(self) -> None:
        blocked = self.run_matrix(
            """
            promotion:
              version: 0.2.0
              gates:
                provider: {status: pending_execution}
            """,
            "--strict",
        )
        self.assertEqual(1, blocked.returncode)

        ready = self.run_matrix(
            """
            promotion:
              version: 0.2.0
              gates:
                provider: {status: passed_protected}
                visual_exception: {status: accepted_documented_limitation}
                oauth: {status: fallback_boundary_selected}
            """,
            "--strict",
        )
        self.assertEqual(0, ready.returncode, ready.stderr)

    def test_pre_tag_mode_allows_only_explicit_post_tag_gate(self) -> None:
        post_tag_only = self.run_matrix(
            """
            promotion:
              version: 0.2.0
              gates:
                archive: {status: pending_post_tag}
                runtime: {status: passed_automated}
            """,
            "--pre-tag",
        )
        self.assertEqual(0, post_tag_only.returncode, post_tag_only.stderr)

        manual_blocker = self.run_matrix(
            """
            promotion:
              version: 0.2.0
              gates:
                archive: {status: pending_post_tag}
                accessibility: {status: pending_manual}
            """,
            "--pre-tag",
        )
        self.assertEqual(1, manual_blocker.returncode)

    def test_unknown_status_fails_closed(self) -> None:
        result = self.run_matrix(
            """
            promotion:
              version: 0.2.0
              gates:
                mystery: {status: probably_fine}
            """,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unsupported statuses", result.stderr)


if __name__ == "__main__":
    unittest.main()
