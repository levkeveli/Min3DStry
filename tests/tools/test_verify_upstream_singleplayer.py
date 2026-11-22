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
            for model, expected_frames in (("new3ds", 88 * 234), ("old3ds", 88 * 234)):
                ctm = Path(tmp) / f"{model}.ctm"
                vsp.write_singleplayer_ctm(ctm, model)
                raw = ctm.read_bytes()
                self.assertEqual(raw[:4], b"CTM\x1b")
                header_size = 256
                self.assertGreaterEqual(len(raw), header_size + expected_frames * 14)
                frame_count = struct.unpack_from("<Q", raw, 84)[0]
                self.assertEqual(frame_count, expected_frames)

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
        self.assertTrue(parsed["wave"])
        self.assertEqual(parsed["saved"]["bytes"], "18500")
        self.assertEqual(parsed["mem"]["appHeap"], 111915008)
        self.assertTrue(parsed["exit"])


if __name__ == "__main__":
    unittest.main()
