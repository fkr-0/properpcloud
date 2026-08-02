#!/usr/bin/env bash
set -euo pipefail

APP=${1:-desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud}
LOG=$(mktemp)
ROOT_OUT=$(mktemp)

cleanup() {
  if [[ -n "${APP_PID:-}" ]]; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  cat "$LOG"
  rm -f "$LOG" "$ROOT_OUT"
}
trap cleanup EXIT

"$APP" --mpris-smoke >"$LOG" 2>&1 &
APP_PID=$!

for _ in $(seq 1 40); do
  if gdbus call \
      --session \
      --dest org.mpris.MediaPlayer2.properpcloud \
      --object-path /org/mpris/MediaPlayer2 \
      --method org.freedesktop.DBus.Properties.Get \
      org.mpris.MediaPlayer2 Identity >"$ROOT_OUT" 2>/dev/null; then
    break
  fi
  kill -0 "$APP_PID" 2>/dev/null || {
    echo "MPRIS smoke process exited before registering its bus name" >&2
    exit 1
  }
  sleep 0.25
done

grep -q properpcloud "$ROOT_OUT"
cat "$ROOT_OUT"

gdbus call \
  --session \
  --dest org.mpris.MediaPlayer2.properpcloud \
  --object-path /org/mpris/MediaPlayer2 \
  --method org.freedesktop.DBus.Properties.Get \
  org.mpris.MediaPlayer2.Player PlaybackStatus | grep -q Paused

gdbus call \
  --session \
  --dest org.mpris.MediaPlayer2.properpcloud \
  --object-path /org/mpris/MediaPlayer2 \
  --method org.freedesktop.DBus.Properties.Get \
  org.mpris.MediaPlayer2.Player Metadata | grep -Eq 'MPRIS smoke|mpris:trackid|mpris:length'

echo "properpcloud packaged MPRIS smoke: OK"
