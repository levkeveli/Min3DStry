import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import check_main_stack  # noqa: E402

SAMPLE = """\
00d43290 <main>:
d43290: e92d4ff0 push {r4, r5, r6, r7, r8, r9, sl, fp, lr}
d43294: ed2d8b08 vpush {d8-d11}
d43298: e24dde91 sub sp, sp, #2320
d4329c: e24dd00c sub sp, sp, #12
d432a0: eb002a74 bl d4dc80 <gfxInitDefault>
"""


class CheckMainStackTests(unittest.TestCase):
    def test_parses_fixed_main_frame(self) -> None:
        self.assertEqual(check_main_stack.parse_main_frame(SAMPLE), 2400)

    def test_missing_main(self) -> None:
        self.assertIsNone(check_main_stack.parse_main_frame("nothing here\n"))

    def test_ignores_calls_before_main(self) -> None:
        text = "1000 <other>:\nsub sp, sp, #999\nbl foo\n" + SAMPLE
        self.assertEqual(check_main_stack.parse_main_frame(text), 2400)

    def test_register_count(self) -> None:
        self.assertEqual(check_main_stack._register_count("r4, r5, lr"), (3, 0))
        self.assertEqual(check_main_stack._register_count("d8-d11"), (0, 4))


if __name__ == "__main__":
    unittest.main()
