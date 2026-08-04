#!/usr/bin/env python3
"""Validate and summarize properpcloud 0.2.0 promotion evidence."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import yaml

PASS_PREFIXES = ("passed", "fallback_boundary_selected", "accepted_")
PENDING_PREFIXES = ("pending", "blocked")


def walk_statuses(value: object, path: tuple[str, ...] = ()) -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = (*path, str(key))
            if key == "status" and isinstance(child, str):
                found.append((".".join(path), child))
            else:
                found.extend(walk_statuses(child, child_path))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            found.extend(walk_statuses(child, (*path, str(index))))
    return found


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, default=Path("docs/reviews/0.2.0-promotion-matrix.yml"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--pre-tag", action="store_true", help="fail on every blocker except an explicitly post-tag-only gate")
    mode.add_argument("--strict", action="store_true", help="fail unless every promotion and publication status is fully passed")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    document = yaml.safe_load(args.matrix.read_text(encoding="utf-8"))
    promotion = document.get("promotion") if isinstance(document, dict) else None
    if not isinstance(promotion, dict) or promotion.get("version") != "0.2.0":
        raise SystemExit("0.2.0 readiness error: invalid promotion matrix")

    statuses = walk_statuses(promotion.get("gates", {}))
    if not statuses:
        raise SystemExit("0.2.0 readiness error: no gate statuses")
    invalid = [(path, status) for path, status in statuses if not status.startswith(PASS_PREFIXES + PENDING_PREFIXES)]
    if invalid:
        raise SystemExit(f"0.2.0 readiness error: unsupported statuses: {invalid}")

    passed = [(path, status) for path, status in statuses if status.startswith(PASS_PREFIXES)]
    pending = [(path, status) for path, status in statuses if status.startswith(PENDING_PREFIXES)]
    pre_tag_blockers = [(path, status) for path, status in pending if status != "pending_post_tag"]
    result = {
        "version": "0.2.0",
        "matrix": str(args.matrix),
        "passed": [{"gate": path, "status": status} for path, status in passed],
        "pending": [{"gate": path, "status": status} for path, status in pending],
        "pre_tag_blockers": [{"gate": path, "status": status} for path, status in pre_tag_blockers],
        "pre_tag_ready": not pre_tag_blockers,
        "publication_ready": not pending,
        "strict_ready": not pending,
    }
    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    else:
        print(f"0.2.0 readiness: {len(passed)} passed status groups, {len(pending)} pending/blocking status groups")
        for path, status in pending:
            print(f"  BLOCKED {path}: {status}")
    if args.pre_tag and pre_tag_blockers:
        return 1
    if args.strict and pending:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
