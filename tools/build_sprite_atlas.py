#!/usr/bin/env python3
"""Build a single RGBA8 Citro2D sprite sheet with tex3ds."""

import argparse
from pathlib import Path
import re
import subprocess
import tempfile


FIXTURES = Path(__file__).resolve().parents[1] / "tests" / "assets"
FIXTURE_NAMES = ("floor", "overlay", "strip_0", "strip_1", "item")
TEX3DS_VERSION = "tex3ds v2.3.0"
MAX_TEXTURE_BYTES = 512 * 512 * 4


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tex3ds", default="tex3ds", help="path to tex3ds v2.3.0")
    parser.add_argument("--output-dir", type=Path, default=Path("build/assets"))
    parser.add_argument("sprites", type=Path, nargs="*", help="ordered PNG inputs (default: test fixtures)")
    args = parser.parse_args()

    sprites = args.sprites or [FIXTURES / f"{name}.png" for name in FIXTURE_NAMES]
    names = set()
    for sprite in sprites:
        if not sprite.is_file() or sprite.suffix != ".png":
            parser.error(f"expected an existing .png file: {sprite}")
        # tex3ds sanitizes filenames when deriving C identifiers.
        name = re.sub(r"[^a-z0-9_]", "_", sprite.stem.lower())
        if not re.fullmatch(r"[a-z][a-z0-9_-]*", sprite.stem) or name in names:
            parser.error(f"use unique lowercase sprite names with letters, digits, hyphens or underscores: {sprite}")
        names.add(name)

    try:
        version = subprocess.check_output([args.tex3ds, "--version"], text=True)
        if version.splitlines()[:1] != [TEX3DS_VERSION]:
            parser.error(f"expected {TEX3DS_VERSION}")

        args.output_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=args.output_dir) as tmp:
            atlas = Path(tmp) / "sprites.t3x"
            header = Path(tmp) / "sprites.h"
            subprocess.run(
                [args.tex3ds, "--atlas", "--format", "rgba8", "--border", "edge",
                 "--compress", "none", "--output", atlas.resolve().as_posix(),
                 "--header", header.resolve().as_posix(),
                 *(sprite.resolve().as_posix() for sprite in sprites)],
                check=True,
            )
            data = atlas.read_bytes()
            if len(data) < 5 or int.from_bytes(data[:2], "little") != len(sprites):
                parser.error("unexpected T3X header or sprite count")
            width = 8 << (data[2] & 7)
            height = 8 << ((data[2] >> 3) & 7)
            texture_bytes = width * height * 4
            if texture_bytes > MAX_TEXTURE_BYTES:
                parser.error(f"atlas requires {texture_bytes:,} texture bytes; split it into smaller pages")

            # Keep the generated include identical across Windows and Linux.
            include = header.read_text(encoding="ascii")
            header.write_text(include, encoding="ascii", newline="\n")
            for output in (atlas, header):
                output.replace(args.output_dir / output.name)
    except (OSError, subprocess.CalledProcessError) as error:
        parser.error(str(error))

    print(f"{len(sprites)} sprites, {width}x{height} RGBA8, {texture_bytes:,} texture bytes")


if __name__ == "__main__":
    main()
