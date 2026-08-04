import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "desktop-session-audit.py"
SPEC = importlib.util.spec_from_file_location("desktop_session_audit", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class DesktopSessionAuditTest(unittest.TestCase):
    def test_missing_bus_name_is_false_without_exposing_address(self) -> None:
        self.assertFalse(MODULE.dbus_has_name("dev.properpcloud.DoesNotExist"))

    def test_runner_captures_bounded_output(self) -> None:
        result = MODULE.run(["python3", "-c", "print('ok')"])
        self.assertEqual(0, result.returncode)
        self.assertEqual("ok", result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
