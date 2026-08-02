#!/usr/bin/env python3
"""Validate properpcloud's release metadata and version agreement."""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys
from pathlib import Path


SEMVER = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
PINNED_ACTION = re.compile(r"^[0-9a-f]{40}$")
USES_ACTION = re.compile(r"^\s*uses:\s*([^\s#]+)", re.MULTILINE)


def fail(message: str) -> None:
    print(f"release error: {message}", file=sys.stderr)


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(
        ["git", *args], cwd=root, text=True, stderr=subprocess.STDOUT
    ).strip()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expect-tag", action="store_true")
    parser.add_argument("--require-clean", action="store_true")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    errors: list[str] = []

    version_path = root / "VERSION"
    changelog_path = root / "CHANGELOG.md"
    license_path = root / "LICENSE"
    notices_path = root / "THIRD_PARTY_NOTICES.md"
    wrapper_path = root / "gradle" / "wrapper" / "gradle-wrapper.jar"
    wrapper_checksum_path = root / "gradle" / "wrapper" / "gradle-wrapper.jar.sha256"

    version = version_path.read_text(encoding="utf-8").strip()
    match = SEMVER.fullmatch(version)
    if match is None:
        errors.append(f"VERSION is not Semantic Versioning 2.0.0: {version!r}")
    else:
        major, minor, patch = (int(match.group(index)) for index in range(1, 4))
        version_code = major * 1_000_000 + minor * 1_000 + patch
        if not 1 <= version_code <= 2_100_000_000:
            errors.append(f"derived Android versionCode is invalid: {version_code}")

    changelog = changelog_path.read_text(encoding="utf-8")
    if f"## [{version}] - " not in changelog:
        errors.append(f"CHANGELOG.md has no dated [{version}] release section")
    if "## [Unreleased]" not in changelog:
        errors.append("CHANGELOG.md has no [Unreleased] section")

    if "MIT License" not in license_path.read_text(encoding="utf-8"):
        errors.append("LICENSE is not the selected MIT license")
    if "Apache License 2.0" not in notices_path.read_text(encoding="utf-8"):
        errors.append("third-party notices do not mention Apache License 2.0")

    if not wrapper_checksum_path.is_file():
        errors.append("Gradle wrapper checksum file is missing")
    elif not wrapper_path.is_file():
        errors.append("Gradle wrapper JAR is missing")
    else:
        checksum_fields = wrapper_checksum_path.read_text(encoding="utf-8").strip().split()
        expected_wrapper_checksum = checksum_fields[0] if checksum_fields else ""
        if not re.fullmatch(r"[0-9a-f]{64}", expected_wrapper_checksum):
            errors.append("Gradle wrapper checksum file is malformed")
        elif sha256(wrapper_path) != expected_wrapper_checksum:
            errors.append("Gradle wrapper JAR checksum does not match reviewed value")

    workflows_dir = root / ".github" / "workflows"
    for workflow_path in sorted(workflows_dir.glob("*.yml")):
        workflow = workflow_path.read_text(encoding="utf-8")
        for action in USES_ACTION.findall(workflow):
            if action.startswith("./"):
                continue
            if "@" not in action:
                errors.append(f"{workflow_path.name} has malformed action reference: {action}")
                continue
            _, reference = action.rsplit("@", 1)
            if not PINNED_ACTION.fullmatch(reference):
                errors.append(
                    f"{workflow_path.name} action is not pinned to an immutable commit: {action}"
                )

    gradle_text = (root / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    if "VERSION" not in gradle_text or "versionName = appVersion" not in gradle_text:
        errors.append("Android versionName is not derived from VERSION")

    expected_tag = f"v{version}"
    release_manifest = root / "docs" / "releases" / f"{version}.yml"
    if not release_manifest.is_file():
        errors.append(f"release manifest is missing: docs/releases/{version}.yml")
    else:
        manifest_text = release_manifest.read_text(encoding="utf-8")
        if f"version: {version}" not in manifest_text or f"tag: {expected_tag}" not in manifest_text:
            errors.append("release manifest version or tag does not agree with VERSION")
    if args.expect_tag:
        try:
            tagged_commit = git(root, "rev-list", "-n", "1", expected_tag)
            head = git(root, "rev-parse", "HEAD")
            if tagged_commit != head:
                errors.append(f"{expected_tag} does not resolve to HEAD")
        except subprocess.CalledProcessError:
            errors.append(f"expected tag does not exist: {expected_tag}")

    if args.require_clean:
        status = git(root, "status", "--porcelain", "--untracked-files=all")
        if status:
            errors.append("working tree is not clean")

    for error in errors:
        fail(error)
    if errors:
        return 1

    print(f"release: version={version} tag={expected_tag} metadata=ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
