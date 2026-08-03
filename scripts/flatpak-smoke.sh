#!/usr/bin/env bash
set -euo pipefail

app_id="${1:-dev.properpcloud.app}"
log=$(mktemp)
identity=$(mktemp)
probe_dir="${XDG_RUNTIME_DIR:-/tmp}/properpcloud"
probe="$probe_dir/flatpak-host-mpv-probe.sh"

cleanup() {
  if [[ -n "${app_pid:-}" ]]; then
    kill "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
  fi
  cat "$log"
  rm -f "$log" "$identity" "$probe"
}
trap cleanup EXIT

flatpak run --user "$app_id" --mpris-smoke >"$log" 2>&1 &
app_pid=$!

for _ in $(seq 1 80); do
  if gdbus call \
      --session \
      --dest org.mpris.MediaPlayer2.properpcloud \
      --object-path /org/mpris/MediaPlayer2 \
      --method org.freedesktop.DBus.Properties.Get \
      org.mpris.MediaPlayer2 Identity >"$identity" 2>/dev/null; then
    break
  fi
  kill -0 "$app_pid" 2>/dev/null || {
    echo "Flatpak MPRIS smoke exited before registering its bus name" >&2
    exit 1
  }
  sleep 0.25
done

grep -q properpcloud "$identity"
gdbus call \
  --session \
  --dest org.mpris.MediaPlayer2.properpcloud \
  --object-path /org/mpris/MediaPlayer2 \
  --method org.freedesktop.DBus.Properties.Get \
  org.mpris.MediaPlayer2.Player PlaybackStatus | grep -q Paused

mkdir -p "$probe_dir"
cat >"$probe" <<'PROBE'
#!/usr/bin/env sh
set -eu
socket="$XDG_RUNTIME_DIR/properpcloud/flatpak-smoke.sock"
mkdir -p "$(dirname "$socket")"
rm -f "$socket"
mpv \
  --no-config \
  --idle=yes \
  --terminal=no \
  --audio-display=no \
  --force-window=no \
  --input-ipc-server="$socket" &
mpv_pid=$!
cleanup_probe() {
  kill "$mpv_pid" 2>/dev/null || true
  wait "$mpv_pid" 2>/dev/null || true
  rm -f "$socket"
}
trap cleanup_probe EXIT
for _ in $(seq 1 100); do
  test -S "$socket" && exit 0
  kill -0 "$mpv_pid" 2>/dev/null || exit 1
  sleep 0.025
done
echo "host mpv did not create the shared Flatpak IPC socket" >&2
exit 1
PROBE
chmod 0755 "$probe"
flatpak run --user --command=sh "$app_id" "$probe"

echo "properpcloud Flatpak smoke: OK (runtime, MPRIS, host mpv IPC)"
