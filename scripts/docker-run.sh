#!/usr/bin/env bash
set -euo pipefail

image="${PROPERPCLOUD_BUILD_IMAGE:-properpcloud/android-build:2026.08}"
cache_dir="${PROPERPCLOUD_GRADLE_CACHE:-$PWD/.cache/gradle}"

mkdir -p "$cache_dir"

exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  --env HOME=/tmp/properpcloud-home \
  --env GRADLE_USER_HOME=/gradle-cache \
  --env PCLOUD_CLIENT_ID="${PCLOUD_CLIENT_ID:-}" \
  --env PROPERPCLOUD_REQUIRE_STABLE_SIGNING="${PROPERPCLOUD_REQUIRE_STABLE_SIGNING:-}" \
  --env PROPERPCLOUD_ANDROID_KEYSTORE_PATH="${PROPERPCLOUD_ANDROID_KEYSTORE_PATH:-}" \
  --env PROPERPCLOUD_ANDROID_KEYSTORE_PASSWORD="${PROPERPCLOUD_ANDROID_KEYSTORE_PASSWORD:-}" \
  --env PROPERPCLOUD_ANDROID_KEY_ALIAS="${PROPERPCLOUD_ANDROID_KEY_ALIAS:-}" \
  --env PROPERPCLOUD_ANDROID_KEY_PASSWORD="${PROPERPCLOUD_ANDROID_KEY_PASSWORD:-}" \
  --volume "$PWD:/workspace" \
  --volume "$cache_dir:/gradle-cache" \
  --workdir /workspace \
  "$image" \
  "$@"
