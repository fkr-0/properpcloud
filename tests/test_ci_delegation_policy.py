import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


class CiDelegationPolicyTest(unittest.TestCase):
    def test_local_check_stays_host_only(self) -> None:
        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        self.assertIn(
            "local-check: oauth-config-test oauth-config-check",
            makefile,
        )
        local_section = makefile.split("local-check:", 1)[1].split("\ntest:", 1)[0]
        self.assertNotIn("docker", local_section.lower())
        self.assertNotIn("assemble", local_section.lower())

    def test_workspace_default_test_uses_the_cheap_gate(self) -> None:
        bridge = yaml.safe_load((ROOT / "bridge.yml").read_text(encoding="utf-8"))
        self.assertEqual(["make", "local-check"], bridge["commands"]["test"]["run"])
        self.assertEqual(["make", "ci"], bridge["commands"]["ci"]["run"])

    def test_github_android_workflow_owns_full_verification(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "android.yml").read_text(encoding="utf-8")
        self.assertIn("pull_request:", workflow)
        self.assertIn("run: make ci", workflow)
        self.assertIn("app/build/outputs/apk/debug/app-debug.apk", workflow)

    def test_release_workflow_marks_semver_prereleases_and_never_makes_them_latest(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
        self.assertIn("prerelease: ${{ contains(env.RELEASE_TAG, '-') }}", workflow)
        self.assertIn(
            "make_latest: ${{ contains(env.RELEASE_TAG, '-') && 'false' || 'true' }}",
            workflow,
        )

    def test_publishable_android_artifact_requires_stable_external_signing(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
        docker_run = (ROOT / "scripts" / "docker-run.sh").read_text(encoding="utf-8")
        app_gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        prepare_release = (ROOT / "scripts" / "prepare-release.py").read_text(encoding="utf-8")

        for secret in (
            "ANDROID_RELEASE_KEYSTORE_BASE64",
            "ANDROID_RELEASE_KEYSTORE_PASSWORD",
            "ANDROID_RELEASE_KEY_ALIAS",
            "ANDROID_RELEASE_KEY_PASSWORD",
        ):
            self.assertIn(f"secrets.{secret}", workflow)
        self.assertIn('PROPERPCLOUD_REQUIRE_STABLE_SIGNING: "true"', workflow)
        self.assertIn("base64 --decode", workflow)
        self.assertIn('rm -f "$PROPERPCLOUD_ANDROID_KEYSTORE_PATH"', workflow)

        for variable in (
            "PROPERPCLOUD_REQUIRE_STABLE_SIGNING",
            "PROPERPCLOUD_ANDROID_KEYSTORE_PATH",
            "PROPERPCLOUD_ANDROID_KEYSTORE_PASSWORD",
            "PROPERPCLOUD_ANDROID_KEY_ALIAS",
            "PROPERPCLOUD_ANDROID_KEY_PASSWORD",
        ):
            self.assertIn(variable, docker_run)
            self.assertIn(variable, app_gradle if variable != "PROPERPCLOUD_ANDROID_KEYSTORE_PASSWORD" else app_gradle)
        self.assertIn("publishable Android artifacts require", prepare_release)
        self.assertIn("stable Android signing configuration is incomplete", prepare_release)


if __name__ == "__main__":
    unittest.main()
