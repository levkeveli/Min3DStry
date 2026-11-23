#!/usr/bin/env python3
"""Exercise the live PCM cache and streams with a mock DSP and staged assets."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("assets", type=Path, help="output from prepare_terrain_assets.py --live-audio")
    parser.add_argument("--cc", default=os.environ.get("CC", "cc"))
    args = parser.parse_args()
    assets = args.assets.resolve()
    arm11 = ROOT / "research/upstream-runtime/arm11"
    tests = ROOT / "tests/audio/live-stock"
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        for name in ("sound-index.h", "audio-index.h"):
            # RomFS absolute paths are unavailable on the host. Run from the real asset directory.
            source = (assets / name).read_text(encoding="ascii").replace('"romfs:/audio/', '"audio/')
            (work / name).write_text(source, encoding="ascii")
        binary = work / "test_live_stock_audio.exe"
        subprocess.run([args.cc, "-std=c11", "-Wall", "-Wextra", "-Werror", "-DM3DS_LIVE_AUDIO",
                        "-I", str(work), "-I", str(tests), "-I", str(arm11), "-I", str(ROOT / "include"),
                        str(tests / "test_live_stock_audio.c"), str(arm11 / "m3ds_live_audio.c"),
                        str(ROOT / "src/audio/audio_mixer.c"), str(ROOT / "src/audio/audio_registry.c"),
                        str(ROOT / "src/audio/audio_stream.c"),
                        "-lm", "-o", str(binary)], check=True)
        result = subprocess.run([str(binary)], cwd=assets, check=True, capture_output=True, text=True)
        for line in result.stdout.splitlines():
            if "UPSTREAM_LIVE_AUDIO_PLAY" not in line or "id=117 " in line or "id=185 " in line:
                print(line)


if __name__ == "__main__":
    main()
