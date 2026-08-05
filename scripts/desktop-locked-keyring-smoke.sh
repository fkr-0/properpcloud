#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
APP=${1:-$ROOT/desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud}
EVIDENCE=${2:-$ROOT/build/evidence/0.2.0-locked-keyring.json}

for command in dbus-run-session gdbus gnome-keyring-daemon secret-tool python3; do
  command -v "$command" >/dev/null || {
    echo "$command is required for the locked-keyring smoke" >&2
    exit 1
  }
done
[[ -x "$APP" ]] || { echo "packaged properpcloud executable is missing" >&2; exit 1; }

WORK=$(mktemp -d "${TMPDIR:-/tmp}/properpcloud-locked-keyring.XXXXXX")
cleanup() {
  chmod -R u+rwX "$WORK" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

mkdir -p "$WORK/home/.local/share/keyrings" "$WORK/runtime/keyring" "$WORK/config" "$WORK/data"
chmod 0700 "$WORK" "$WORK/home" "$WORK/runtime" "$WORK/runtime/keyring" "$WORK/config" "$WORK/data"
LOG="$WORK/locked-keyring.log"
export APP LOG

HOME="$WORK/home" \
XDG_RUNTIME_DIR="$WORK/runtime" \
XDG_CONFIG_HOME="$WORK/config" \
XDG_DATA_HOME="$WORK/data" \
GIO_USE_VFS=local \
NO_AT_BRIDGE=1 \
dbus-run-session -- bash -euo pipefail <<'INNER'
export GIO_USE_VFS=local NO_AT_BRIDGE=1
unset DISPLAY WAYLAND_DISPLAY

keyring_pid=
cleanup_keyring() {
  if [[ -n "$keyring_pid" ]]; then
    kill "$keyring_pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      if ! kill -0 "$keyring_pid" 2>/dev/null; then
        return 0
      fi
      sleep 0.1
    done
    kill -KILL "$keyring_pid" 2>/dev/null || true
  fi
  return 0
}
trap cleanup_keyring EXIT

keyring_passphrase=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
printf '%s\n' "$keyring_passphrase" \
  | gnome-keyring-daemon --unlock --components=secrets \
      --control-directory="$XDG_RUNTIME_DIR/keyring" >/dev/null
unset keyring_passphrase
keyring_pid=$(pgrep -af '^gnome-keyring-daemon ' \
  | awk -v control="--control-directory=$XDG_RUNTIME_DIR/keyring" 'index($0, control) { print $1; exit }')
[[ -n "$keyring_pid" ]] || { echo "isolated keyring daemon was not found" >&2; exit 1; }

python3 - <<'PY' \
  | secret-tool store --label='properpcloud isolated locked-keyring audit' \
      service properpcloud key pcloud-session
import secrets
print(secrets.token_urlsafe(32), end="")
PY

before=$(gdbus call --session \
  --dest org.freedesktop.secrets \
  --object-path /org/freedesktop/secrets/collection/login \
  --method org.freedesktop.DBus.Properties.Get \
  org.freedesktop.Secret.Collection Locked)
[[ "$before" == *false* ]]

gdbus call --session \
  --dest org.freedesktop.secrets \
  --object-path /org/freedesktop/secrets \
  --method org.freedesktop.Secret.Service.Lock \
  "[objectpath '/org/freedesktop/secrets/collection/login']" >/dev/null

after=$(gdbus call --session \
  --dest org.freedesktop.secrets \
  --object-path /org/freedesktop/secrets/collection/login \
  --method org.freedesktop.DBus.Properties.Get \
  org.freedesktop.Secret.Collection Locked)
[[ "$after" == *true* ]]

"$APP" --locked-keyring-smoke | tee "$LOG"
INNER

python3 - "$LOG" "$EVIDENCE" <<'PY'
import json
import pathlib
import re
import sys

log = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
match = re.search(
    r"properpcloud locked keyring smoke: OK "
    r"\(credential_returned=false lookup_bounded=true elapsed_ms=(\d+)\)",
    log,
)
if not match:
    raise SystemExit("locked-keyring evidence error: fixed success summary not found")
elapsed = int(match.group(1))
if elapsed > 5_000:
    raise SystemExit("locked-keyring evidence error: lookup exceeded five-second bound")
payload = {
    "schema": 1,
    "scope": "isolated_ephemeral_gnome_keyring",
    "real_user_keyring_touched": False,
    "collection_locked": True,
    "credential_returned": False,
    "lookup_bounded": True,
    "lookup_elapsed_millis": elapsed,
    "credential_material_recorded": False,
    "dbus_address_recorded": False,
}
output = pathlib.Path(sys.argv[2])
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"locked-keyring evidence: {output}")
PY
