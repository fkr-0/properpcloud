import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = (ROOT / "scripts" / "desktop-session-audit.py").read_text(encoding="utf-8")


class SessionAuditContractTest(unittest.TestCase):
    def test_audit_uses_disposable_secret_and_fixed_evidence_fields(self) -> None:
        self.assertIn("secrets.token_urlsafe", SCRIPT)
        self.assertIn('"secret_recorded": False', SCRIPT)
        self.assertIn('"dbus_address_recorded": False', SCRIPT)
        self.assertIn('secret-tool", "clear"', SCRIPT)
        self.assertIn("--mpris-control-smoke", SCRIPT)
        self.assertIn("SetPosition", SCRIPT)
        self.assertIn("--sleep-monitor-smoke", SCRIPT)
        self.assertIn('"physical_suspend_cycle": "pending_manual"', SCRIPT)
        self.assertNotIn("DBUS_SESSION_BUS_ADDRESS", SCRIPT)


if __name__ == "__main__":
    unittest.main()
