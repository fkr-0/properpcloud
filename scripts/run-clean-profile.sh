#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: run-clean-profile.sh COMMAND [ARG...]" >&2
  exit 64
fi

profile_root=$(mktemp -d "${TMPDIR:-/tmp}/properpcloud-clean-profile.XXXXXX")
cleanup() {
  rm -rf "$profile_root"
}
trap cleanup EXIT

mkdir -p \
  "$profile_root/home" \
  "$profile_root/config" \
  "$profile_root/data" \
  "$profile_root/cache" \
  "$profile_root/state" \
  "$profile_root/tmp"
chmod 0700 "$profile_root" "$profile_root"/*

export HOME="$profile_root/home"
export XDG_CONFIG_HOME="$profile_root/config"
export XDG_DATA_HOME="$profile_root/data"
export XDG_CACHE_HOME="$profile_root/cache"
export XDG_STATE_HOME="$profile_root/state"
export TMPDIR="$profile_root/tmp"
export GSETTINGS_BACKEND=memory
export PROPERPCLOUD_CLEAN_PROFILE=1

set +e
"$@"
status=$?
set -e
exit "$status"
