#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

skip() {
  local message=$1
  if [[ ${MPV_SMOKE_REQUIRE_BASELINE:-0} == 1 ]]; then
    fail "$message (baseline codec/tool is required in CI)"
  fi
  printf 'SKIP: %s\n' "$message" >&2
  exit 77
}

command -v mpv >/dev/null 2>&1 || skip "host mpv is not installed"
command -v ffmpeg >/dev/null 2>&1 || skip "ffmpeg is not installed"
command -v python3 >/dev/null 2>&1 || skip "python3 is not installed"

mpv_version=$(mpv --no-config --version | sed -n '1p')
if ! { mpv --no-config --ad=help 2>&1 || true; } | grep -Eiq 'opus'; then
  skip "host mpv does not expose an Opus audio decoder"
fi
if ! { ffmpeg -hide_banner -encoders 2>/dev/null || true; } | grep -qE '[[:space:]]libopus[[:space:]]'; then
  skip "ffmpeg does not provide the libopus fixture encoder"
fi

tmp=$(mktemp -d)
mpv_pid=
cleanup() {
  if [[ -n $mpv_pid ]] && kill -0 "$mpv_pid" 2>/dev/null; then
    kill "$mpv_pid" 2>/dev/null || true
    wait "$mpv_pid" 2>/dev/null || true
  fi
  rm -rf "$tmp"
}
trap cleanup EXIT INT TERM

make_fixture() {
  local output=$1 format=$2
  ffmpeg -hide_banner -loglevel error -nostdin -y \
    -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=3.5' \
    -map 0:a:0 -vn -map_metadata -1 -c:a libopus -b:a 64k -vbr off \
    -application audio -fflags +bitexact -f "$format" "$output" || fail "could not generate deterministic Opus fixture ($format)"
}

make_fixture "$tmp/audio.webm" webm
make_fixture "$tmp/audio.mka" matroska
make_fixture "$tmp/audio.mkv" matroska
ffmpeg -hide_banner -loglevel error -nostdin -y \
  -f lavfi -i 'color=c=black:s=64x64:r=10:d=3.5' \
  -f lavfi -i 'sine=frequency=880:sample_rate=48000:duration=3.5' \
  -map 0:v:0 -map 1:a:0 -c:v ffv1 -c:a libopus -b:a 64k -application audio \
  -shortest -fflags +bitexact -f matroska "$tmp/audio-video.mkv" \
  || fail "could not generate audio+video Matroska fixture"
video_codec=$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of csv=p=0 "$tmp/audio-video.mkv")
[[ -n "$video_codec" ]] || fail "audio+video fixture has no video stream"

mpv --no-config --idle=yes --terminal=no --ao=null --vid=no \
  --audio-display=no --force-window=no --pause=yes --keep-open=no \
  --input-ipc-server="$tmp/mpv.sock" >"$tmp/mpv.log" 2>&1 &
mpv_pid=$!

for _ in $(seq 1 150); do
  [[ -S "$tmp/mpv.sock" ]] && break
  kill -0 "$mpv_pid" 2>/dev/null || fail "host mpv exited before IPC became ready"
  sleep 0.02
done
[[ -S "$tmp/mpv.sock" ]] || fail "host mpv IPC socket did not become ready"

python3 - "$tmp/mpv.sock" "$tmp/audio.webm" "$tmp/audio.mka" "$tmp/audio.mkv" "$tmp/audio-video.mkv" "$mpv_version" <<'PY'
import json
import socket
import sys
import time

socket_path, *fixtures, version = sys.argv[1:]
client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
client.settimeout(0.25)
client.connect(socket_path)
buffer = b""
messages = []
request_id = 0

def receive_message(deadline):
    global buffer
    while time.monotonic() < deadline:
        if b"\n" in buffer:
            raw, buffer = buffer.split(b"\n", 1)
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                continue
        try:
            chunk = client.recv(16384)
        except socket.timeout:
            continue
        if not chunk:
            raise RuntimeError("mpv IPC closed")
        buffer += chunk
        if len(buffer) > 131072:
            raise RuntimeError("mpv IPC response exceeded limit")
    raise TimeoutError("timed out waiting for mpv IPC message")

def command(args, timeout=8):
    global request_id
    request_id += 1
    current_id = request_id
    client.sendall((json.dumps({"command": args, "request_id": current_id}) + "\n").encode())
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        message = receive_message(deadline)
        if message.get("request_id") == current_id:
            if message.get("error") != "success":
                raise RuntimeError("mpv IPC command failed")
            return message.get("data")
        if "event" in message:
            messages.append(message)
    raise TimeoutError("timed out waiting for mpv command response")

def wait_event(event_name, timeout=12, predicate=lambda item: True):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for index, message in enumerate(messages):
            if message.get("event") == event_name and predicate(message):
                return messages.pop(index)
        message = receive_message(deadline)
        if "event" in message:
            messages.append(message)
    raise TimeoutError("timed out waiting for " + event_name)

try:
    print("host mpv:", version)
    for fixture in fixtures:
        basename = fixture.rsplit("/", 1)[-1]
        command(["loadfile", fixture, "replace"])
        wait_event("start-file")
        wait_event("file-loaded")
        tracks = command(["get_property", "track-list"])
        audio_tracks = [
            track for track in (tracks or [])
            if isinstance(track, dict) and track.get("type") == "audio"
        ]
        if not audio_tracks:
            raise RuntimeError(basename + ": no audio track discovered")
        if basename == "audio-video.mkv":
            selected_video = [
                track for track in (tracks or [])
                if isinstance(track, dict) and track.get("type") == "video" and track.get("selected") is True
            ]
            if selected_video:
                raise RuntimeError("audio+video container unexpectedly selected a video track")
        duration = command(["get_property", "duration"])
        if not isinstance(duration, (int, float)) or duration < 3:
            raise RuntimeError(basename + ": duration is missing or unstable")
        command(["seek", 1.0, "absolute+exact"])
        time.sleep(0.15)
        position = command(["get_property", "time-pos"])
        if not isinstance(position, (int, float)) or not 0.5 <= position <= 2.0:
            raise RuntimeError(basename + ": resume seek did not land near one second")
        command(["set_property", "pause", False])
        ended = wait_event("end-file", timeout=12, predicate=lambda item: item.get("reason") == "eof")
        if ended.get("reason") != "eof":
            raise RuntimeError(basename + ": completion reason was not EOF")
        print("PASS:", basename, "audio tracks=", len(audio_tracks), "duration=", round(duration, 3), "s", "seek=", round(position, 3), "s", "completion=eof")
    print("PASS: WebM and Matroska audio plus incidental-video suppression load, track discovery, duration, resume seek and null-output completion")
finally:
    try:
        command(["quit"], timeout=1)
    except Exception:
        pass
    client.close()
PY
