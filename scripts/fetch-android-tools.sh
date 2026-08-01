#!/usr/bin/env bash
set -euo pipefail

version="${ANDROID_CMDLINE_TOOLS_VERSION:-15859902}"
expected_sha256="${ANDROID_CMDLINE_TOOLS_SHA256:-4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583}"
archive="commandlinetools-linux-${version}_latest.zip"
destination="${ANDROID_TOOLCHAIN_CACHE_DIR:-$PWD/.cache/toolchain}/${archive}"
url="https://dl.google.com/android/repository/${archive}"

mkdir -p "$(dirname "$destination")"

if [[ -f "$destination" ]] && echo "${expected_sha256}  ${destination}" | sha256sum --check --strict --status; then
  printf 'Android command-line tools cache verified: %s\n' "$destination"
  exit 0
fi

rm -f "$destination"
partial="${destination}.part"

if command -v aria2c >/dev/null 2>&1; then
  aria2c \
    --allow-overwrite=true \
    --auto-file-renaming=false \
    --continue=true \
    --file-allocation=none \
    --max-connection-per-server=8 \
    --max-tries=8 \
    --min-split-size=1M \
    --retry-wait=2 \
    --split=8 \
    --dir "$(dirname "$partial")" \
    --out "$(basename "$partial")" \
    "$url"
else
  curl \
    --continue-at - \
    --fail \
    --location \
    --retry 8 \
    --retry-delay 2 \
    --output "$partial" \
    "$url"
fi

echo "${expected_sha256}  ${partial}" | sha256sum --check --strict
mv "$partial" "$destination"
rm -f "${partial}.aria2"
printf 'Android command-line tools cached: %s\n' "$destination"
