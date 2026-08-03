from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "read-dotenv-public.py"
SPEC = importlib.util.spec_from_file_location("read_dotenv_public", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReadDotenvPublicTest(unittest.TestCase):
    def test_reads_only_public_client_id_and_ignores_secret(self) -> None:
        text = "PCLOUD_CLIENT_SECRET=must-never-be-returned\nPCLOUD_CLIENT_ID=public-app-id\n"

        self.assertEqual("public-app-id", MODULE.parse_public_client_id(text))

    def test_supports_export_quoting_and_comments(self) -> None:
        text = 'export PCLOUD_CLIENT_ID="public-app-id" # local application identity\n'

        self.assertEqual("public-app-id", MODULE.parse_public_client_id(text))

    def test_explicit_environment_value_wins_over_dotenv(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            dotenv = Path(directory) / ".env"
            dotenv.write_text("PCLOUD_CLIENT_ID=file-id\n", encoding="utf-8")

            resolved = MODULE.resolve_public_client_id(
                dotenv,
                {"PCLOUD_CLIENT_ID": "environment-id"},
            )

        self.assertEqual("environment-id", resolved)

    def test_missing_dotenv_is_an_unconfigured_non_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / ".env"

            self.assertEqual("", MODULE.resolve_public_client_id(missing, {}))

    def test_duplicate_public_key_fails_closed(self) -> None:
        with self.assertRaisesRegex(MODULE.DotenvConfigurationError, "duplicate"):
            MODULE.parse_public_client_id(
                "PCLOUD_CLIENT_ID=first\nPCLOUD_CLIENT_ID=second\n",
            )

    def test_whitespace_in_client_id_fails_closed(self) -> None:
        with self.assertRaisesRegex(MODULE.DotenvConfigurationError, "whitespace"):
            MODULE.parse_public_client_id('PCLOUD_CLIENT_ID="not a client id"\n')


if __name__ == "__main__":
    unittest.main()
