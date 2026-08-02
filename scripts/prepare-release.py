#!/usr/bin/env python3
"""Create deterministic, reviewable GitHub release inputs."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import urllib.request
from pathlib import Path


JAUDIOTAGGER_VERSION = "3.0.1"
JAUDIOTAGGER_SOURCES_SHA256 = "d9c79a145944e6bc37579403843c9da84cd81459e42c9877351802ee284f5858"
JAUDIOTAGGER_SOURCES_URL = (
    "https://repo1.maven.org/maven2/net/jthink/jaudiotagger/"
    f"{JAUDIOTAGGER_VERSION}/jaudiotagger-{JAUDIOTAGGER_VERSION}-sources.jar"
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_verified(url: str, destination: Path, expected_sha256: str) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "properpcloud-release/1"})
    with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)
    actual = sha256(destination)
    if actual != expected_sha256:
        destination.unlink(missing_ok=True)
        raise SystemExit(
            f"release error: checksum mismatch for {destination.name}: "
            f"expected {expected_sha256}, got {actual}"
        )


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    version = (root / "VERSION").read_text(encoding="utf-8").strip()
    source = root / "app/build/outputs/apk/debug/app-debug.apk"
    if not source.is_file():
        raise SystemExit("release error: debug APK is missing; run make ci first")

    dist = root / "dist"
    if dist.exists():
        shutil.rmtree(dist)
    dist.mkdir()

    apk = dist / f"properpcloud-{version}-demo-debug.apk"
    shutil.copy2(source, apk)
    checksum = sha256(apk)

    third_party = dist / "third-party"
    third_party.mkdir()
    jaudiotagger_sources = third_party / f"jaudiotagger-{JAUDIOTAGGER_VERSION}-sources.jar"
    download_verified(
        JAUDIOTAGGER_SOURCES_URL,
        jaudiotagger_sources,
        JAUDIOTAGGER_SOURCES_SHA256,
    )

    (dist / "SHA256SUMS").write_text(
        f"{checksum}  {apk.name}\n"
        f"{JAUDIOTAGGER_SOURCES_SHA256}  third-party/{jaudiotagger_sources.name}\n",
        encoding="utf-8",
    )

    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    image_id = subprocess.check_output(
        ["docker", "image", "inspect", "properpcloud/android-build:2026.08", "--format", "{{.Id}}"],
        cwd=root,
        text=True,
    ).strip()
    evidence = {
        "version": version,
        "tag": f"v{version}",
        "commit": commit,
        "artifact": {"path": apk.name, "size_bytes": apk.stat().st_size, "sha256": checksum},
        "third_party_sources": {
            "path": f"third-party/{jaudiotagger_sources.name}",
            "size_bytes": jaudiotagger_sources.stat().st_size,
            "sha256": JAUDIOTAGGER_SOURCES_SHA256,
            "license": "LGPL-2.1-or-later",
        },
        "toolchain_image_id": image_id,
        "compile_sdk": 37,
        "target_sdk": 36,
        "distribution_note": "Installable debug-signed demo build; production signing is intentionally external.",
        "authentication": {
            "oauth_client_id_bundled": bool(os.environ.get("PCLOUD_CLIENT_ID", "").strip()),
            "interim_direct_login_available": True,
            "password_persisted": False,
        },
        "live_pcloud_validation": "requires maintainer sandbox credentials and is not performed in public CI",
    }
    (dist / "release-evidence.json").write_text(
        json.dumps(evidence, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    marker = f"## [{version}]"
    start = changelog.find(marker)
    if start < 0:
        raise SystemExit(f"release error: CHANGELOG has no section for {version}")
    next_section = changelog.find("\n## [", start + len(marker))
    notes = changelog[start : next_section if next_section >= 0 else len(changelog)].strip()
    notes += (
        "\n\n## Artifact status\n\n"
        "The attached APK is an installable debug-signed demo build produced by the pinned "
        "Docker toolchain. Production signing remains an external maintainer boundary.\n\n"
        "The deterministic demo source is fully exercised in public CI. Live pCloud OAuth/direct-login and "
        "regional-account validation require maintainer-provided sandbox credentials and are "
        "reported separately rather than simulated.\n\n"
        "The exact jaudiotagger source archive used by the metadata adapter is attached under "
        "`third-party/`, checksum-verified, and rebuildable through the tagged Gradle project.\n"
    )
    (dist / "RELEASE_NOTES.md").write_text(notes + "\n", encoding="utf-8")
    print(
        f"release artifacts: {apk.name} sha256={checksum}; "
        f"{jaudiotagger_sources.name} sha256={JAUDIOTAGGER_SOURCES_SHA256}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
