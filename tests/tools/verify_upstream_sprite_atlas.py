#!/usr/bin/env python3
"""Compare the default factory T3X against its exported PNGs."""

import argparse
from pathlib import Path
import re
import struct
import subprocess


def verify(root: Path, magick: str) -> None:
    data = (root / "sprites.t3x").read_bytes()
    count, dimensions, format_id, mipmaps = struct.unpack_from("<HBBB", data)
    width = 8 << (dimensions & 7)
    height = 8 << ((dimensions >> 3) & 7)
    if (count, width, height, format_id, mipmaps) != (39, 256, 512, 0, 0):
        raise ValueError("Expected the default 39-region, 256x512 RGBA8 sheet")

    payload_offset = 5 + 12 * count
    compression_header = int.from_bytes(data[payload_offset:payload_offset + 4], "little")
    texture = data[payload_offset + 4:]
    if compression_header != (width * height * 4) << 8 or len(texture) != width * height * 4:
        raise ValueError("Invalid uncompressed RGBA8 payload")

    names = re.findall(r"^#define sprites_(\w+)_idx (\d+)$",
                       (root / "sprites.h").read_text(encoding="ascii"), re.M)
    pngs = {p.stem.replace("-", "_"): p for p in (root / "png").glob("*.png")}
    if {name for name, _ in names} != pngs.keys() or sorted(int(i) for _, i in names) != list(range(count)):
        raise ValueError("T3X indices and exported PNGs do not match")

    checked = 0
    for name, index in names:
        sw, sh, left, top, right, bottom = struct.unpack_from("<6H", data, 5 + 12 * int(index))
        if (right - left) * width != sw * 1024 or (top - bottom) * height != sh * 1024:
            raise ValueError(f"Unexpected rotation or subtexture size for {name}")

        source = subprocess.check_output([magick, str(pngs[name]), "-depth", "8", "rgba:-"])
        if len(source) != sw * sh * 4:
            raise ValueError(f"Unexpected PNG dimensions for {name}")

        x0 = left * width // 1024
        y0 = (1024 - top) * height // 1024
        for y in range(sh):
            for x in range(sw):
                px, py = x0 + x, y0 + y
                tile = py // 8 * (width // 8) + px // 8
                # Interleave the three low coordinate bits within each 8x8 tile.
                offset = sum((((px >> bit) & 1) << (2 * bit)) |
                             (((py >> bit) & 1) << (2 * bit + 1)) for bit in range(3))
                pos = (tile * 64 + offset) * 4
                packed = texture[pos:pos + 4][::-1]  # T3X stores ABGR bytes.
                png_pos = (y * sw + x) * 4
                expected = source[png_pos:png_pos + 4]
                if expected[3] != packed[3] or (expected[3] and expected[:3] != packed[:3]):
                    raise ValueError(f"{name}: pixel at ({x}, {y}) changed")
        checked += sw * sh
    print(f"Verified {count} sprites and {checked:,} texels including every alpha channel")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("atlas_dir", type=Path)
    parser.add_argument("--magick", default="magick", help="ImageMagick 7 executable")
    args = parser.parse_args()
    verify(args.atlas_dir, args.magick)
