import struct
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import verify_upstream_singleplayer as vsp  # noqa: E402


class VerifyUpstreamSingleplayerTests(unittest.TestCase):
    def test_write_singleplayer_ctm_header_and_size(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            # The playback starts at 40s and runs for 62s.
            for model, expected_frames in (("new3ds", 102 * 234), ("old3ds", 102 * 234)):
                ctm = Path(tmp) / f"{model}.ctm"
                vsp.write_singleplayer_ctm(ctm, model)
                raw = ctm.read_bytes()
                self.assertEqual(raw[:4], b"CTM\x1b")
                header_size = 256
                self.assertGreaterEqual(len(raw), header_size + expected_frames * 14)
                frame_count = struct.unpack_from("<Q", raw, 84)[0]
                self.assertEqual(frame_count, expected_frames)

    def test_benchmark_accepts_signed_gc_counter_and_checks_timing(self) -> None:
        sample = (
            "UPSTREAM_PLAYABLE_BENCH window=1 frames=9 elapsed_ns=5315782950 "
            "fps=1.693071384714833 p95_ns=635119486 heap_free=-1955132 heap_committed=26447880\n"
            "UPSTREAM_PLAYABLE_LOAD window=1 wave=1 enemies=0 shooting_turrets=0 "
            "turret_projectiles=0 belt_items=0 camera_tile=121,49"
        )
        vsp.verify_playable_benchmark(sample)
        with self.assertRaisesRegex(AssertionError, "inconsistent FPS"):
            vsp.verify_playable_benchmark(sample.replace("fps=1.693071384714833", "fps=24"))

    def test_benchmark_sd_capture_requires_provenance_and_clean_exit(self) -> None:
        sample = (
            "UPSTREAM_PLAYABLE_CAPTURE start commit=" + "f" * 40
            + " dirty=0 new3ds=1 app_mem=100663296 firm=0x022e0000\n"
            "UPSTREAM_MEMORY appHeap=89235456 linearHeap=4194304 stack=524288\n"
            "UPSTREAM_MEMORY managedLimit=33554432\n"
            "UPSTREAM_SP_LOADED source=map map=Ground_Zero size=256x256 wave=1 copper=0\n"
            "UPSTREAM_PLAYABLE_BENCH window=1 frames=125 elapsed_ns=5000000000 "
            "fps=25.0 p95_ns=40000000 heap_free=4800000 heap_committed=26447880\n"
            "UPSTREAM_PLAYABLE_LOAD window=1 wave=2 enemies=80 shooting_turrets=14 "
            "turret_projectiles=4 belt_items=174 camera_tile=121,49\n"
            "UPSTREAM_PLAYABLE_CAPTURE end\n"
        )
        vsp.verify_playable_sd_capture("UPSTREAM_PLAYABLE_CAPTURE start interrupted\n" + sample)
        with self.assertRaisesRegex(AssertionError, "did not close"):
            vsp.verify_playable_sd_capture(sample + sample.rsplit("UPSTREAM_PLAYABLE_CAPTURE end", 1)[0])
        with self.assertRaisesRegex(AssertionError, "missing New 3DS"):
            vsp.verify_playable_sd_capture(sample.replace("new3ds=1", "new3ds=0"))

    def test_resolve_sd_save_path(self) -> None:
        log = Path("/home/user/.var/app/org.azahar_emu.Azahar/data/azahar-emu/log/azahar_log.txt")
        self.assertEqual(
            vsp.resolve_sd_save_path(log),
            Path("/home/user/.var/app/org.azahar_emu.Azahar/data/azahar-emu/sdmc/3ds/min3dstry/singleplayer-save.msav"),
        )

    def test_parse_singleplayer_log_extracts_milestones(self) -> None:
        sample = "\n".join([
            "UPSTREAM_MEMORY appHeap=111915008 linearHeap=4194304 stack=262144",
            "UPSTREAM_SP_LOADED source=map map=Ground_Zero size=256x256 wave=1 copper=0",
            "UPSTREAM_SP_READY unit=12 x=1032.0 y=440.0 copper=0 wave=1",
            "UPSTREAM_SP_MINED item=copper copper=15",
            "UPSTREAM_SP_BUILT block=conveyor x=131 y=55 rotation=0",
            "UPSTREAM_SP_MOVING unit=12 input=circle",
            "UPSTREAM_SP_PAUSE paused=true updateId=120",
            "UPSTREAM_SP_PAUSE paused=false updateId=120",
            "UPSTREAM_SP_WAVE wave=2 enemies=1",
            "UPSTREAM_SP_SAVED path=/3ds/min3dstry/singleplayer-save.msav bytes=18500 wave=2 copper=14",
            "UPSTREAM_SP_EXIT",
        ])
        parsed = vsp.parse_singleplayer_log(sample)
        self.assertEqual(parsed["loaded"]["source"], "map")
        self.assertEqual(parsed["mined"][0]["copper"], "15")
        self.assertEqual(parsed["built"][0]["block"], "conveyor")
        self.assertTrue(parsed["moving"])
        self.assertTrue(parsed["pause_true"])
        self.assertTrue(parsed["pause_false"])
        self.assertTrue(vsp.pause_milestone_reached(parsed))
        self.assertTrue(parsed["wave"])
        self.assertEqual(parsed["saved"]["bytes"], "18500")
        self.assertEqual(parsed["mem"]["appHeap"], 111915008)
        self.assertTrue(parsed["exit"])

    def test_pause_disabled_menu_milestone(self) -> None:
        parsed = vsp.parse_singleplayer_log("\n".join([
            "UPSTREAM_SP_PAUSE disabled=true",
            "UPSTREAM_PAUSE_MENU open=true selected=0 options=3",
            "UPSTREAM_PAUSE_MENU open=false",
            "UPSTREAM_SP_MINED item=copper copper=15",
        ]))
        self.assertTrue(parsed["pause_disabled"])
        self.assertTrue(parsed["pause_menu_open"])
        self.assertTrue(parsed["pause_menu_close"])
        self.assertFalse(parsed["pause_true"])
        self.assertFalse(parsed["pause_false"])
        self.assertTrue(vsp.pause_milestone_reached(parsed))

    def test_pause_menu_without_disabled_rule_is_rejected(self) -> None:
        parsed = vsp.parse_singleplayer_log("\n".join([
            "UPSTREAM_PAUSE_MENU open=true selected=0 options=3",
            "UPSTREAM_PAUSE_MENU open=false",
        ]))
        self.assertFalse(parsed["pause_disabled"])
        self.assertFalse(vsp.pause_milestone_reached(parsed))

    def test_missing_pause_milestone_is_rejected(self) -> None:
        parsed = vsp.parse_singleplayer_log("UPSTREAM_SP_MOVING unit=12 input=circle")
        self.assertFalse(vsp.pause_milestone_reached(parsed))


if __name__ == "__main__":
    unittest.main()
