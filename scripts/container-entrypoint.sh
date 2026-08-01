#!/usr/bin/env bash
set -euo pipefail

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/gradle-cache}"
export HOME="${HOME:-/tmp/properpcloud-home}"

mkdir -p "$GRADLE_USER_HOME" "$HOME"

if [[ ! -f ./gradlew || ! -f ./gradle/wrapper/gradle-wrapper.jar ]]; then
  cat >&2 <<'EOF'
The committed Gradle Wrapper is missing.
Generate and review it explicitly; container builds never mutate source to bootstrap wrappers.
EOF
  exit 66
fi

chmod +x ./gradlew

exec ./gradlew \
  --no-daemon \
  --console=plain \
  --stacktrace \
  "$@"
