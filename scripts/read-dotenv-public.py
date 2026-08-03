#!/usr/bin/env python3
"""Read only properpcloud's public OAuth client ID from local configuration."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shlex
import sys


PUBLIC_KEY = "PCLOUD_CLIENT_ID"


class DotenvConfigurationError(ValueError):
    """Raised when the public dotenv configuration is ambiguous or malformed."""


def validate_public_client_id(value: str) -> str:
    client_id = value.strip()
    if not client_id:
        return ""
    if len(client_id) > 512:
        raise DotenvConfigurationError("PCLOUD_CLIENT_ID exceeds 512 characters")
    if any(character.isspace() or ord(character) < 32 or ord(character) == 127 for character in client_id):
        raise DotenvConfigurationError("PCLOUD_CLIENT_ID contains whitespace or control characters")
    return client_id


def parse_public_client_id(text: str) -> str:
    """Parse exactly PCLOUD_CLIENT_ID while ignoring every other dotenv key."""

    found: str | None = None
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line.removeprefix("export ").lstrip()
        if "=" not in line:
            continue
        key, raw_value = line.split("=", 1)
        if key.strip() != PUBLIC_KEY:
            continue
        if found is not None:
            raise DotenvConfigurationError(f"duplicate PCLOUD_CLIENT_ID at line {line_number}")
        try:
            lexer = shlex.shlex(raw_value, posix=True)
            lexer.whitespace_split = True
            lexer.commenters = "#"
            tokens = list(lexer)
        except ValueError as error:
            raise DotenvConfigurationError(
                f"malformed PCLOUD_CLIENT_ID quoting at line {line_number}",
            ) from error
        if len(tokens) > 1:
            raise DotenvConfigurationError(
                f"PCLOUD_CLIENT_ID has trailing content at line {line_number}",
            )
        found = validate_public_client_id(tokens[0] if tokens else "")
    return found or ""


def resolve_public_client_id(path: Path, environment: dict[str, str] | None = None) -> str:
    environment = os.environ if environment is None else environment
    explicit = environment.get(PUBLIC_KEY, "")
    if explicit.strip():
        return validate_public_client_id(explicit)
    if not path.is_file():
        return ""
    return parse_public_client_id(path.read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Resolve only the public pCloud client ID; never source or export app secrets.",
    )
    parser.add_argument("--path", type=Path, default=Path(".env"))
    parser.add_argument("--check", action="store_true", help="validate without printing the public ID")
    args = parser.parse_args(argv)
    try:
        client_id = resolve_public_client_id(args.path)
    except (DotenvConfigurationError, OSError, UnicodeError) as error:
        print(f"OAuth configuration error: {error}", file=sys.stderr)
        return 1
    if not args.check:
        print(client_id)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
