#!/usr/bin/env bash
set -euo pipefail

# Mount the complete pCloud remote locally. Configure the named rclone remote
# separately so credentials stay in rclone's permission-restricted config.
remote="${PROPERPCLOUD_PCLOUD_REMOTE:-pcloud:}"
data_home="${XDG_DATA_HOME:-$HOME/.local/share}"
cache_home="${XDG_CACHE_HOME:-$HOME/.cache}"
mount_root="${PROPERPCLOUD_MOUNT_ROOT:-$data_home/properpcloud/mount/pcloud}"
cache_root="${PROPERPCLOUD_RCLONE_CACHE:-$cache_home/properpcloud/rclone-vfs}"

command -v rclone >/dev/null 2>&1 || {
  printf '%s\n' 'properpcloud: rclone is required for the pCloud mount' >&2
  exit 127
}

mkdir -p -- "$mount_root" "$cache_root"
chmod 700 -- "$mount_root" "$cache_root" 2>/dev/null || true

extra=()
if [[ "${PROPERPCLOUD_MOUNT_READ_WRITE:-0}" != "1" ]]; then
  extra+=(--read-only)
fi

exec rclone mount "$remote" "$mount_root" \
  --cache-dir "$cache_root" \
  --vfs-cache-mode full \
  --vfs-cache-max-age 168h \
  --dir-cache-time 5m \
  --poll-interval 1m \
  --vfs-read-chunk-size 32M \
  --vfs-read-chunk-size-limit 512M \
  --umask 077 \
  "${extra[@]}"
