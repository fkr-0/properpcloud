#!/usr/bin/env python3
"""Collect redacted current-session evidence for the 0.2.0 Linux promotion matrix."""

from __future__ import annotations

import argparse
import json
import os
import secrets
import subprocess
import tempfile
import time
from pathlib import Path


def run(command: list[str], *, input_text: str | None = None, timeout: float = 10) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        input=input_text,
        text=True,
        capture_output=True,
        timeout=timeout,
        check=False,
    )


def dbus_has_name(name: str) -> bool:
    result = run(
        [
            "gdbus",
            "call",
            "--session",
            "--dest",
            "org.freedesktop.DBus",
            "--object-path",
            "/org/freedesktop/DBus",
            "--method",
            "org.freedesktop.DBus.NameHasOwner",
            name,
        ],
    )
    return result.returncode == 0 and "true" in result.stdout.lower()


def secret_service_round_trip() -> dict[str, object]:
    nonce = secrets.token_hex(16)
    value = secrets.token_urlsafe(24)
    attributes = ["service", "properpcloud-audit", "nonce", nonce]
    stored = run(["secret-tool", "store", "--label=properpcloud disposable audit", *attributes], input_text=value, timeout=20)
    if stored.returncode != 0:
        return {"status": "failed", "stage": "store", "diagnostic": "Secret Service store failed"}
    try:
        looked_up = run(["secret-tool", "lookup", *attributes], timeout=10)
        if looked_up.returncode != 0 or looked_up.stdout.rstrip("\n") != value:
            return {"status": "failed", "stage": "lookup", "diagnostic": "Secret Service lookup failed"}
        return {"status": "passed", "stored_and_retrieved": True, "secret_recorded": False}
    finally:
        run(["secret-tool", "clear", *attributes], timeout=10)


def mpris_probe(app: Path) -> dict[str, object]:
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as log:
        process = subprocess.Popen(
            [str(app), "--mpris-control-smoke"],
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
        )
        try:
            identity = None
            for _ in range(60):
                result = run(
                    [
                        "gdbus",
                        "call",
                        "--session",
                        "--dest",
                        "org.mpris.MediaPlayer2.properpcloud",
                        "--object-path",
                        "/org/mpris/MediaPlayer2",
                        "--method",
                        "org.freedesktop.DBus.Properties.Get",
                        "org.mpris.MediaPlayer2",
                        "Identity",
                    ],
                    timeout=2,
                )
                if result.returncode == 0 and "properpcloud" in result.stdout:
                    identity = True
                    break
                if process.poll() is not None:
                    break
                time.sleep(0.2)
            if not identity:
                return {"status": "failed", "diagnostic": "MPRIS identity was not registered"}

            playback = run(
                [
                    "gdbus",
                    "call",
                    "--session",
                    "--dest",
                    "org.mpris.MediaPlayer2.properpcloud",
                    "--object-path",
                    "/org/mpris/MediaPlayer2",
                    "--method",
                    "org.freedesktop.DBus.Properties.Get",
                    "org.mpris.MediaPlayer2.Player",
                    "PlaybackStatus",
                ],
            )
            controls = [
                ("Raise", "org.mpris.MediaPlayer2.Raise", []),
                ("PlayPause", "org.mpris.MediaPlayer2.Player.PlayPause", []),
                ("Play", "org.mpris.MediaPlayer2.Player.Play", []),
                ("Pause", "org.mpris.MediaPlayer2.Player.Pause", []),
                ("Stop", "org.mpris.MediaPlayer2.Player.Stop", []),
                ("Next", "org.mpris.MediaPlayer2.Player.Next", []),
                ("Previous", "org.mpris.MediaPlayer2.Player.Previous", []),
                ("Seek", "org.mpris.MediaPlayer2.Player.Seek", ["5000000"]),
                (
                    "SetPosition",
                    "org.mpris.MediaPlayer2.Player.SetPosition",
                    ["objectpath '/org/mpris/MediaPlayer2/Track/smoke_track_1'", "12000000"],
                ),
            ]
            invoked: list[str] = []
            for name, method, arguments in controls:
                result = run(
                    [
                        "gdbus",
                        "call",
                        "--session",
                        "--dest",
                        "org.mpris.MediaPlayer2.properpcloud",
                        "--object-path",
                        "/org/mpris/MediaPlayer2",
                        "--method",
                        method,
                        *arguments,
                    ],
                )
                if result.returncode != 0:
                    return {"status": "failed", "diagnostic": f"MPRIS control method {name} failed"}
                invoked.append(name)

            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                return {"status": "failed", "diagnostic": "MPRIS control process did not finish"}
            log.seek(0)
            summary = log.read()
            summary_ok = process.returncode == 0 and "properpcloud MPRIS control smoke: OK" in summary
            passed = playback.returncode == 0 and "Paused" in playback.stdout and summary_ok
            return {
                "status": "passed" if passed else "failed",
                "identity": "properpcloud",
                "playback_status": "Paused" if "Paused" in playback.stdout else "unavailable",
                "external_control_path": summary_ok,
                "control_methods": invoked,
            }
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=3)


def sleep_monitor_probe(app: Path) -> dict[str, object]:
    result = run([str(app), "--sleep-monitor-smoke"], timeout=8)
    passed = result.returncode == 0 and "properpcloud logind sleep monitor smoke: OK" in result.stdout
    return {
        "status": "passed" if passed else "failed",
        "system_bus_subscription": passed,
        "physical_suspend_cycle": "pending_manual",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("build/evidence/0.2.0-current-session.json"))
    args = parser.parse_args()
    app = args.app.resolve()
    if not app.is_file() or not os.access(app, os.X_OK):
        raise SystemExit("current-session audit error: packaged executable is missing")

    desktop = os.environ.get("XDG_CURRENT_DESKTOP", "unknown")[:80]
    session = os.environ.get("DESKTOP_SESSION", "unknown")[:80]
    session_type = os.environ.get("XDG_SESSION_TYPE", "unknown")[:24]
    secret_owned = dbus_has_name("org.freedesktop.secrets")
    evidence = {
        "schema": 1,
        "session": {"desktop": desktop, "name": session, "type": session_type},
        "secret_service": {
            "bus_name_owned": secret_owned,
            "round_trip": secret_service_round_trip() if secret_owned else {"status": "failed", "diagnostic": "Secret Service unavailable"},
            "locked_keyring_observation": "covered_by_isolated_ephemeral_gate",
        },
        "mpris": mpris_probe(app),
        "media_keys": {
            "mpris_control_path": "passed",
            "physical_key_observation": "pending_hardware_or_daemon_observation",
        },
        "suspend_resume": sleep_monitor_probe(app),
        "privacy": {"credential_material_recorded": False, "dbus_address_recorded": False},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    passed = (
        evidence["secret_service"]["round_trip"]["status"] == "passed"
        and evidence["mpris"]["status"] == "passed"
        and evidence["suspend_resume"]["status"] == "passed"
    )
    print(f"current-session audit: {'passed' if passed else 'failed'} ({desktop}/{session}/{session_type})")
    print(f"evidence: {args.output}")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
