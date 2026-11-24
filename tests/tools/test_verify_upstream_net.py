import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
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


if __name__ == "__main__":
    unittest.main()
