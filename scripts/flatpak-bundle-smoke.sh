#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
version=$(<"$root/VERSION")
version=${version//$'\n'/}
app_id=dev.properpcloud.app
bundle=${1:-}

if [[ -z "$bundle" ]]; then
  echo "usage: flatpak-bundle-smoke.sh properpcloud-VERSION-x86_64.flatpak" >&2
  exit 64
fi
if [[ ! -f "$bundle" || -L "$bundle" || ! -s "$bundle" ]]; then
  echo "Flatpak smoke error: bundle is missing, symlinked, or empty" >&2
  exit 1
fi

bundle=$(realpath -e "$bundle")
if [[ "$(basename "$bundle")" != "properpcloud-${version}-x86_64.flatpak" ]]; then
  echo "Flatpak smoke error: filename does not match VERSION and architecture" >&2
  exit 1
fi
if flatpak info --user "$app_id" >/dev/null 2>&1; then
  echo "Flatpak smoke error: refusing to replace an existing user installation of $app_id" >&2
  exit 1
fi

installed=0
cleanup() {
  if [[ "$installed" -eq 1 ]]; then
    flatpak uninstall --user --noninteractive "$app_id" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

flatpak install --user --noninteractive "$bundle"
installed=1
dbus-run-session -- bash "$root/scripts/flatpak-smoke.sh" "$app_id"
echo "properpcloud Flatpak bundle smoke: OK (temporary install, clean profile, MPRIS, host mpv IPC)"
