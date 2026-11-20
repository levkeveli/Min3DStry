import struct
import sys
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import verify_azahar  # noqa: E402


def make_test_png(width: int, height: int, row_fn) -> bytes:
    raw_rows = bytearray()
    for y in range(height):
        raw_rows.append(0)  # Filter type None
        for x in range(width):
            r, g, b = row_fn(x, y)
            raw_rows.extend((r, g, b))

    def chunk(ctype: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(ctype + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + ctype + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    idat = zlib.compress(bytes(raw_rows))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


class VerifyAzaharTests(unittest.TestCase):
    def test_patch_qt_config_preserves_bytearray_and_sets_default_false(self) -> None:
        original = (
            "[UI]\n"
            "UILayout\\geometry=@ByteArray(\\x1\\xd9)\n"
            "firstStart\\default=true\n"
            "firstStart=true\n"
            "\n"
            "[System]\n"
            "is_new_3ds\\default=true\n"
            "is_new_3ds=true\n"
        )
        patched = verify_azahar.patch_qt_config_text(
            original,
            {
                "UI": {"firstStart": "false", "confirmClose": "false"},
                "System": {"is_new_3ds": "false"},
                "Debugging": {"enable_rpc_server": "true"},
            },
        )
        self.assertIn("UILayout\\geometry=@ByteArray(\\x1\\xd9)", patched)
        self.assertIn("firstStart\\default=false", patched)
        self.assertIn("firstStart=false", patched)
        self.assertIn("confirmClose\\default=false", patched)
        self.assertIn("confirmClose=false", patched)
        self.assertIn("is_new_3ds\\default=false", patched)
        self.assertIn("is_new_3ds=false", patched)
        self.assertIn("[Debugging]\nenable_rpc_server\\default=false\nenable_rpc_server=true", patched)

    def test_parse_azahar_log_extracts_telemetry(self) -> None:
        log = (
            "[   2.94] Render.OpenGL <Info> driver.cpp:133: GL_VERSION: 4.3.0\n"
            "[   2.94] Render.OpenGL <Info> driver.cpp:135: GL_RENDERER: Intel(R) Arc(TM) Graphics\n"
            "[   3.95] Kernel.SVC <Debug> svc.cpp:100: [MIN3DSTRY] HW: New 3DS XL (N3DS, 804 MHz, L2:on, AppMem:124 MiB, FIRM:2.50.11)\n"
            "[   3.96] Debug.Emulated <Debug> svc.cpp:100: [MIN3DSTRY] PARITY scenario=starter_copper_defense_v1 ticks=240 hash=0x11223344 copper=25 delivered=14 killed=2 hp=200\n"
            "[   3.97] Debug.Emulated <Debug> svc.cpp:100: [MIN3DSTRY] AUDIO backend=ndsp state=fallback_no_dsp rc=0xd8e06406 rate=22050 voices=0/8 clips=2\n"
            "[   4.45] Kernel.SVC <Debug> svc.cpp:100: [MIN3DSTRY] FRAME tick=30 paused=0 cam=(-88.0,-72.0) "
            "draw=R:322,L:5,T:0,C:42 cull=77 sw=1 drop=0 cpu=3.12% gpu=1.45% cmdbuf=2.10%\n"
        )
        parsed = verify_azahar.parse_azahar_log(log)
        self.assertEqual(parsed["renderer"], "Intel(R) Arc(TM) Graphics")
        self.assertEqual(parsed["gl_version"], "4.3.0")
        self.assertIn("New 3DS XL (N3DS, 804 MHz", parsed["hw_line"])
        self.assertEqual(parsed["parity"]["trace_hash"], "0x11223344")
        self.assertEqual(parsed["parity"]["enemies_killed"], 2)
        self.assertEqual(parsed["audio"]["backend"], "ndsp")
        self.assertEqual(parsed["audio"]["state"], "fallback_no_dsp")
        self.assertEqual(parsed["audio"]["rc"], "0xd8e06406")
        self.assertEqual(parsed["audio"]["rate"], 22050)
        self.assertEqual(parsed["audio"]["voices"], 0)
        self.assertEqual(parsed["audio"]["max_voices"], 8)
        self.assertEqual(parsed["audio"]["clips"], 2)
        self.assertEqual(parsed["frame"]["tick"], 30)
        self.assertEqual(parsed["frame"]["rects"], 322)
        self.assertEqual(parsed["frame"]["circles"], 42)
        self.assertEqual(parsed["frame"]["state_switches"], 1)
        self.assertEqual(parsed["frame"]["budget_dropped"], 0)
        self.assertAlmostEqual(parsed["frame"]["cam_x"], -88.0)

    def test_inspect_png_distinguishes_blank_and_rendered_frames(self) -> None:
        blank = make_test_png(16, 16, lambda _x, _y: (0, 0, 0))
        blank_info = verify_azahar.inspect_png(blank)
        self.assertEqual(blank_info["top_distinct_colors"], 1)
        self.assertEqual(blank_info["bottom_distinct_colors"], 1)

        rendered = make_test_png(16, 16, lambda x, y: (x * 16, y * 16, 40))
        rendered_info = verify_azahar.inspect_png(rendered)
        self.assertGreater(rendered_info["top_distinct_colors"], 1)
        self.assertGreater(rendered_info["bottom_distinct_colors"], 1)

    def test_inspect_png_supports_4bit_indexed_palette(self) -> None:
        width, height = 8, 8
        raw_rows = bytearray()
        for y in range(height):
            raw_rows.append(0)
            for x in range(0, width, 2):
                idx0 = 0 if x < 4 else 1
                idx1 = 2 if (x + y) % 2 == 0 else 3
                raw_rows.append((idx0 << 4) | idx1)

        def chunk(ctype: bytes, data: bytes) -> bytes:
            crc = zlib.crc32(ctype + data) & 0xFFFFFFFF
            return struct.pack(">I", len(data)) + ctype + data + struct.pack(">I", crc)

        ihdr = struct.pack(">IIBBBBB", width, height, 4, 3, 0, 0, 0)
        plte = bytes([0, 0, 0, 255, 255, 255, 40, 120, 200, 220, 140, 60])
        idat = zlib.compress(bytes(raw_rows))
        png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"PLTE", plte) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")
        info = verify_azahar.inspect_png(png)
        self.assertEqual(info["width"], 8)
        self.assertEqual(info["height"], 8)
        self.assertGreater(info["top_distinct_colors"], 1)
        self.assertGreater(info["bottom_distinct_colors"], 1)

    def test_rpc_framing_and_unpackers(self) -> None:
        hdr, req_id = verify_azahar._build_header(verify_azahar.REQ_GET_PERF_STATS, 4, request_id=1234)
        payload = struct.pack("<10dI", 60.0, 59.9, 1.0, 0.1, 0.2, 0.3, 0.4, 0.5, 1.0, 0.0, 7)
        unpacked_hdr = struct.pack("<IIII", verify_azahar.RPC_VERSION, req_id, verify_azahar.REQ_GET_PERF_STATS, len(payload))
        body = verify_azahar._parse_header(unpacked_hdr + payload, req_id, verify_azahar.REQ_GET_PERF_STATS)
        self.assertIsNotNone(body)
        stats = verify_azahar._parse_perf_stats(body)
        self.assertAlmostEqual(stats["system_fps"], 60.0)
        self.assertEqual(stats["artic_events"], 7)

        plist_payload = struct.pack("<IIQ8s", 1, 42, 0x000400000F800000, b"min3dst\x00")
        entries = verify_azahar._parse_process_list_chunk(plist_payload)
        self.assertEqual(entries, [(42, 0x000400000F800000, "min3dst")])


if __name__ == "__main__":
    unittest.main()
