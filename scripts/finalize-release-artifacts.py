#!/usr/bin/env python3
"""Merge independently built Linux packages into the prepared release directory."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def record(path: Path, dist: Path) -> dict[str, object]:
    return {
        "path": path.relative_to(dist).as_posix(),
        "size_bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--appimage", type=Path, required=True)
    parser.add_argument("--flatpak", type=Path, required=True)
    parser.add_argument("--flatpak-runtime-version", default="25.08")
    args = parser.parse_args()

    dist = args.dist.resolve()
    if not (dist / "release-evidence.json").is_file():
        raise SystemExit("release error: prepared Android release directory is missing")

    copied: dict[str, Path] = {}
    for kind, source in (("linux_appimage", args.appimage), ("linux_flatpak", args.flatpak)):
        source = source.resolve()
        if not source.is_file() or source.stat().st_size == 0:
            raise SystemExit(f"release error: missing {kind} input: {source}")
        destination = dist / source.name
        shutil.copy2(source, destination)
        copied[kind] = destination

    evidence_path = dist / "release-evidence.json"
    evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
    evidence.pop("artifact", None)
    apk = next(iter(sorted(dist.glob("*.apk"))), None)
    if apk is None:
        raise SystemExit("release error: prepared APK is missing")
    evidence["artifacts"] = {
        "android_apk": record(apk, dist),
        **{kind: record(path, dist) for kind, path in copied.items()},
    }
    evidence["linux_distribution"] = {
        "architecture": "x86_64",
        "appimage_runtime": "bundled jlink runtime; host mpv remains required",
        "flatpak_app_id": "dev.properpcloud.app",
        "flatpak_runtime": f"org.freedesktop.Platform//{args.flatpak_runtime_version}",
        "flatpak_mpv_boundary": (
            "host mpv invoked through flatpak-spawn with only "
            "xdg-run/properpcloud shared for private IPC"
        ),
    }
    evidence_path.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    checksum_paths = [
        *sorted(dist.glob("*.apk")),
        *sorted(dist.glob("*.AppImage")),
        *sorted(dist.glob("*.flatpak")),
        *sorted((dist / "third-party").glob("*")),
    ]
    (dist / "SHA256SUMS").write_text(
        "".join(f"{sha256(path)}  {path.relative_to(dist).as_posix()}\n" for path in checksum_paths),
        encoding="utf-8",
    )

    notes_path = dist / "RELEASE_NOTES.md"
    notes = notes_path.read_text(encoding="utf-8").rstrip()
    notes += (
        "\n\n## Linux packages\n\n"
        "The release includes an x86_64 AppImage and a single-file Flatpak bundle. "
        "Both contain the application runtime. Playback still uses the distribution's "
        "installed `mpv`; the Flatpak invokes that host command through `flatpak-spawn`. "
        "Install the Flatpak bundle with `flatpak install ./properpcloud-*.flatpak`.\n"
    )
    notes_path.write_text(notes + "\n", encoding="utf-8")

    for kind, path in copied.items():
        print(f"release artifact: {kind}={path.name} sha256={sha256(path)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
