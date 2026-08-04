#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
version=${1:-$(<"$root/VERSION")}
tag="v${version}"
source_url="https://github.com/fkr-0/properpcloud/archive/refs/tags/${tag}.tar.gz"
work="$root/build/arch-gate/${version}"
archive="$work/properpcloud-${tag}.tar.gz"
recipe="$work/PKGBUILD"
evidence="$root/build/evidence/${version}-arch-package.json"
image=${ARCH_PACKAGE_IMAGE:-archlinux:base-devel}

rm -rf "$work"
mkdir -p "$work" "$(dirname "$evidence")" "$root/.cache/arch-gradle"

curl --fail --location --proto '=https' --tlsv1.2 --output "$archive" "$source_url"
python3 "$root/scripts/render-arch-pkgbuild.py" \
  --version "$version" \
  --source-url "$source_url" \
  --source-archive "$archive" \
  --output "$recipe"
cp "$archive" "$work/properpcloud-${version}.tar.gz"

uid=$(id -u)
gid=$(id -g)
docker run --rm \
  --volume "$work:/work" \
  --volume "$root/.cache/arch-gradle:/gradle-cache" \
  --workdir /work \
  --env VERSION="$version" \
  "$image" bash -euo pipefail -c '
    pacman -Syu --noconfirm --needed jdk21-openjdk mpv libsecret dbus git unzip fakeroot >/dev/null
    groupadd --gid '"$gid"' builder 2>/dev/null || true
    useradd --uid '"$uid"' --gid '"$gid"' --create-home builder 2>/dev/null || true
    chown -R '"$uid:$gid"' /work /gradle-cache
    runuser -u builder -- env GRADLE_USER_HOME=/gradle-cache makepkg --verifysource --noconfirm
    runuser -u builder -- env GRADLE_USER_HOME=/gradle-cache makepkg --cleanbuild --noconfirm
    package=$(find /work -maxdepth 1 -type f -name "properpcloud-${VERSION}-*.pkg.tar.*" -print -quit)
    test -n "$package"
    pacman -U --noconfirm "$package" >/dev/null
    test -x /usr/bin/properpcloud
    test -f /usr/share/licenses/properpcloud/LICENSE
    test -f /usr/share/licenses/properpcloud/THIRD_PARTY_NOTICES.md
    properpcloud --smoke
  '

package=$(find "$work" -maxdepth 1 -type f -name "properpcloud-${version}-*.pkg.tar.*" -print -quit)
test -n "$package"
sha=$(sha256sum "$package" | awk '{print $1}')
size=$(stat -c '%s' "$package")

python3 - "$evidence" "$version" "$tag" "$source_url" "$package" "$sha" "$size" <<'PY'
import json, pathlib, sys
output, version, tag, source_url, package, sha, size = sys.argv[1:]
payload = {
    "schema": 1,
    "version": version,
    "tag": tag,
    "source_url": source_url,
    "source_is_immutable_tag_archive": True,
    "package": pathlib.Path(package).name,
    "package_size_bytes": int(size),
    "package_sha256": sha,
    "makepkg_verifysource": "passed",
    "makepkg_cleanbuild": "passed",
    "license_inventory": "passed",
    "installed_smoke": "passed",
}
path = pathlib.Path(output)
path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Arch package gate: {version} {sha}")
print(f"evidence: {path}")
PY
