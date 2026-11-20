import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import check_parity  # noqa: E402


class CheckParityTests(unittest.TestCase):
    def test_compare_parity_runs_detects_exact_field_divergence(self) -> None:
        ref = {
            "scenario_id": "starter_copper_defense_v1",
            "total_ticks": 240,
            "trace_hash": "0x1234abcd",
            "checkpoints": [
                {"tick": 0, "core_copper": 40, "state_hash": "0xaaaaaaaa"},
                {"tick": 240, "core_copper": 55, "state_hash": "0xbbbbbbbb"},
            ],
        }
        self.assertEqual(check_parity.compare_parity_runs(ref, ref, label="o2"), [])

        bad = {
            "scenario_id": "starter_copper_defense_v1",
            "total_ticks": 240,
            "trace_hash": "0xdeadbeef",
            "checkpoints": [
                {"tick": 0, "core_copper": 40, "state_hash": "0xaaaaaaaa"},
                {"tick": 240, "core_copper": 54, "state_hash": "0xcccccccc"},
            ],
        }
        errs = check_parity.compare_parity_runs(ref, bad, label="o2")
        self.assertTrue(any("trace_hash" in e for e in errs))
        self.assertTrue(any("tick 240: field 'core_copper'" in e for e in errs))

    def test_parse_parity_debug_line(self) -> None:
        line = (
            "[   3.10] Debug.Emulated <Debug> svc.cpp:100: [MIN3DSTRY] PARITY "
            "scenario=starter_copper_defense_v1 ticks=240 hash=0x0a1b2c3d "
            "copper=25 delivered=14 killed=3 hp=180\n"
        )
        parsed = check_parity.parse_parity_debug_line(line)
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["scenario_id"], "starter_copper_defense_v1")
        self.assertEqual(parsed["total_ticks"], 240)
        self.assertEqual(parsed["trace_hash"], "0x0a1b2c3d")
        self.assertEqual(parsed["core_copper"], 25)
        self.assertEqual(parsed["items_delivered"], 14)
        self.assertEqual(parsed["enemies_killed"], 3)
        self.assertEqual(parsed["core_hp"], 180)


if __name__ == "__main__":
    unittest.main()
