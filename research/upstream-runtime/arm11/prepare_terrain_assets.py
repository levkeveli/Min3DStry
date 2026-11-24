#!/usr/bin/env python3
"""Pack the original atlas regions drawn by the Glacier terrain viewport."""

import argparse
import csv
import hashlib
import json
import struct
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
# Mindustry v160.8 Maps.defaultMapNames. The archive also contains Canyon,
# which the original Custom Game catalog does not list.
BUILTIN_CUSTOM_GAME_MAPS = (
    "maze", "fortress", "labyrinth", "islands", "tendrils", "caldera", "wasteland", "shattered", "fork",
    "triad", "mudFlats", "moltenLake", "archipelago", "debrisField", "domain", "veins", "glacier", "passage",
)
sys.path.insert(0, str(CONVERTER.parent))
from build_audio_assets import DEFAULT_RATE, convert_map
from stage_music_assets import load_map, parse_ogg_vorbis_ident
from stage_sound_catalog import UPSTREAM_COMMIT, ogg_frames, stage_sound_catalog

LIVE_AUDIO_MAX_FRAMES = DEFAULT_RATE * 6
MUSIC_MAP = CONVERTER.parents[1] / "audio/upstream-music-map.json"


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


def pack_custom_game_maps(archive: zipfile.ZipFile, maps_dir: Path) -> None:
    builtin = maps_dir / "default"
    builtin.mkdir()
    for name in BUILTIN_CUSTOM_GAME_MAPS:
        filename = f"{name}.msav"
        (builtin / filename).write_bytes(archive.read(f"assets/maps/default/{filename}"))


def pack_campaign_maps(archive: zipfile.ZipFile, maps_dir: Path) -> None:
    for member in archive.namelist():
        match = re.fullmatch(r"assets/maps/(serpulo|erekir)/((?:hidden/)?[A-Za-z0-9_-]+\.msav)", member)
        if match:
            planet, filename = match.groups()
            output = maps_dir / planet / filename
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(archive.read(member))
    for starter in ("serpulo/groundZero.msav", "erekir/onset.msav"):
        if not (maps_dir / starter).is_file():
            raise ValueError(f"Missing original campaign starter map: {starter}")


def pack_planet_data(archive: zipfile.ZipFile, output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    for member in archive.namelist():
        if re.fullmatch(r"assets/planets/[^/]+\.json", member):
            (output / Path(member).name).write_bytes(archive.read(member))
    if not (output / "serpulo.json").is_file():
        raise ValueError("The original Serpulo planet data is missing")


def pack_stock_text_assets(archive: zipfile.ZipFile, staged: Path) -> None:
    for name in STOCK_TEXT_ASSETS:
        destination = staged / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(archive.read("assets/" + name))


def pack_stock_music(desktop: Path, staged: Path) -> None:
    # The caller verifies the desktop release hash before extracting either audio catalog.
    mapping = load_map(MUSIC_MAP)
    if mapping["upstream_commit"] != UPSTREAM_COMMIT or len(mapping["entries"]) != 16:
        raise ValueError("Unexpected pinned music map")
    music_dir = staged / "audio/music"
    music_dir.mkdir(parents=True)
    tracks = []
    with zipfile.ZipFile(desktop) as archive:
        for index, entry in enumerate(mapping["entries"]):
            name = entry["id"]
            if (entry["index"] != index or not re.fullmatch(r"[a-z0-9]+", name)
                    or entry["source"] != f"core/assets/music/{name}.ogg"):
                raise ValueError("Invalid stock music index entry")
            member = f"music/{name}.ogg"
            original = archive.read(member)
            destination = music_dir / f"{name}.ogg"
            destination.write_bytes(original)
            ident = parse_ogg_vorbis_ident(destination)
            tracks.append({"index": index, "source": entry["source"], "romfs": f"romfs:/audio/{member}",
                           "sha256": hashlib.sha256(original).hexdigest(), "bytes": len(original),
                           "channels": ident["channels"], "sample_rate": ident["sample_rate"],
                           "frames": ogg_frames(original, member)})
    manifest = {"format": 1, "upstream_commit": UPSTREAM_COMMIT, "desktop_sha256": DESKTOP_SHA256,
                "tracks": tracks}
    (staged / "audio/music-manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")


def pack_live_audio(args, staged: Path, scratch: Path) -> None:
    catalog = scratch / "catalog"
    sounds = stage_sound_catalog(args.java, args.server, args.desktop, catalog)
    (catalog / "audio").rename(staged / "audio")
    pack_stock_music(args.desktop, staged)
    upstream = scratch / "upstream"
    shutil.copytree(staged / "audio/sounds", upstream / "core/assets/sounds")
    entries = [
        {"id": f"sfx.{sound['id']:03}", "source": sound["source"], "channels": sound["channels"]}
        for sound in sounds
    ]
    audio_map = scratch / "sound-map.json"
    audio_map.write_text(json.dumps({"format": 1, "upstream_commit": UPSTREAM_COMMIT,
                                     "rate": DEFAULT_RATE, "entries": entries}), encoding="ascii")
    pcm = convert_map(upstream, audio_map, scratch / "pcm", args.ffmpeg, romfs_out=staged)
    converted = pcm["entries"]
    if [entry["id"] for entry in converted] != [entry["id"] for entry in entries]:
        raise ValueError("PCM index does not follow the original stock sound IDs")
    short_count = sum(entry["frames"] <= LIVE_AUDIO_MAX_FRAMES for entry in converted)
    lines = ['#include "audio-index.h"', '',
             '#define M3DS_STOCK_SOUND_COUNT M3DS_AUDIO_CLIP_COUNT',
             f'#define M3DS_STOCK_SHORT_COUNT {short_count}U', '']
    (staged / "sound-index.h").write_text("\n".join(lines), encoding="ascii")


def pack_weather_noise(archive: zipfile.ZipFile, output: Path, scratch: Path, tex3ds: str) -> None:
    output.mkdir()
    for name, size in (("noiseAlpha", 128), ("fog", 256)):
        asset = archive.read(f"assets/sprites/{name}.png")
        if asset[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", asset[16:24]) != (size, size):
            raise ValueError(f"Unexpected original dimensions for {name}.png")
        source = scratch / f"{name}.png"
        source.write_bytes(asset)
        subprocess.run([tex3ds, "--atlas", "--format", "rgba8", "--border", "none",
                        "--compress", "auto", "--output", str(output / f"{name}.t3x"), str(source)], check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--desktop", type=Path, help="official pinned desktop JAR for the original font and audio")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--tex3ds", default="tex3ds")
    parser.add_argument("--multiplayer", action="store_true", help="pack Glacier and upstream multiplayer unit sprites")
    parser.add_argument("--singleplayer", action="store_true",
                        help="pack original offline campaign and Custom Game maps, sprites.aatls, and multiplayer assets")
    parser.add_argument("--live-audio", action="store_true", help="also pack pinned stock effects for the live mixer")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    if args.live_audio and not (args.multiplayer or args.singleplayer):
        parser.error("--live-audio requires --multiplayer or --singleplayer")
    if args.multiplayer or args.singleplayer:
        if args.desktop is None or not args.desktop.is_file():
            parser.error("--multiplayer and --singleplayer require the pinned desktop JAR via --desktop")
        with args.desktop.open("rb") as archive:
            if hashlib.file_digest(archive, "sha256").hexdigest() != DESKTOP_SHA256:
                parser.error("expected the official v160.8 Mindustry.jar via --desktop")

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
                pack_weather_noise(archive, staged / "weather", Path(tmp), args.tex3ds)
                (staged / "block_colors.png").write_bytes(archive.read("assets/sprites/block_colors.png"))
                (staged / "logicids.dat").write_bytes(archive.read("assets/logicids.dat"))
                logic_font = Path(tmp) / "logic.ttf"
                logic_font.write_bytes(archive.read("assets/fonts/logic.ttf"))
                for member in archive.namelist():
                    if re.fullmatch(r"assets/bundles/bundle(?:_[A-Za-z_]+)?\.properties", member):
                        (bundles / Path(member).name).write_bytes(archive.read(member))
                if args.singleplayer:
                    (staged / "contributors").write_bytes(archive.read("assets/contributors"))
                    pack_planet_data(archive, staged / "planets")
                    maps_dir = staged / "maps"
                    maps_dir.mkdir(parents=True, exist_ok=True)
                    gz_bytes = archive.read("assets/maps/serpulo/groundZero.msav")
                    aatls_bytes = archive.read("assets/sprites/sprites.aatls")
                    if not gz_bytes or not aatls_bytes:
                        raise ValueError("Missing groundZero.msav or sprites.aatls in official archive")
                    (maps_dir / "groundZero.msav").write_bytes(gz_bytes)
                    pack_custom_game_maps(archive, maps_dir)
                    pack_campaign_maps(archive, maps_dir)
                    (staged / "sprites.aatls").write_bytes(aatls_bytes)
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
