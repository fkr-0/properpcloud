#!/usr/bin/env bash
set -euo pipefail

version="${ROBOLECTRIC_ANDROID_ALL_VERSION:-16-robolectric-13921718-i7}"
expected_sha256="${ROBOLECTRIC_ANDROID_ALL_SHA256:-16f1f751643d1d3d5592008846bbdfc1e57cff15e6ec303d26584de3b6ac25ec}"
artifact="android-all-instrumented-${version}.jar"
destination="${ROBOLECTRIC_CACHE_DIR:-$PWD/.cache/robolectric}/${artifact}"
url="https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/${version}/${artifact}"

mkdir -p "$(dirname "$destination")"

if [[ -f "$destination" ]] && echo "${expected_sha256}  ${destination}" | sha256sum --check --strict --status; then
  printf 'Robolectric runtime cache verified: %s\n' "$destination"
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
printf 'Robolectric runtime cached: %s\n' "$destination"
