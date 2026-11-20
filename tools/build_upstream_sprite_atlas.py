#!/usr/bin/env python3
"""Inventory the pinned upstream atlas and build a first lossless 3DS page."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


TOOLS = Path(__file__).resolve().parent
ASSETS_SHA256 = "2728b63d309c27a405744c8b5440d90a65387a41e2c4339b630a239f79f6bd17"
SERVER_SHA256 = "faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c"
FACTORY_SPRITES = (
    *(f"{floor}{variant}" for floor in ("ice", "ice-snow", "snow", "ore-copper")
      for variant in range(1, 4)),
    *(f"conveyor-{variant}-{frame}" for variant in range(5) for frame in range(4)),
    "router", "mechanical-drill", "mechanical-drill-rotator", "mechanical-drill-top",
    "core-shard", "core-shard-team-sharded", "item-copper",
)


def verify_archive(path: Path, expected: str) -> None:
    digest = hashlib.sha256()
    with path.open("rb") as archive:
        for chunk in iter(lambda: archive.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise ValueError(f"{path}: expected official Mindustry v160.8 SHA-256 {expected}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True, help="official v160.8 assets.jar")
    parser.add_argument("--server", type=Path, required=True, help="official v160.8 server-release.jar")
    parser.add_argument("--output-dir", type=Path, required=True, help="new output directory")
    parser.add_argument("--java", default="java", help="path to a JDK 17+ java executable")
    parser.add_argument("--tex3ds", default="tex3ds", help="path to tex3ds v2.3.0")
    parser.add_argument("sprites", nargs="*", help="ordered upstream names (default: Glacier factory set)")
    args = parser.parse_args()

    sprites = args.sprites or FACTORY_SPRITES
    identifiers = {name.replace("-", "_") for name in sprites}
    if len(identifiers) != len(sprites) or any(
        not re.fullmatch(r"[a-z][a-z0-9_-]*", name) for name in sprites
    ):
        parser.error("use unique lowercase sprite names with letters, digits, hyphens or underscores")

    try:
        verify_archive(args.assets, ASSETS_SHA256)
        verify_archive(args.server, SERVER_SHA256)
        output = args.output_dir.resolve()
        if output.exists():
            parser.error(f"output already exists: {output}")
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
            staged = Path(tmp) / "atlas"
            staged.mkdir()
            subprocess.run(
                [args.java, "-Djava.awt.headless=true", "-cp", str(args.server.resolve()),
                 str(TOOLS / "UpstreamSpriteAtlas.java"), str(args.assets.resolve()), str(staged),
                 *sprites],
                check=True,
            )
            subprocess.run(
                [sys.executable, str(TOOLS / "build_sprite_atlas.py"), "--tex3ds", args.tex3ds,
                 "--output-dir", str(staged), *(str(staged / "png" / f"{name}.png")
                                                for name in sprites)],
                check=True,
            )
            os.rename(staged, output)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
