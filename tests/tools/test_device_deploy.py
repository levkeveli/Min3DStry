import hashlib
import json
import os
import socket
import sys
import tempfile
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import device_deploy  # noqa: E402


class DeviceDeployTests(unittest.TestCase):
    def test_build_3dslink_command_flags_and_env(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            rom = Path(tmp) / "min3dstry.3dsx"
            rom.write_bytes(b"3DSX")
            old_env = os.environ.get("M3DS_DEVICE_IP")
            try:
                os.environ["M3DS_DEVICE_IP"] = "192.0.2.55"
                cmd = device_deploy.build_3dslink_command(
                    rom, None, retries=5, stream_logs=True, tool_bin="3dslink"
                )
                self.assertEqual(cmd[:6], ["3dslink", "-a", "192.0.2.55", "-r", "5", "-s"])
                self.assertTrue(cmd[6].endswith("min3dstry.3dsx"))
                self.assertEqual(cmd[7:], ["m3ds-stream-logs"])

                os.environ.pop("M3DS_DEVICE_IP", None)
                with self.assertRaises(ValueError):
                    device_deploy.build_3dslink_command(rom, None, tool_bin="3dslink")
            finally:
                if old_env is not None:
                    os.environ["M3DS_DEVICE_IP"] = old_env
                else:
                    os.environ.pop("M3DS_DEVICE_IP", None)

    def test_stage_to_sd_writes_files_and_valid_sha256_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rom = root / "min3dstry.3dsx"
            smdh = root / "min3dstry.smdh"
            rom.write_bytes(b"ROMDATA_12345")
            smdh.write_bytes(b"SMDH_67890")
            sd = root / "sd"

            res = device_deploy.stage_to_sd(rom, sd, smdh_path=smdh, commit_sha="cc20ad3")
            staged_rom = sd / "3ds" / "min3dstry" / "min3dstry.3dsx"
            staged_smdh = sd / "3ds" / "min3dstry" / "min3dstry.smdh"
            manifest_file = Path(res["manifest_path"])

            self.assertTrue(staged_rom.is_file())
            self.assertTrue(staged_smdh.is_file())
            self.assertTrue(manifest_file.is_file())
            manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
            self.assertEqual(manifest["commit_sha"], "cc20ad3")
            by_name = {entry["name"]: entry for entry in manifest["files"]}
            self.assertEqual(by_name["min3dstry.3dsx"]["sha256"], hashlib.sha256(b"ROMDATA_12345").hexdigest())
            self.assertEqual(by_name["min3dstry.smdh"]["sha256"], hashlib.sha256(b"SMDH_67890").hexdigest())

    def test_parse_device_log_and_format_provenance(self) -> None:
        sample_log = (
            "[MIN3DSTRY] HW: Original 3DS (O3DS, 268 MHz, L2:off, AppMem:64 MiB, FIRM:11.17.0)\n"
            "[MIN3DSTRY] PARITY scenario=starter_copper_defense_v1 ticks=240 "
            "hash=0x53a483c7 copper=22 delivered=12 killed=2 hp=160\n"
            "[MIN3DSTRY] AUDIO backend=ndsp state=ready rc=0x00000000 rate=22050 voices=0/8 clips=2\n"
            "[MIN3DSTRY] UI block=conveyor rot=0 mode=build cursor=(8,4) buttons=10\n"
            "[MIN3DSTRY] FRAME tick=30 paused=0 cam=(88.0,72.0) "
            "draw=R:456,L:4,T:17,C:30 cull=456 sw=1 drop=0 cpu=12.40% gpu=8.10% cmdbuf=0.26%\n"
        )
        parsed = device_deploy.parse_device_log(sample_log)
        self.assertIn("Original 3DS (O3DS, 268 MHz", parsed["hw_line"])
        self.assertEqual(parsed["parity"]["trace_hash"], "0x53a483c7")
        self.assertEqual(parsed["audio"]["state"], "ready")
        self.assertEqual(parsed["ui"]["buttons"], 10)
        self.assertEqual(parsed["frame"]["rects"], 456)

        md = device_deploy.format_provenance_markdown(parsed, commit_sha="cc20ad3", target_label="Old 3DS")
        self.assertIn("Old 3DS", md)
        self.assertIn("0x53a483c7", md)
        self.assertIn("backend=ndsp state=ready", md)
        self.assertIn("block=conveyor", md)
        self.assertIn("R:456", md)

    def test_tcp_log_capture_loopback(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out_log = Path(tmp) / "capture.log"
            ready = threading.Event()
            port_holder: list[int] = []
            result_holder: list[tuple[int, list[str]]] = []

            def server_thread() -> None:
                result_holder.append(
                    device_deploy.capture_log_tcp(
                        "127.0.0.1",
                        0,
                        out_log,
                        timeout=5.0,
                        max_lines=10,
                        ready_event=ready,
                        port_holder=port_holder,
                    )
                )

            t = threading.Thread(target=server_thread, daemon=True)
            t.start()
            self.assertTrue(ready.wait(timeout=3.0))
            port = port_holder[0]

            with socket.create_connection(("127.0.0.1", port), timeout=3.0) as sock:
                sock.sendall(
                    b"[MIN3DSTRY] HW: New 3DS (N3DS, 804 MHz, L2:on, AppMem:96 MiB, FIRM:11.17.0)\n"
                    b"[MIN3DSTRY] PARITY scenario=starter_copper_defense_v1 ticks=240 "
                    b"hash=0x53a483c7 copper=22 delivered=12 killed=2 hp=160\n"
                )
            t.join(timeout=5.0)
            self.assertFalse(t.is_alive())
            self.assertEqual(len(result_holder), 1)
            _bound_port, lines = result_holder[0]
            self.assertEqual(len(lines), 2)
            parsed = device_deploy.parse_device_log(out_log.read_text(encoding="utf-8"))
            self.assertEqual(parsed["parity"]["trace_hash"], "0x53a483c7")


if __name__ == "__main__":
    unittest.main()
