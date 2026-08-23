#!/usr/bin/env bash
set -euo pipefail

app="${1:?usage: desktop-local-tag-recovery-process-smoke.sh APP [EVIDENCE_JSON]}"
evidence="${2:-build/evidence/0.2.0-local-tag-recovery-process.json}"

test -x "$app" || { echo "packaged properpcloud executable is missing" >&2; exit 1; }
mkdir -p build build/evidence "$(dirname "$evidence")"

root="$(mktemp -d "$PWD/build/.local-tag-recovery-process.XXXXXX")"
first_log="$(mktemp "$PWD/build/evidence/.local-tag-recovery-first.XXXXXX.log")"
second_log="$(mktemp "$PWD/build/evidence/.local-tag-recovery-second.XXXXXX.log")"
first_pid=""

cleanup() {
  if [[ -n "$first_pid" ]] && kill -0 "$first_pid" 2>/dev/null; then
    kill -KILL "$first_pid" 2>/dev/null || true
    wait "$first_pid" 2>/dev/null || true
  fi
  rm -rf "$root"
  rm -f "$first_log" "$second_log"
}
trap cleanup EXIT

"$app" --local-tag-recovery-kill-smoke "$root" >"$first_log" 2>&1 &
first_pid=$!

ready=0
for _ in $(seq 1 120); do
  if grep -Fq 'replacement-complete; awaiting external kill' "$first_log"; then
    ready=1
    break
  fi
  if ! kill -0 "$first_pid" 2>/dev/null; then
    break
  fi
  sleep 0.05
done

if [[ "$ready" != "1" ]]; then
  echo "packaged recovery smoke never reached the destructive replacement boundary" >&2
  sed -n '1,80p' "$first_log" >&2
  exit 1
fi

kill -KILL "$first_pid"
set +e
wait "$first_pid"
first_exit=$?
set -e
first_pid=""
if [[ "$first_exit" != "137" ]]; then
  echo "expected externally killed packaged process exit 137, got $first_exit" >&2
  exit 1
fi

"$app" --local-tag-recovery-restart-smoke "$root" >"$second_log" 2>&1
grep -Fq 'properpcloud local tag recovery process smoke: OK' "$second_log" || {
  echo "fresh packaged process did not verify guarded recovery" >&2
  sed -n '1,120p' "$second_log" >&2
  exit 1
}

if find "$root" -type f -name '.properpcloud-recovery-*.v1' -print -quit | grep -q .; then
  echo "durable recovery record remained after verified guarded rollback" >&2
  exit 1
fi

python3 - "$app" "$evidence" "$first_exit" <<'PY'
from __future__ import annotations

import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

app = Path(sys.argv[1])
evidence = Path(sys.argv[2])
first_exit = int(sys.argv[3])

payload = {
    "schema": "properpcloud-local-tag-recovery-process-evidence-v1",
    "recorded_at": datetime.now(timezone.utc).isoformat(),
    "packaged_executable": app.name,
    "packaged_executable_sha256": hashlib.sha256(app.read_bytes()).hexdigest(),
    "first_process": {
        "reached_atomic_replacement_boundary": True,
        "terminated_by_external_sigkill": True,
        "exit_code": first_exit,
        "normal_disarm_ran": False,
    },
    "fresh_process": {
        "explicit_root_reselection_boundary": True,
        "durable_recovery_rediscovered": True,
        "guarded_exact_hash_rollback_verified": True,
        "recovery_authority_cleared": True,
    },
    "privacy": {
        "selected_root_path_recorded": False,
        "media_path_recorded": False,
        "provider_url_recorded": False,
        "credential_recorded": False,
    },
    "physical_power_cut_exercised": False,
}
evidence.parent.mkdir(parents=True, exist_ok=True)
evidence.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

cat "$second_log"
printf 'properpcloud local tag recovery process evidence: %s\n' "$evidence"
