#!/usr/bin/env bash
set -euo pipefail

app_id="${1:-dev.properpcloud.app}"
log=$(mktemp)
identity=$(mktemp)
playback_status=$(mktemp)
probe_dir="${XDG_RUNTIME_DIR:-/tmp}/properpcloud"
probe="$probe_dir/flatpak-host-mpv-probe.sh"
profile="/tmp/properpcloud-clean-profile-$$"
flatpak_profile=(
  --env="HOME=$profile/home"
  --env="XDG_CONFIG_HOME=$profile/config"
  --env="XDG_DATA_HOME=$profile/data"
  --env="XDG_CACHE_HOME=$profile/cache"
  --env="XDG_STATE_HOME=$profile/state"
  --env="TMPDIR=$profile/tmp"
  --env="GSETTINGS_BACKEND=memory"
  --env="PROPERPCLOUD_CLEAN_PROFILE=1"
)

cleanup() {
  if [[ -n "${app_pid:-}" ]]; then
    kill "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
  fi
  cat "$log"
  rm -f "$log" "$identity" "$playback_status" "$probe"
}
trap cleanup EXIT

flatpak run --user "${flatpak_profile[@]}" "$app_id" --mpris-smoke >"$log" 2>&1 &
app_pid=$!

wait_for_mpris_property() {
  local interface=$1
  local property=$2
  local expected=$3
  local output=$4
  local description=$5

  for _ in $(seq 1 80); do
    if gdbus call \
        --session \
        --dest org.mpris.MediaPlayer2.properpcloud \
        --object-path /org/mpris/MediaPlayer2 \
        --method org.freedesktop.DBus.Properties.Get \
        "$interface" "$property" >"$output" 2>/dev/null && \
        grep -q "$expected" "$output"; then
      return 0
    fi
    if ! kill -0 "$app_pid" 2>/dev/null; then
      echo "Flatpak MPRIS smoke exited before exposing $description" >&2
      return 1
    fi
    sleep 0.25
  done
  echo "Flatpak MPRIS smoke timed out waiting for $description" >&2
  return 1
}

wait_for_mpris_property \
  org.mpris.MediaPlayer2 Identity properpcloud "$identity" identity
wait_for_mpris_property \
  org.mpris.MediaPlayer2.Player PlaybackStatus Paused "$playback_status" playback-status

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
flatpak run --user "${flatpak_profile[@]}" --command=sh "$app_id" "$probe"

echo "properpcloud Flatpak smoke: OK (runtime, MPRIS, host mpv IPC)"
