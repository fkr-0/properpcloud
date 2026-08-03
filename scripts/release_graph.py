#!/usr/bin/env python3
"""Validate the complete release artifact/checksum/provenance graph."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path, PurePosixPath
from typing import Any


SHA256 = re.compile(r"^[0-9a-f]{64}$")
FORBIDDEN_EVIDENCE_KEYS = {
    "access_token",
    "auth_token",
    "client_secret",
    "password_value",
    "signed_url",
    "stream_url",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _safe_relative_path(value: object) -> str | None:
    if not isinstance(value, str) or not value:
        return None
    if "\\" in value or any(ord(character) < 32 or ord(character) == 127 for character in value):
        return None
    candidate = PurePosixPath(value)
    if candidate.is_absolute() or candidate == PurePosixPath(".") or ".." in candidate.parts:
        return None
    return candidate.as_posix()


def _regular_file_in_dist(dist: Path, relative: str) -> tuple[Path | None, str | None]:
    parts = PurePosixPath(relative).parts
    candidate = dist.joinpath(*parts)
    current = dist
    for part in parts[:-1]:
        current = current / part
        if current.is_symlink():
            return None, f"{relative} traverses a symlinked parent directory"
    if candidate.is_symlink():
        return None, f"{relative} is a symlink"
    if not candidate.is_file():
        return None, f"{relative} is missing or is not a regular file"
    try:
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(dist)
    except (OSError, ValueError):
        return None, f"{relative} escapes the release directory"
    return candidate, None


def _find_forbidden_keys(value: Any, prefix: str = "") -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = str(key).lower()
            path = f"{prefix}.{key}" if prefix else str(key)
            if normalized in FORBIDDEN_EVIDENCE_KEYS:
                found.append(path)
            found.extend(_find_forbidden_keys(child, path))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            found.extend(_find_forbidden_keys(child, f"{prefix}[{index}]"))
    return found


def _parse_checksums(path: Path) -> tuple[dict[str, str], list[str]]:
    entries: dict[str, str] = {}
    errors: list[str] = []
    if not path.is_file() or path.is_symlink():
        return entries, ["SHA256SUMS is missing or is a symlink"]
    for number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw_line.strip():
            continue
        fields = raw_line.split(maxsplit=1)
        if len(fields) != 2 or not SHA256.fullmatch(fields[0]):
            errors.append(f"SHA256SUMS line {number} is malformed")
            continue
        relative = fields[1].lstrip("*")
        safe = _safe_relative_path(relative)
        if safe is None:
            errors.append(f"SHA256SUMS line {number} has an unsafe path")
        elif safe in entries:
            errors.append(f"SHA256SUMS repeats {safe}")
        else:
            entries[safe] = fields[0]
    return entries, errors


def validate_release_graph(dist: Path, version: str, commit: str) -> list[str]:
    dist = dist.resolve()
    errors: list[str] = []
    evidence_path = dist / "release-evidence.json"
    notes_path = dist / "RELEASE_NOTES.md"
    if not evidence_path.is_file() or evidence_path.is_symlink():
        return ["release-evidence.json is missing or is a symlink"]
    try:
        evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return [f"release-evidence.json is invalid: {error}"]

    if evidence.get("version") != version:
        errors.append("evidence version does not match the requested version")
    if evidence.get("tag") != f"v{version}":
        errors.append("evidence tag does not match the requested version")
    if evidence.get("commit") != commit:
        errors.append("evidence commit does not match the immutable release commit")
    forbidden = _find_forbidden_keys(evidence)
    if forbidden:
        errors.append("evidence contains forbidden secret/ephemeral fields: " + ", ".join(forbidden))

    artifacts = evidence.get("artifacts")
    if not isinstance(artifacts, dict):
        errors.append("evidence has no finalized artifacts map")
        artifacts = {}
    required_kinds = {"android_apk", "linux_appimage", "linux_flatpak"}
    if set(artifacts) != required_kinds:
        errors.append("evidence artifact kinds must be exactly android_apk, linux_appimage, and linux_flatpak")

    records: list[tuple[str, dict[str, Any]]] = []
    for kind, record in artifacts.items():
        if isinstance(record, dict):
            records.append((str(kind), record))
        else:
            errors.append(f"evidence record {kind} is not an object")
    third_party = evidence.get("third_party_sources")
    if isinstance(third_party, dict):
        records.append(("third_party_sources", third_party))
    else:
        errors.append("evidence has no third-party source record")

    expected_checksums: dict[str, str] = {}
    for kind, record in records:
        relative = _safe_relative_path(record.get("path"))
        expected_digest = record.get("sha256")
        expected_size = record.get("size_bytes")
        if relative is None:
            errors.append(f"{kind} has an unsafe or missing path")
            continue
        if relative in expected_checksums:
            errors.append(f"multiple evidence records point to {relative}")
            continue
        path, path_error = _regular_file_in_dist(dist, relative)
        if path_error is not None or path is None:
            errors.append(path_error or f"{relative} is invalid")
            continue
        if path.stat().st_size <= 0:
            errors.append(f"{relative} is empty")
        if not isinstance(expected_size, int) or expected_size != path.stat().st_size:
            errors.append(f"{relative} size does not match evidence")
        actual_digest = sha256(path)
        if not isinstance(expected_digest, str) or expected_digest != actual_digest:
            errors.append(f"{relative} digest does not match evidence")
        expected_checksums[relative] = actual_digest

    expected_names = {
        "android_apk": re.compile(rf"^properpcloud-{re.escape(version)}-.+\.apk$"),
        "linux_appimage": re.compile(rf"^properpcloud-{re.escape(version)}-x86_64\.AppImage$"),
        "linux_flatpak": re.compile(rf"^properpcloud-{re.escape(version)}-x86_64\.flatpak$"),
    }
    for kind, pattern in expected_names.items():
        record = artifacts.get(kind)
        if isinstance(record, dict):
            relative = _safe_relative_path(record.get("path"))
            if relative is not None and not pattern.fullmatch(PurePosixPath(relative).name):
                errors.append(f"{kind} filename does not encode version and architecture")

    checksums, checksum_errors = _parse_checksums(dist / "SHA256SUMS")
    errors.extend(checksum_errors)
    if checksums != expected_checksums:
        missing = sorted(set(expected_checksums) - set(checksums))
        extra = sorted(set(checksums) - set(expected_checksums))
        mismatched = sorted(
            path for path in set(checksums) & set(expected_checksums)
            if checksums[path] != expected_checksums[path]
        )
        errors.append(
            "checksum graph differs from evidence"
            f" (missing={missing}, extra={extra}, mismatched={mismatched})"
        )

    if not notes_path.is_file() or notes_path.is_symlink():
        errors.append("RELEASE_NOTES.md is missing or is a symlink")
    elif f"## [{version}]" not in notes_path.read_text(encoding="utf-8"):
        errors.append("release notes do not contain the requested version section")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    errors = validate_release_graph(args.dist, args.version, args.commit)
    for error in errors:
        print(f"release graph error: {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"release graph: version={args.version} commit={args.commit[:12]} artifacts=verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
