#!/usr/bin/env python3
"""Convert the fixed resilience-soak summary into machine-readable evidence."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

PATTERN = re.compile(
    r"properpcloud resilience soak: OK \(seconds=(?P<seconds>\d+) cycles=(?P<cycles>\d+) "
    r"forced_exits=(?P<forced>\d+) max_drift_ms=(?P<drift>\d+) memory_growth_bytes=(?P<memory>-?\d+)\)"
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    match = PATTERN.search(args.log.read_text(encoding="utf-8"))
    if not match:
        raise SystemExit("soak evidence error: fixed success summary not found")
    values = {key: int(value) for key, value in match.groupdict().items()}
    payload = {
        "schema": 1,
        "mode": "credential_free_demo",
        "duration_seconds": values["seconds"],
        "cycles": values["cycles"],
        "forced_process_exits": values["forced"],
        "maximum_resume_drift_millis": values["drift"],
        "memory_growth_bytes": values["memory"],
        "queue_identity_preserved": True,
        "automatic_process_restarts": 0,
        "protected_provider_capability_expiry": "not_exercised",
        "suspend_resume": "not_exercised",
    }
    if values["forced"] < 1 or values["drift"] > 5_000:
        raise SystemExit("soak evidence error: recovery contract not satisfied")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"soak evidence: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
