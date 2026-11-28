import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

TOOLS = Path(__file__).resolve().parents[2] / "tools"
sys.path.insert(0, str(TOOLS))

import verify_upstream_live_net as verifier  # noqa: E402


class WorldAtlasPixelTest(unittest.TestCase):
    def test_exact_samples_match(self):
        self.assertTrue(verifier.world_atlas_pixels_match(dict(verifier.CORE_PIXELS)))

    def test_one_pixel_shift_matches(self):
        shifted = {(x + 1, y): color for (x, y), color in verifier.CORE_PIXELS.items()}
        self.assertTrue(verifier.world_atlas_pixels_match(shifted))

    def test_vertical_shift_matches(self):
        shifted = {(x, y + 1): color for (x, y), color in verifier.CORE_PIXELS.items()}
        self.assertTrue(verifier.world_atlas_pixels_match(shifted))

    def test_missing_samples_fail(self):
        self.assertFalse(verifier.world_atlas_pixels_match({}))

    def test_wrong_neighborhood_fails(self):
        samples = dict(verifier.CORE_PIXELS)
        x, y = next(iter(verifier.CORE_PIXELS))
        for dx in range(-1, 2):
            for dy in range(-1, 2):
                samples[(x + dx, y + dy)] = (0, 0, 0)
        self.assertFalse(verifier.world_atlas_pixels_match(samples))


    def test_capture_waits_for_exact_atlas_pixels(self):
        rpc = Mock()
        rpc.screenshot.side_effect = [b"covered", b"clear"]
        layout = {"width": 400, "height": 480, "top_distinct_colors": 30}
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "spawn.png"
            with patch.object(verifier, "wait_for"), patch.object(verifier.time, "sleep"), \
                    patch.object(verifier.az, "inspect_png", side_effect=[
                        {**layout, "samples": {}},
                        {**layout, "samples": dict(verifier.CORE_PIXELS)},
                    ]):
                verifier.capture_viewport(None, None, rpc, output, (761, 0, 648.0, 208.0),
                                          require_world_atlas=True)
            self.assertEqual(output.read_bytes(), b"clear")
        self.assertEqual(rpc.screenshot.call_count, 2)


if __name__ == "__main__":
    unittest.main()
