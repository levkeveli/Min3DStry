import socket
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import hw_capture  # noqa: E402


class HwCaptureTests(unittest.TestCase):
    def test_capture_stream_writes_chunks_immediately(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "capture.log"
            server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            server.settimeout(0.2)
            port = server.getsockname()[1]
            stop = threading.Event()
            thread = hw_capture.capture_stream(server, log, 3.0, stop)
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=2) as client:
                    client.sendall(b"line one\n")
                    client.sendall(b"line two\n")
                    time.sleep(0.2)
                    self.assertIn("line one", log.read_text(encoding="utf-8"))
            finally:
                stop.set()
                thread.join(timeout=2.0)
                server.close()
            self.assertEqual(log.read_text(encoding="utf-8"), "line one\nline two\n")

    def test_missing_rom_returns_two(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            code = hw_capture.main(
                [
                    "--rom",
                    str(Path(tmp) / "missing.3dsx"),
                    "--ip",
                    "127.0.0.1",
                    "--log-out",
                    str(Path(tmp) / "out.log"),
                ]
            )
            self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
