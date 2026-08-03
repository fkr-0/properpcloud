#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
version=$(<"$root/VERSION")
version=${version//$'\n'/}
source_image="${PROPERPCLOUD_DESKTOP_IMAGE:-$root/desktop-app/build/compose/binaries/main/app/properpcloud}"
output_dir="${PROPERPCLOUD_LINUX_OUTPUT_DIR:-$root/build/releases}"
work_dir="${PROPERPCLOUD_APPIMAGE_WORK_DIR:-$root/build/packaging/appimage}"
appdir="$work_dir/properpcloud.AppDir"
cache_root="${APPIMAGETOOL_CACHE_DIR:-$root/.cache/appimage}"
tool="$cache_root/${APPIMAGETOOL_VERSION:-1.9.1}/appimagetool-x86_64.AppImage"
runtime_file="$cache_root/${APPIMAGE_RUNTIME_VERSION:-20251108}/runtime-x86_64"
output="$output_dir/properpcloud-${version}-x86_64.AppImage"

test -x "$source_image/bin/properpcloud" || {
  echo "AppImage packaging error: Compose desktop image is missing at $source_image" >&2
  exit 1
}

if [[ ! -x "$tool" || ! -f "$runtime_file" ]]; then
  (cd "$root" && bash scripts/fetch-appimagetool.sh)
fi

rm -rf "$work_dir"
mkdir -p \
  "$appdir/usr/lib/properpcloud" \
  "$appdir/usr/bin" \
  "$appdir/usr/share/applications" \
  "$appdir/usr/share/icons/hicolor/512x512/apps" \
  "$appdir/usr/share/metainfo" \
  "$output_dir"

cp -a "$source_image/." "$appdir/usr/lib/properpcloud/"
install -Dm755 "$root/packaging/linux/AppRun" "$appdir/AppRun"
ln -s ../lib/properpcloud/bin/properpcloud "$appdir/usr/bin/properpcloud"
install -Dm644 \
  "$root/packaging/linux/dev.properpcloud.app.desktop" \
  "$appdir/usr/share/applications/dev.properpcloud.app.desktop"
printf 'X-AppImage-Version=%s\n' "$version" >> "$appdir/usr/share/applications/dev.properpcloud.app.desktop"
install -Dm644 \
  "$root/packaging/linux/dev.properpcloud.app.png" \
  "$appdir/usr/share/icons/hicolor/512x512/apps/dev.properpcloud.app.png"
install -Dm644 \
  "$root/packaging/linux/dev.properpcloud.app.metainfo.xml" \
  "$appdir/usr/share/metainfo/dev.properpcloud.app.metainfo.xml"
ln -s dev.properpcloud.app.metainfo.xml "$appdir/usr/share/metainfo/dev.properpcloud.app.appdata.xml"
ln -s usr/share/applications/dev.properpcloud.app.desktop "$appdir/dev.properpcloud.app.desktop"
ln -s usr/share/icons/hicolor/512x512/apps/dev.properpcloud.app.png "$appdir/dev.properpcloud.app.png"

export ARCH=x86_64
export APPIMAGE_EXTRACT_AND_RUN=1
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$root" show -s --format=%ct HEAD)}"
rm -f "$output"
"$tool" --runtime-file "$runtime_file" "$appdir" "$output"
chmod 0755 "$output"

test -s "$output"
printf 'AppImage: %s sha256=%s\n' "$output" "$(sha256sum "$output" | awk '{print $1}')"
