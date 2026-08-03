#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
version=$(<"$root/VERSION")
version=${version//$'\n'/}
image=${1:-}

if [[ -z "$image" ]]; then
  echo "usage: appimage-smoke.sh properpcloud-VERSION-x86_64.AppImage" >&2
  exit 64
fi
if [[ ! -f "$image" || -L "$image" || ! -x "$image" ]]; then
  echo "AppImage smoke error: image is missing, symlinked, or not executable" >&2
  exit 1
fi

image=$(realpath -e "$image")
expected_name="properpcloud-${version}-x86_64.AppImage"
if [[ "$(basename "$image")" != "$expected_name" ]]; then
  echo "AppImage smoke error: filename does not match VERSION and architecture" >&2
  exit 1
fi

work=$(mktemp -d "${TMPDIR:-/tmp}/properpcloud-appimage-smoke.XXXXXX")
cleanup() {
  rm -rf "$work"
}
trap cleanup EXIT

(
  cd "$work"
  "$image" --appimage-extract >/dev/null
)

appdir="$work/squashfs-root"
launcher="$appdir/AppRun"
desktop="$appdir/usr/share/applications/dev.properpcloud.app.desktop"
binary="$appdir/usr/lib/properpcloud/bin/properpcloud"

if [[ ! -f "$launcher" || -L "$launcher" || ! -x "$launcher" || ! -s "$launcher" ]]; then
  echo "AppImage smoke error: extracted AppRun is missing, symlinked, empty, or not executable" >&2
  exit 1
fi
if ! cmp -s "$root/packaging/linux/AppRun" "$launcher"; then
  echo "AppImage smoke error: extracted AppRun differs from the reviewed launcher" >&2
  exit 1
fi
if [[ ! -f "$desktop" || -L "$desktop" ]] || ! grep -Fqx "X-AppImage-Version=$version" "$desktop"; then
  echo "AppImage smoke error: embedded desktop metadata does not match VERSION" >&2
  exit 1
fi
if [[ ! -e "$binary" ]]; then
  echo "AppImage smoke error: packaged properpcloud launcher is missing" >&2
  exit 1
fi
resolved_binary=$(realpath -e "$binary")
case "$resolved_binary" in
  "$appdir"/*) ;;
  *)
    echo "AppImage smoke error: packaged launcher escapes the extracted image" >&2
    exit 1
    ;;
esac

"$launcher" --smoke
echo "properpcloud AppImage smoke: OK (verified extraction, metadata, launcher, clean profile)"
