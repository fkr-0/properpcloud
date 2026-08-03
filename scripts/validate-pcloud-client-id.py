#!/usr/bin/env python3
"""Validate an optional public pCloud OAuth client ID for release builds."""

from __future__ import annotations

import os
import sys


def main() -> int:
    client_id = os.environ.get("PCLOUD_CLIENT_ID", "").strip()
    if not client_id:
        print(
            "release: PCLOUD_CLIENT_ID is absent; bundled OAuth is disabled and the clearly labelled fallback direct-login path remains available",
        )
        return 0
    if len(client_id) > 512 or any(character.isspace() or ord(character) < 32 for character in client_id):
        print("release error: PCLOUD_CLIENT_ID has an invalid format", file=sys.stderr)
        return 1
    print("release: public pCloud OAuth application client ID configured")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
