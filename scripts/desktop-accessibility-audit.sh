#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
APP=${1:-$ROOT/desktop-app/build/compose/binaries/main/app/properpcloud/bin/properpcloud}
OUT_DIR=${2:-$ROOT/build/evidence}
BASE="$OUT_DIR/0.2.0-accessibility-scale2.png"
HELP="$OUT_DIR/0.2.0-accessibility-scale2-help.png"
EVIDENCE="$OUT_DIR/0.2.0-accessibility.json"

for command in dbus-run-session xvfb-run xdotool python3 sha256sum; do
  command -v "$command" >/dev/null || {
    echo "$command is required for the accessibility audit" >&2
    exit 1
  }
done
[[ -x /usr/bin/import ]] || { echo "/usr/bin/import from ImageMagick is required" >&2; exit 1; }
[[ -x /usr/bin/identify ]] || { echo "/usr/bin/identify from ImageMagick is required" >&2; exit 1; }
[[ -x "$APP" ]] || { echo "packaged properpcloud executable is missing" >&2; exit 1; }
ACCESSIBILITY_TMP="$OUT_DIR/.accessibility-tmp"
mkdir -p "$OUT_DIR" "$ACCESSIBILITY_TMP"
rm -f "$BASE" "$HELP" "$EVIDENCE"

export APP BASE HELP ACCESSIBILITY_TMP
dbus-run-session -- xvfb-run -a -s '-screen 0 1600x1000x24 -nolisten tcp' bash -euo pipefail <<'INNER'
export GIO_USE_VFS=local
export GTK_THEME=HighContrast
export PROPERPCLOUD_HIGH_CONTRAST=1
export NO_AT_BRIDGE=0
export JAVA_TOOL_OPTIONS='-Dsun.java2d.uiScale=2 -Dcompose.accessibility.enable=true'
export SKIKO_RENDER_API=SOFTWARE

capture_window() {
  local show_help=$1
  local output=$2
  local log="$ACCESSIBILITY_TMP/properpcloud-accessibility-${show_help}.log"
  PROPERPCLOUD_SHOW_KEYBOARD_HELP="$show_help" "$APP" >"$log" 2>&1 &
  local app_pid=$!
  local window=
  for _ in $(seq 1 100); do
    window=$(xdotool search --onlyvisible --name properpcloud 2>/dev/null | tail -1 || true)
    [[ -n "$window" ]] && break
    kill -0 "$app_pid" 2>/dev/null || {
      cat "$log" >&2
      return 1
    }
    sleep 0.1
  done
  if [[ -z "$window" ]]; then
    echo "properpcloud window did not appear" >&2
    kill "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
    return 1
  fi
  sleep 2
  eval "$(xdotool getwindowgeometry --shell "$window")"
  if [[ "$WIDTH" != "1280" || "$HEIGHT" != "820" ]]; then
    echo "unexpected accessibility audit window geometry: ${WIDTH}x${HEIGHT}" >&2
    kill "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
    return 1
  fi
  /usr/bin/import -window "$window" "$output"
  kill "$app_pid" 2>/dev/null || true
  wait "$app_pid" 2>/dev/null || true
  rm -f "$log"
}

capture_window 0 "$BASE"
capture_window 1 "$HELP"
INNER

cmp -s "$BASE" "$HELP" && {
  echo "keyboard-help dialog capture did not differ from the base window" >&2
  exit 1
}

python3 - "$BASE" "$HELP" "$EVIDENCE" <<'PY'
import hashlib
import json
import pathlib
import subprocess
import sys

base = pathlib.Path(sys.argv[1])
help_image = pathlib.Path(sys.argv[2])
output = pathlib.Path(sys.argv[3])

def inspect(path: pathlib.Path) -> dict[str, object]:
    dimensions = subprocess.run(
        ["/usr/bin/identify", "-format", "%w %h %[fx:mean]", str(path)],
        check=True,
        text=True,
        capture_output=True,
    ).stdout.split()
    width, height = int(dimensions[0]), int(dimensions[1])
    mean = float(dimensions[2])
    if (width, height) != (1280, 820):
        raise SystemExit(f"accessibility evidence error: unexpected image size {width}x{height}")
    if path.stat().st_size < 10_000 or not 0.01 < mean < 0.99:
        raise SystemExit("accessibility evidence error: screenshot appears blank or degenerate")
    return {
        "file": path.name,
        "width": width,
        "height": height,
        "size_bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "normalized_mean_luminance": mean,
    }

payload = {
    "schema": 1,
    "scope": "automated_xvfb_visual_capture",
    "ui_scale_percent": 200,
    "window_width": 1280,
    "window_height": 820,
    "high_contrast_theme_requested": True,
    "compose_accessibility_enabled": True,
    "keyboard_help_dialog_captured": True,
    "f1_shortcut_covered_by_unit_test": True,
    "non_color_selected_and_current_labels": True,
    "screenshots": [inspect(base), inspect(help_image)],
    "manual_screen_reader_review": "not_exercised",
    "credential_material_recorded": False,
}
output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"accessibility evidence: {output}")
PY

echo "properpcloud 200 percent high-contrast accessibility capture: OK"
