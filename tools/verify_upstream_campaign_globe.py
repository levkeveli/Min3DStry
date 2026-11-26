#!/usr/bin/env python3
"""Replay stock campaign globe rotation, planet switching, and sector launch."""

import argparse
from pathlib import Path
import shutil
import tempfile
import time

import verify_upstream_singleplayer as sp
from campaign_resume_fixtures import create
from verify_upstream_campaign_launch import check_stock, write_input_replay


def capture_globe(rpc, path):
    points = ((175, 100), (224, 100), (175, 140), (224, 140))
    deadline = time.monotonic() + 8
    while True:
        png = rpc.screenshot(res_scale=1)
        pixels = sp.az.inspect_png(png, points)["samples"]
        colors = set(pixels.values()) - {(24, 27, 32)}
        if len(colors) >= 2:
            path.write_bytes(png)
            return
        if time.monotonic() >= deadline:
            path.write_bytes(png)
            raise AssertionError(f"Stock globe surface was not visible in {path}: {pixels}")
        time.sleep(0.1)


def verify(args):
    args.output.mkdir(parents=True)
    sd = (args.output / "sdmc").resolve()
    root = sd / "3ds/min3dstry"
    root.mkdir(parents=True)
    (root / "startup.txt").write_text("direct\n", encoding="ascii")
    with tempfile.TemporaryDirectory(prefix="m3ds-globe-v1608-") as temp:
        fixtures = Path(temp)
        create(args.java, args.server, fixtures, globe_lights=True)
        saves = root / "saves"
        saves.mkdir()
        shutil.copyfile(fixtures / "sector-serpulo-170.msav", saves / "sector-serpulo-170.msav")
        shutil.copyfile(fixtures / "settings.bin", root / "settings.bin")
    movie = args.output / "new3ds-campaign-globe.ctm"
    # Hold R to open Campaign, X to open its globe, Right to rotate it, Y to
    # switch planets, and A to preview and launch The Onset through its stock sector slot.
    windows = [(0, 24, 1 << 8), (44, 49, sp.KEY_X), (114, 119, 1 << 4),
               (135, 140, sp.KEY_Y), (166, 171, 1), (187, 192, 1), (342, 357, sp.KEY_START)]
    write_input_replay(movie, "new3ds", windows, 370, b"Min3DStry Planet Map")

    emulator = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(emulator)
    originals = {path: path.read_bytes() if path.exists() else None for path in (config, log)}
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.write_text(sp.az.patch_qt_config_text((originals[config] or b"").decode(), storage), encoding="utf-8")
        proc, rpc = sp._launch_azahar("new3ds", args.rom, emulator, config, log, movie)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_MEMORY appHeap=\d+ linearHeap=8388608\b", 40)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE planet=serpulo sector=170 available=true\b", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SECTOR_OVERLAY planet=serpulo vertices=[1-9]\d* "
                                       r"shadowed=[1-9]\d* outlined=[1-9]\d*", 25)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SECTOR_GRID planet=serpulo edges=[1-9]\d* vertices=[1-9]\d*", 25)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_MARKER_ASSET kind=0 id=0\b", 25)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_MARKERS_DRAW planet=0 markers=[1-9]\d*\b", 25)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SURFACE_DRAW faces=7292 vertices=43740\b", 95)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE_FRAME planet=serpulo sector=170 selection=-1 "
                                       r"surface=true\b", 25)
            capture_globe(rpc, args.output / "new3ds-serpulo.png")
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE planet=serpulo sector=(?!170\b)\d+ available=", 100)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE planet=erekir sector=10 available=true\b", 55)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SECTOR_OVERLAY planet=erekir vertices=[1-9]\d* "
                                       r"shadowed=[1-9]\d* outlined=[1-9]\d*", 25)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SECTOR_GRID planet=erekir edges=[1-9]\d* vertices=[1-9]\d*", 25)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE_FRAME planet=erekir sector=10 selection=-1\b", 25)
            capture_globe(rpc, args.output / "new3ds-erekir.png")
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE_PREVIEW planet=erekir sector=10 resume=false\b", 60)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE_FRAME planet=erekir sector=10 selection=10\b", 25)
            capture_globe(rpc, args.output / "new3ds-erekir-selection.png")
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SELECTED slot=sector-erekir-10\.msav .*source=planet-map\b", 60)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_LOADED source=campaign-new map=The_Onset size=512x512\b", 180)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GENERATION_DONE planet=erekir sector=10\b", 20)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 145)
        finally:
            rpc.close()
            sp._stop_proc(proc, emulator)
            (args.output / "new3ds-campaign-globe.log").write_bytes(log.read_bytes())
    finally:
        for path, original in originals.items():
            if original is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(original)
    check_stock(args.java, args.server, saves / "sector-erekir-10.msav", root / "settings.bin", "erekir")
    print("UPSTREAM_CAMPAIGN_GLOBE_PASS model=new3ds planets=2 launched=erekir:10 stock-readback=ok", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--azahar")
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    verify(parser.parse_args())


if __name__ == "__main__":
    main()
