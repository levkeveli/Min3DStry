import hashlib
import io
import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
from contextlib import redirect_stdout
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
            "[MIN3DSTRY] FRAME tick=30 paused=0 cam=(88.0,72.0) "
            "draw=R:456,L:4,T:17,C:30 cull=456 sw=1 drop=0 cpu=12.40% gpu=8.10% cmdbuf=0.26%\n"
        )
        parsed = device_deploy.parse_device_log(sample_log)
        self.assertIn("Original 3DS (O3DS, 268 MHz", parsed["hw_line"])
        self.assertEqual(parsed["parity"]["trace_hash"], "0x53a483c7")
        self.assertEqual(parsed["audio"]["state"], "ready")
        self.assertEqual(parsed["frame"]["rects"], 456)

        md = device_deploy.format_provenance_markdown(parsed, commit_sha="cc20ad3", target_label="Old 3DS")
        self.assertIn("Old 3DS", md)
        self.assertIn("0x53a483c7", md)
        self.assertIn("backend=ndsp state=ready", md)
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

    def test_parse_and_evaluate_hardware_budget_old_and_new_3ds(self) -> None:
        old3ds_log = (
            "[MIN3DSTRY] HW: Original 3DS (O3DS, 268 MHz, L2:off, AppMem:64 MiB, FIRM:11.17.0)\n"
            "[MIN3DSTRY] PARITY scenario=starter_copper_defense_v1 ticks=240 "
            "hash=0x53a483c7 copper=22 delivered=12 killed=2 hp=160\n"
            "[MIN3DSTRY] MEM app=64MiB reserve=16MiB budget=1048576 used=553728 peak=553728 "
            "remaining=494848 world=480000/524288 textures=8192/262144 transient=65536/262144 failures=0 status=ok\n"
            "UPSTREAM_MEMORY appHeap=40000000 linearHeap=4194304 stack=262144\n"
            "[MIN3DSTRY] BENCH scenario=glacier fps=28.50 avg_ms=34.80 p95_ms=41.20\n"
        )
        parsed = device_deploy.parse_device_log(old3ds_log)
        self.assertEqual(parsed["app_mem_mib"], 64)
        self.assertEqual(parsed["upstream_memory"]["app_heap_bytes"], 40000000)
        self.assertEqual(parsed["upstream_memory"]["linear_heap_bytes"], 4194304)
        self.assertEqual(parsed["upstream_memory"]["stack_bytes"], 262144)
        self.assertAlmostEqual(parsed["bench"]["fps"], 28.50)
        self.assertAlmostEqual(parsed["bench"]["p95_ms"], 41.20)
        self.assertEqual(parsed["bench"]["scenario"], "glacier")

        ok_old = device_deploy.evaluate_hardware_budget(
            parsed,
            profile="old3ds",
            min_fps=24.0,
            expected_hash="0x53a483c7",
            load_bytes=16596992,
        )
        self.assertTrue(ok_old["passed"])
        for check in ok_old["checks"].values():
            self.assertEqual(check["status"], "pass")

        new3ds_log = old3ds_log.replace(
            "Original 3DS (O3DS, 268 MHz, L2:off, AppMem:64 MiB",
            "New 3DS XL (N3DS, 804 MHz, L2:on, AppMem:124 MiB",
        )
        ok_new = device_deploy.evaluate_hardware_budget(
            device_deploy.parse_device_log(new3ds_log),
            profile="new3ds",
            min_fps=24.0,
            expected_hash="0x53a483c7",
            load_bytes=16596992,
        )
        self.assertTrue(ok_new["passed"])

        reported_log = old3ds_log + "UPSTREAM_MEMORY managedLimit=33554432\n"
        reported = device_deploy.parse_device_log(reported_log)
        self.assertEqual(reported["upstream_memory"]["managed_limit_bytes"], 32 * 1024 * 1024)
        footprint = device_deploy.evaluate_hardware_budget(
            reported, profile="old3ds", load_bytes=34 * 1024 * 1024, min_fps=24.0
        )["checks"]["upstream_memory_footprint"]
        self.assertEqual(footprint["status"], "fail")
        self.assertEqual(footprint["managed_limit_bytes"], 32 * 1024 * 1024)

    def test_evaluate_hardware_budget_detects_failures(self) -> None:
        bad_log = (
            "[MIN3DSTRY] HW: New 3DS XL (N3DS, 804 MHz, L2:on, AppMem:124 MiB, FIRM:11.17.0)\n"
            "[MIN3DSTRY] PARITY scenario=starter_copper_defense_v1 ticks=240 "
            "hash=0xdeadbeef copper=22 delivered=12 killed=2 hp=160\n"
            "[MIN3DSTRY] MEM app=64MiB reserve=16MiB budget=1048576 used=553728 peak=553728 "
            "remaining=494848 world=480000/524288 textures=8192/262144 transient=65536/262144 failures=2 status=oom\n"
            "UPSTREAM_MEMORY appHeap=40000000 linearHeap=4194304 stack=262144\n"
            "[MIN3DSTRY] BENCH fps=19.50 p95_ms=52.00\n"
        )
        parsed = device_deploy.parse_device_log(bad_log)
        res = device_deploy.evaluate_hardware_budget(
            parsed,
            profile="old3ds",
            min_fps=24.0,
            expected_hash="0x53a483c7",
            load_bytes=40 * 1024 * 1024,
        )
        self.assertFalse(res["passed"])
        self.assertEqual(res["checks"]["hw_profile"]["status"], "fail")
        self.assertEqual(res["checks"]["memory_budget"]["status"], "fail")
        self.assertEqual(res["checks"]["upstream_memory_footprint"]["status"], "fail")
        self.assertEqual(res["checks"]["frame_rate"]["status"], "fail")
        self.assertEqual(res["checks"]["parity_hash"]["status"], "fail")

    def test_benchmark_report_cli_json_and_strict(self) -> None:
        sample_log = (
            "[MIN3DSTRY] HW: Original 3DS (O3DS, 268 MHz, L2:off, AppMem:64 MiB, FIRM:11.17.0)\n"
            "[MIN3DSTRY] PARITY scenario=starter_copper_defense_v1 ticks=240 "
            "hash=0x53a483c7 copper=22 delivered=12 killed=2 hp=160\n"
            "[MIN3DSTRY] BENCH fps=25.00 p95_ms=39.80\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            log_file = Path(tmp) / "hw.log"
            log_file.write_text(sample_log, encoding="utf-8")

            parser = device_deploy.build_parser()
            args_json = parser.parse_args(
                [
                    "benchmark-report",
                    "--input",
                    str(log_file),
                    "--profile",
                    "old3ds",
                    "--expected-hash",
                    "0x53a483c7",
                    "--format",
                    "json",
                    "--strict",
                ]
            )
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = args_json.func(args_json)
            self.assertEqual(rc, 0)
            payload = json.loads(buf.getvalue())
            self.assertTrue(payload["evaluation"]["passed"])
            self.assertEqual(payload["evaluation"]["checks"]["frame_rate"]["status"], "pass")

            args_fail = parser.parse_args(
                [
                    "benchmark-report",
                    "--input",
                    str(log_file),
                    "--profile",
                    "old3ds",
                    "--min-fps",
                    "30.0",
                    "--format",
                    "markdown",
                    "--strict",
                ]
            )
            md_buf = io.StringIO()
            with redirect_stdout(md_buf):
                rc_fail = args_fail.func(args_fail)
            self.assertEqual(rc_fail, 1)
            self.assertIn("`FAIL`", md_buf.getvalue())
            self.assertIn("| `frame_rate` | `FAIL` |", md_buf.getvalue())


class StreamingCaptureTests(unittest.TestCase):
    def test_streams_lines_and_enforces_timeout(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "capture.log"
            code = (
                "import time\n"
                "for i in range(3):\n"
                "    print('line', i, flush=True)\n"
                "    time.sleep(0.1)\n"
                "time.sleep(5)\n"
            )
            started = time.monotonic()
            code_rc = device_deploy.run_streaming(
                [sys.executable, "-c", code], str(log), max_seconds=0.6
            )
            elapsed = time.monotonic() - started
            text = log.read_text(encoding="utf-8")
            self.assertIn("line 0", text)
            self.assertIn("line 2", text)
            self.assertLess(elapsed, 4.0)
            self.assertIsInstance(code_rc, int)

    def test_timeout_holds_under_continuous_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "continuous.log"
            code = (
                "import time\n"
                "end = time.time() + 10\n"
                "i = 0\n"
                "while time.time() < end:\n"
                "    print('tick', i, flush=True)\n"
                "    i += 1\n"
                "    time.sleep(0.02)\n"
            )
            started = time.monotonic()
            code_rc = device_deploy.run_streaming(
                [sys.executable, "-c", code], str(log), max_seconds=0.5
            )
            elapsed = time.monotonic() - started
            self.assertEqual(code_rc, 0)
            self.assertLess(elapsed, 4.0)
            self.assertIn("tick 0", log.read_text(encoding="utf-8"))

    def test_resolve_3dslink_accepts_env_aliases_and_explicit_path(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "3dslink.exe"
            fake.write_text("", encoding="utf-8")
            saved = {
                key: os.environ.get(key)
                for key in ("THREEDSLINK_BIN", "THREESLINK_BIN")
            }
            try:
                os.environ["THREESLINK_BIN"] = str(fake)
                self.assertEqual(device_deploy.resolve_3dslink(None), str(fake))
                os.environ.pop("THREESLINK_BIN", None)
                os.environ["THREEDSLINK_BIN"] = str(fake)
                self.assertEqual(device_deploy.resolve_3dslink(None), str(fake))
                self.assertEqual(
                    device_deploy.resolve_3dslink(str(fake)), str(fake)
                )
            finally:
                for key, value in saved.items():
                    if value is None:
                        os.environ.pop(key, None)
                    else:
                        os.environ[key] = value

    def test_writes_complete_output_without_timeout(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "complete.log"
            code_rc = device_deploy.run_streaming(
                [sys.executable, "-c", "print('done', flush=True)"], str(log)
            )
            self.assertEqual(code_rc, 0)
            self.assertIn("done", log.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
