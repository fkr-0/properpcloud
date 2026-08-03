#!/usr/bin/env python3
"""Render the Arch package recipe from an exact release source archive."""

from __future__ import annotations

import argparse
import hashlib
import re
from pathlib import Path
from urllib.parse import urlparse


SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def render(template: str, version: str, source_url: str, source_sha256: str) -> str:
    if not SEMVER.fullmatch(version):
        raise ValueError("version must be stable SemVer")
    parsed = urlparse(source_url)
    if parsed.scheme != "https" or not parsed.netloc:
        raise ValueError("source URL must use HTTPS")
    if not re.fullmatch(r"[0-9a-f]{64}", source_sha256):
        raise ValueError("source checksum is malformed")
    replacements = {
        "@VERSION@": version,
        "@SOURCE_URL@": source_url,
        "@SOURCE_SHA256@": source_sha256,
    }
    for marker, value in replacements.items():
        if marker not in template:
            raise ValueError(f"template is missing {marker}")
        template = template.replace(marker, value)
    if "@" in template or "SKIP" in template:
        raise ValueError("rendered PKGBUILD retains a placeholder or skipped checksum")
    return template


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-url", required=True)
    parser.add_argument("--source-archive", type=Path, required=True)
    parser.add_argument("--template", type=Path, default=Path("packaging/arch/PKGBUILD.in"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.source_archive.is_file() or args.source_archive.is_symlink():
        raise SystemExit("Arch package error: source archive is missing or is a symlink")
    content = render(
        args.template.read_text(encoding="utf-8"),
        args.version,
        args.source_url,
        sha256(args.source_archive),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(content, encoding="utf-8")
    print(f"Arch package: version={args.version} source_sha256={sha256(args.source_archive)} output={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
