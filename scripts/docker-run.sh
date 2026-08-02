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
  --volume "$PWD:/workspace" \
  --volume "$cache_dir:/gradle-cache" \
  --workdir /workspace \
  "$image" \
  "$@"
