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
  --env ORG_GRADLE_PROJECT_requireReleaseSigning="${ORG_GRADLE_PROJECT_requireReleaseSigning:-}" \
  --env ANDROID_KEYSTORE_FILE="${ANDROID_KEYSTORE_FILE:-}" \
  --env ANDROID_KEYSTORE_PASSWORD="${ANDROID_KEYSTORE_PASSWORD:-}" \
  --env ANDROID_KEY_ALIAS="${ANDROID_KEY_ALIAS:-}" \
  --env ANDROID_KEY_PASSWORD="${ANDROID_KEY_PASSWORD:-}" \
  --volume "$PWD:/workspace" \
  --volume "$cache_dir:/gradle-cache" \
  --workdir /workspace \
  "$image" \
  "$@"
