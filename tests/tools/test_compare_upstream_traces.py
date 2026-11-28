import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from compare_upstream_traces import compare, read_checkpoints


class CompareUpstreamTracesTest(unittest.TestCase):
    def test_exact_order_count_and_empty_input(self):
        self.assertTrue(compare(["a", "b"], ["a", "b"])["passed"])
        self.assertFalse(compare([], [])["passed"])
        result = compare(["a", "b"], ["b"])
        self.assertEqual(result["matched"], 0)
        self.assertEqual(result["differences"][1]["reference"], "b")
        self.assertIsNone(result["differences"][1]["port"])

    def test_ignores_shell_commands_in_job_logs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "probe.log"
            path.write_text(
                "job\tstep\t2026-11-25T10:50:00Z grep 'UPSTREAM_TICK n=1'\n"
                "job\tstep\t2026-11-25T10:50:01Z UPSTREAM_TICK n=1\n"
                "UPSTREAM_TICK n=2\n", encoding="utf-8",
            )
            self.assertEqual(read_checkpoints(path, re.compile("UPSTREAM_TICK ")),
                             ["UPSTREAM_TICK n=1", "UPSTREAM_TICK n=2"])
