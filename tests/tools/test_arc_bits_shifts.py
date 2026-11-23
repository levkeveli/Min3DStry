import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "research/upstream-runtime/arm11"))
from prepare import fix_arc_bit_shifts


class ArcBitsShiftTests(unittest.TestCase):
    def test_restores_java_long_shift_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bits = root / "c/a/s/Bits.c"
            bits.parent.mkdir(parents=True)
            bits.write_text(
                """
                bit = (INT64_C(1) << teavm_local_1);
                another = (INT64_C(1) << (teavm_local_3 & INT32_C(63)));
                first = (-INT64_C(1) << teavm_local_1);
                last = ((int64_t) ((uint64_t) -INT64_C(1) >> (-teavm_local_2)));
                hash = value >> INT32_C(32);
                """,
                encoding="utf-8",
            )
            fix_arc_bit_shifts(root)
            result = bits.read_text(encoding="utf-8")
            self.assertIn("UINT64_C(1) << (teavm_local_1 & 63u)", result)
            self.assertIn("UINT64_C(1) << (teavm_local_3 & INT32_C(63))", result)
            self.assertIn("UINT64_MAX << (teavm_local_1 & 63u)", result)
            self.assertIn("UINT64_MAX >> ((0u - (uint32_t) teavm_local_2) & 63u)", result)
            self.assertIn("hash = value >> INT32_C(32);", result)
