import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import verify_upstream_live_net  # noqa: E402
import verify_upstream_net  # noqa: E402


class VerifyUpstreamNetRetryTests(unittest.TestCase):
    def test_known_stock_session_races_are_transient(self) -> None:
        samples = (
            "java.lang.IllegalStateException: Unexpected Session 1 initial state: "
            "buildings=2 tile85=air tile86=snow-boulder copper=200",
            "java.lang.IllegalStateException: Unexpected Session 2 rejoin WorldStream "
            "did not preserve Session 1 state",
            "java.lang.IllegalStateException: Timed out waiting for WorldStream (run=1)",
            "java.lang.IllegalStateException: Disconnected before WorldStream: closed",
        )
        for sample in samples:
            with self.subTest(sample=sample):
                self.assertTrue(verify_upstream_net.is_transient_session_failure(sample))

    def test_real_protocol_errors_are_not_retried(self) -> None:
        samples = (
            "Missing expected session marker 'UPSTREAM_NET_PASS'",
            "Expected outdated version rejection",
            "Unexpected discovery response: map=Glacier",
            "Unexpected Session 1 post-build state: buildings=2",
        )
        for sample in samples:
            with self.subTest(sample=sample):
                self.assertFalse(verify_upstream_net.is_transient_session_failure(sample))


class VerifyUpstreamLiveControlsReplayTests(unittest.TestCase):
    def test_new3ds_unified_start_spans_slow_hid_polls(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            movie = Path(tmp) / "controls.ctm"
            verify_upstream_live_net.write_controls_replay(
                movie, first_move=42, model="new3ds", unified_boot=True,
            )
            replay = movie.read_bytes()

        samples_per_second = 234
        start_button = 1 << 3
        for frame in range(97 * samples_per_second, 104 * samples_per_second):
            extra_samples = 3 * (frame // 7) + sum(index < frame % 7 for index in (0, 2, 4))
            offset = 256 + 14 * frame + 7 * extra_samples
            buttons = struct.unpack_from("<H", replay, offset + 1)[0]
            expected = start_button if 98 * samples_per_second <= frame < 103 * samples_per_second else 0
            self.assertEqual(buttons, expected, f"CTM sample {frame}")


if __name__ == "__main__":
    unittest.main()
