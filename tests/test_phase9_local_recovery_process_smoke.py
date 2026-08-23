import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class Phase9LocalRecoveryProcessSmokeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.main = (
            ROOT / "desktop-app/src/main/kotlin/dev/properpcloud/desktop/Main.kt"
        ).read_text(encoding="utf-8")
        cls.smoke = (
            ROOT
            / "desktop-app/src/main/kotlin/dev/properpcloud/desktop/DesktopLocalTagRecoveryProcessSmoke.kt"
        ).read_text(encoding="utf-8")
        cls.script = (ROOT / "scripts/desktop-local-tag-recovery-process-smoke.sh").read_text(
            encoding="utf-8"
        )
        cls.makefile = (ROOT / "Makefile").read_text(encoding="utf-8")

    def test_packaged_entry_points_are_explicit_and_take_a_selected_root(self) -> None:
        self.assertIn('--local-tag-recovery-kill-smoke', self.main)
        self.assertIn('--local-tag-recovery-restart-smoke', self.main)
        self.assertIn('argumentValue(args, "--local-tag-recovery-kill-smoke")', self.main)
        self.assertIn('argumentValue(args, "--local-tag-recovery-restart-smoke")', self.main)

    def test_first_process_blocks_after_real_atomic_replacement_until_external_kill(self) -> None:
        self.assertIn("StandardCopyOption.ATOMIC_MOVE", self.smoke)
        self.assertIn("replacement-complete; awaiting external kill", self.smoke)
        self.assertIn("while (true) Thread.sleep", self.smoke)
        self.assertNotIn("Runtime.getRuntime().halt", self.smoke)

    def test_shell_harness_uses_real_sigkill_and_requires_fresh_process_success(self) -> None:
        self.assertIn('kill -KILL "$first_pid"', self.script)
        self.assertIn('expected externally killed packaged process exit 137', self.script)
        self.assertIn('--local-tag-recovery-restart-smoke "$root"', self.script)
        self.assertIn('guarded_exact_hash_rollback_verified', self.script)

    def test_restart_uses_normal_selected_root_binding_and_guarded_rollback(self) -> None:
        self.assertIn("DesktopLocalFolderBinding.createSelected", self.smoke)
        self.assertIn("binding.recoveryState.recoveryRequired", self.smoke)
        self.assertIn("binding.rollbackTag(recovered)", self.smoke)
        self.assertIn("ApplyResultStatus.VERIFIED", self.smoke)

    def test_evidence_is_redacted_and_does_not_claim_physical_power_cut(self) -> None:
        self.assertIn('"selected_root_path_recorded": False', self.script)
        self.assertIn('"media_path_recorded": False', self.script)
        self.assertIn('"provider_url_recorded": False', self.script)
        self.assertIn('"credential_recorded": False', self.script)
        self.assertIn('"physical_power_cut_exercised": False', self.script)
        self.assertNotIn('"selected_root":', self.script)

    def test_linux_ci_runs_the_process_boundary_smoke(self) -> None:
        self.assertIn("desktop-local-tag-recovery-process-smoke: desktop-package", self.makefile)
        linux_ci = self.makefile.split("linux-ci:", 1)[1].split("\n", 1)[0]
        self.assertIn("desktop-local-tag-recovery-process-smoke", linux_ci)


if __name__ == "__main__":
    unittest.main()
