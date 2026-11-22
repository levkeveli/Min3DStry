#!/usr/bin/env python3
"""Pack the original atlas regions drawn by the Glacier terrain viewport."""

import argparse
import csv
from pathlib import Path
import subprocess
import sys
import tempfile
import re
import zipfile


REGIONS = (
    "ice-edge", "ice1", "ice2", "ice3", "ore-coal1", "ore-coal2", "ore-coal3",
    "ore-copper1", "ore-copper2", "ore-copper3", "stone1", "stone2", "stone3",
)
CONVERTER = Path(__file__).resolve().parents[3] / "tools" / "build_upstream_sprite_atlas.py"
GLACIER_INVENTORY = Path(__file__).with_name("glacier-terrain.txt")
STOCK_TEXT_ASSETS = (
    "fonts/font.woff", "fonts/font_jp.woff", "fonts/icon.ttf", "icons/icons.properties",
)


def write_layout(staged: Path, regions: tuple[str, ...]) -> None:
    entries = {}
    page_indices = {}
    with (staged / "regions.tsv").open(encoding="utf-8", newline="") as inventory:
        for row in csv.DictReader(inventory, delimiter="\t"):
            entries[row["name"]] = row
            page_indices.setdefault(row["page"], len(page_indices))

    lines = ['#include "sprites.h"', '', 'static const ArcAtlasRegion terrain_regions[] = {']
    for name in regions:
        row = entries[name]
        position = [str(page_indices[row["page"]])]
        position.extend(str(int(row[key])) for key in ("left", "top", "width", "height"))
        position.append(f"sprites_{name.replace('-', '_')}_idx")
        lines.append("    { " + ", ".join(position) + " },")
    lines.append("};")
    (staged / "terrain_layout.h").write_text("\n".join(lines) + "\n", encoding="ascii")


def pack_menu_icons(args, staged: Path, extracted: Path) -> None:
    extracted.mkdir()
    subprocess.run([args.java, "-Djava.awt.headless=true", "-cp", str(args.server.resolve()),
                    str(CONVERTER.with_name("UpstreamSpriteAtlas.java")), str(args.assets.resolve()),
                    str(extracted), "--menu-icons"], check=True)
    manifest = extracted / "menu-icons.tsv"
    (extracted / "catalog").rename(staged / "catalog")
    manifest.rename(staged / manifest.name)

def pack_world_atlas(args, staged: Path, extracted: Path) -> None:
    extracted.mkdir()
    subprocess.run([args.java, "-Djava.awt.headless=true", "-cp", str(args.server.resolve()),
                    str(CONVERTER.with_name("UpstreamSpriteAtlas.java")), str(args.assets.resolve()),
                    str(extracted), "--world-pages"], check=True)
    for name in ("world-atlas", "world-atlas.tsv", "world_atlas_layout.h"):
        (extracted / name).rename(staged / name)


def pack_stock_text_assets(archive: zipfile.ZipFile, staged: Path) -> None:
    for name in STOCK_TEXT_ASSETS:
        destination = staged / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(archive.read("assets/" + name))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--tex3ds", default="tex3ds")
    parser.add_argument("--multiplayer", action="store_true", help="pack Glacier and upstream multiplayer unit sprites")
    parser.add_argument("--singleplayer", action="store_true",
                        help="pack offline single-player Ground Zero map, sprites.aatls, and multiplayer assets")
    args = parser.parse_args()

    output = args.output_dir.resolve()
    if output.exists():
        parser.error(f"output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    regions = REGIONS
    pack_multi = args.multiplayer or args.singleplayer
    if pack_multi:
        surveyed = (name for name in GLACIER_INVENTORY.read_text(encoding="ascii").splitlines()
                    if not name.startswith("core-nucleus"))
        conveyors = (f"conveyor-{variant}-{frame}" for variant in range(5) for frame in range(4))
        regions = tuple(dict.fromkeys((*surveyed, "gamma", "dagger", "copper-wall", "router",
                                       "sorter", "inverted-sorter", "cross-full", "white", *conveyors)))
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        staged = Path(tmp) / "terrain"
        subprocess.run([sys.executable, str(CONVERTER), "--assets", str(args.assets),
                        "--server", str(args.server), "--output-dir", str(staged),
                        "--java", args.java, "--tex3ds", args.tex3ds, *regions], check=True)
        write_layout(staged, regions)
        if pack_multi:
            pack_menu_icons(args, staged, Path(tmp) / "menu")
            pack_world_atlas(args, staged, Path(tmp) / "world")
            bundles = staged / "bundles"
            bundles.mkdir()
            with zipfile.ZipFile(args.assets) as archive:
                pack_stock_text_assets(archive, staged)
                (staged / "block_colors.png").write_bytes(archive.read("assets/sprites/block_colors.png"))
                for member in archive.namelist():
                    if re.fullmatch(r"assets/bundles/bundle(?:_[A-Za-z_]+)?\.properties", member):
                        (bundles / Path(member).name).write_bytes(archive.read(member))
                if args.singleplayer:
                    maps_dir = staged / "maps"
                    maps_dir.mkdir(parents=True, exist_ok=True)
                    gz_bytes = archive.read("assets/maps/serpulo/groundZero.msav")
                    aatls_bytes = archive.read("assets/sprites/sprites.aatls")
                    if not gz_bytes or not aatls_bytes:
                        raise ValueError("Missing groundZero.msav or sprites.aatls in official archive")
                    (maps_dir / "groundZero.msav").write_bytes(gz_bytes)
                    (staged / "sprites.aatls").write_bytes(aatls_bytes)
            if not (bundles / "bundle.properties").is_file():
                raise ValueError("The official archive is missing its root translation bundle")
        staged.rename(output)


if __name__ == "__main__":
    main()
