#!/usr/bin/env bash
set -euo pipefail

version="${APPIMAGETOOL_VERSION:-1.9.1}"
expected_sha256="${APPIMAGETOOL_SHA256:-ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0}"
artifact="appimagetool-x86_64.AppImage"
cache_root="${APPIMAGETOOL_CACHE_DIR:-$PWD/.cache/appimage}"
destination="${cache_root}/${version}/${artifact}"
url="https://github.com/AppImage/appimagetool/releases/download/${version}/${artifact}"
runtime_version="${APPIMAGE_RUNTIME_VERSION:-20251108}"
runtime_sha256="${APPIMAGE_RUNTIME_SHA256:-2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d}"
runtime_destination="${cache_root}/${runtime_version}/runtime-x86_64"
runtime_url="https://github.com/AppImage/type2-runtime/releases/download/${runtime_version}/runtime-x86_64"

download_verified() {
  local source_url=$1
  local target=$2
  local checksum=$3
  local partial="${target}.part"

  mkdir -p "$(dirname "$target")"
  if [[ -f "$target" ]] && echo "${checksum}  ${target}" | sha256sum --check --strict --status; then
    return 0
  fi

  rm -f "$target"
  curl \
    --continue-at - \
    --fail \
    --location \
    --retry 8 \
    --retry-delay 2 \
    --output "$partial" \
    "$source_url"
  if ! echo "${checksum}  ${partial}" | sha256sum --check --strict; then
    rm -f "$partial"
    return 1
  fi
  mv "$partial" "$target"
}

download_verified "$url" "$destination" "$expected_sha256"
download_verified "$runtime_url" "$runtime_destination" "$runtime_sha256"
chmod 0755 "$destination"
printf 'AppImage tools verified: %s; runtime=%s\n' "$destination" "$runtime_destination"
