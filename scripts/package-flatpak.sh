#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
version=$(<"$root/VERSION")
version=${version//$'\n'/}
app_id="dev.properpcloud.app"
runtime_version="${PROPERPCLOUD_FLATPAK_RUNTIME_VERSION:-25.08}"
source_image="${PROPERPCLOUD_DESKTOP_IMAGE:-$root/desktop-app/build/compose/binaries/main/app/properpcloud}"
output_dir="${PROPERPCLOUD_LINUX_OUTPUT_DIR:-$root/build/releases}"
work_dir="${PROPERPCLOUD_FLATPAK_WORK_DIR:-$root/build/packaging/flatpak}"
build_dir="$work_dir/build"
repo_dir="$work_dir/repo"
output="$output_dir/properpcloud-${version}-x86_64.flatpak"
source_date_epoch="${SOURCE_DATE_EPOCH:-$(git -C "$root" show -s --format=%ct HEAD)}"
source_date_timestamp=$(date --utc --date="@${source_date_epoch}" +%Y-%m-%dT%H:%M:%SZ)

command -v flatpak >/dev/null || {
  echo "Flatpak packaging error: flatpak is not installed" >&2
  exit 1
}
command -v ostree >/dev/null || {
  echo "Flatpak packaging error: ostree is not installed" >&2
  exit 1
}
test -x "$source_image/bin/properpcloud" || {
  echo "Flatpak packaging error: Compose desktop image is missing at $source_image" >&2
  exit 1
}
flatpak info "org.freedesktop.Platform//${runtime_version}" >/dev/null || {
  echo "Flatpak packaging error: org.freedesktop.Platform//${runtime_version} is not installed" >&2
  exit 1
}

rm -rf "$work_dir"
mkdir -p "$work_dir" "$output_dir"
# The application is already compiled into a jlink image. The Platform is used
# as the temporary build root so packaging does not download an unused SDK.
flatpak build-init \
  --arch=x86_64 \
  "$build_dir" \
  "$app_id" \
  org.freedesktop.Platform \
  org.freedesktop.Platform \
  "$runtime_version"
sed -i \
  "s#^sdk=org.freedesktop.Platform/#sdk=org.freedesktop.Sdk/#" \
  "$build_dir/metadata"

install -d \
  "$build_dir/files/lib/properpcloud" \
  "$build_dir/files/bin" \
  "$build_dir/files/share/applications" \
  "$build_dir/files/share/icons/hicolor/512x512/apps" \
  "$build_dir/files/share/metainfo"
cp -a "$source_image/." "$build_dir/files/lib/properpcloud/"
install -Dm755 "$root/packaging/flatpak/properpcloud" "$build_dir/files/bin/properpcloud"
install -Dm755 "$root/packaging/flatpak/mpv-host" "$build_dir/files/bin/mpv"
install -Dm644 \
  "$root/packaging/linux/dev.properpcloud.app.desktop" \
  "$build_dir/files/share/applications/dev.properpcloud.app.desktop"
install -Dm644 \
  "$root/packaging/linux/dev.properpcloud.app.png" \
  "$build_dir/files/share/icons/hicolor/512x512/apps/dev.properpcloud.app.png"
install -Dm644 \
  "$root/packaging/linux/dev.properpcloud.app.metainfo.xml" \
  "$build_dir/files/share/metainfo/dev.properpcloud.app.metainfo.xml"

flatpak build-finish \
  --command=properpcloud \
  --share=ipc \
  --share=network \
  --socket=x11 \
  --socket=wayland \
  --device=dri \
  --filesystem=xdg-run/properpcloud:create \
  --talk-name=org.freedesktop.Flatpak \
  --talk-name=org.freedesktop.secrets \
  --own-name=org.mpris.MediaPlayer2.properpcloud \
  --env=LC_NUMERIC=C \
  "$build_dir"

ostree init --repo="$repo_dir" --mode=archive
# The repository is disposable build output. Use a fixed reserve rather than a
# percentage that scales with the host filesystem size.
ostree config --repo="$repo_dir" set core.min-free-space-size 128MB

flatpak build-export \
  --arch=x86_64 \
  --timestamp="$source_date_timestamp" \
  --subject="properpcloud ${version}" \
  "$repo_dir" \
  "$build_dir" \
  stable

rm -f "$output"
flatpak build-bundle \
  --arch=x86_64 \
  --runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo \
  "$repo_dir" \
  "$output" \
  "$app_id" \
  stable

test -s "$output"
printf 'Flatpak: %s sha256=%s\n' "$output" "$(sha256sum "$output" | awk '{print $1}')"
