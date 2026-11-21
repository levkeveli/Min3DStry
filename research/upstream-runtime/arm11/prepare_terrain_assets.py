#!/usr/bin/env python3
"""Pack the original atlas regions drawn by the Glacier terrain viewport."""

import argparse
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import re
import shutil
import zipfile


REGIONS = (
    "ice-edge", "ice1", "ice2", "ice3", "ore-coal1", "ore-coal2", "ore-coal3",
    "ore-copper1", "ore-copper2", "ore-copper3", "stone1", "stone2", "stone3",
)
CONVERTER = Path(__file__).resolve().parents[3] / "tools" / "build_upstream_sprite_atlas.py"
GLACIER_INVENTORY = Path(__file__).with_name("glacier-terrain.txt")
DESKTOP_SHA256 = "6dd6da921d5021a84823456e05b477ee8de8511990d40de850b37dd0905152f2"
STOCK_TEXT_ASSETS = (
    "fonts/font.woff", "fonts/font_jp.woff", "fonts/icon.ttf", "icons/icons.properties",
)
sys.path.insert(0, str(CONVERTER.parent))
from build_audio_assets import DEFAULT_RATE, convert_map
from stage_sound_catalog import UPSTREAM_COMMIT, stage_sound_catalog

LIVE_AUDIO_MAX_FRAMES = DEFAULT_RATE * 6


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


def pack_live_audio(args, staged: Path, scratch: Path) -> None:
    catalog = scratch / "catalog"
    sounds = stage_sound_catalog(args.java, args.server, args.desktop, catalog)
    (catalog / "audio").rename(staged / "audio")
    upstream = scratch / "upstream"
    shutil.copytree(staged / "audio/sounds", upstream / "core/assets/sounds")
    short = [
        {"id": f"sfx.{sound['id']:03}", "source": sound["source"], "channels": sound["channels"]}
        for sound in sounds
        if sound["frames"] * DEFAULT_RATE <= LIVE_AUDIO_MAX_FRAMES * sound["sample_rate"]
    ]
    audio_map = scratch / "sound-map.json"
    audio_map.write_text(json.dumps({"format": 1, "upstream_commit": UPSTREAM_COMMIT,
                                     "rate": DEFAULT_RATE, "entries": short}), encoding="ascii")
    pcm = convert_map(upstream, audio_map, scratch / "pcm", args.ffmpeg, romfs_out=staged)
    if any(entry["frames"] > LIVE_AUDIO_MAX_FRAMES for entry in pcm["entries"]):
        raise ValueError("Converted sound exceeds the live clip frame limit")
    index = {entry["id"]: ordinal for ordinal, entry in enumerate(pcm["entries"])}
    ordinals = [index.get(f"sfx.{sound['id']:03}", -1) for sound in sounds]
    lines = ['#include <stdint.h>', '#include "audio-index.h"', '',
             f'#define M3DS_STOCK_SOUND_COUNT {len(ordinals)}U',
             'static const int16_t m3ds_sound_to_clip[M3DS_STOCK_SOUND_COUNT] = {']
    for start in range(0, len(ordinals), 16):
        lines.append("    " + ", ".join(map(str, ordinals[start:start + 16])) + ",")
    lines.extend(["};", ""])
    (staged / "sound-index.h").write_text("\n".join(lines), encoding="ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--desktop", type=Path, help="official Mindustry.jar with Arc's native FreeType font packer")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--tex3ds", default="tex3ds")
    parser.add_argument("--multiplayer", action="store_true", help="pack Glacier and upstream multiplayer unit sprites")
    parser.add_argument("--live-audio", action="store_true", help="also pack pinned stock effects for the live mixer")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    if args.live_audio and not args.multiplayer:
        parser.error("--live-audio requires --multiplayer")
    if args.multiplayer:
        if args.desktop is None or not args.desktop.is_file():
            parser.error("--multiplayer requires the pinned desktop JAR via --desktop")
        with args.desktop.open("rb") as archive:
            if hashlib.file_digest(archive, "sha256").hexdigest() != DESKTOP_SHA256:
                parser.error("expected the official v160.8 Mindustry.jar via --desktop")

    output = args.output_dir.resolve()
    if output.exists():
        parser.error(f"output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    regions = REGIONS
    if args.multiplayer:
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
        if args.multiplayer:
            pack_menu_icons(args, staged, Path(tmp) / "menu")
            pack_world_atlas(args, staged, Path(tmp) / "world")
            bundles = staged / "bundles"
            bundles.mkdir()
            with zipfile.ZipFile(args.assets) as archive:
                pack_stock_text_assets(archive, staged)
                (staged / "block_colors.png").write_bytes(archive.read("assets/sprites/block_colors.png"))
                (staged / "logicids.dat").write_bytes(archive.read("assets/logicids.dat"))
                logic_font = Path(tmp) / "logic.ttf"
                logic_font.write_bytes(archive.read("assets/fonts/logic.ttf"))
                for member in archive.namelist():
                    if re.fullmatch(r"assets/bundles/bundle(?:_[A-Za-z_]+)?\.properties", member):
                        (bundles / Path(member).name).write_bytes(archive.read(member))
            subprocess.run([args.java, "-Dfile.encoding=UTF-8", "-cp", str(args.desktop.resolve()),
                            str(CONVERTER.with_name("PackUpstreamLogicFont.java")), str(logic_font),
                            str(staged / "logic-font.dat")], check=True)
            if not (bundles / "bundle.properties").is_file():
                raise ValueError("The official archive is missing its root translation bundle")
            if args.live_audio:
                pack_live_audio(args, staged, Path(tmp) / "live-audio")
        staged.rename(output)


if __name__ == "__main__":
    main()
