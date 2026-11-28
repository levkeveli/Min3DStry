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


# Stock Arc Camera3D at the overview orientation (-.15, -.85, -.5), with a
# 60-degree vertical FOV. Reference colors come from v160.8's six original PNGs.
# Keep the samples near the corners to avoid the planets and central HUD.
SKYBOX_PIXELS = {
    (42, 47): (28, 23, 42), (54, 66): (11, 0, 38), (43, 63): (11, 0, 36), (30, 65): (10, 0, 35),
    (346, 51): (128, 127, 130), (353, 50): (56, 55, 58), (373, 49): (43, 42, 45), (364, 62): (16, 15, 18),
    (38, 210): (25, 4, 76), (36, 226): (25, 6, 70), (54, 189): (23, 0, 77), (45, 192): (22, 0, 76),
    (351, 213): (32, 30, 36), (379, 200): (31, 29, 34), (353, 194): (16, 15, 20), (368, 194): (7, 5, 11),
}


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
    # Open and rotate the Serpulo globe, then select Erekir in the solar-system view.
    # Preview and launch The Onset through its stock sector slot.
    windows = [(0, 24, 1 << 8), (44, 49, sp.KEY_X), (114, 119, 1 << 4),
               (135, 140, sp.KEY_X), (152, 156, sp.KEY_RIGHT), (170, 175, sp.KEY_A),
               (194, 199, sp.KEY_A), (215, 220, sp.KEY_A), (372, 387, sp.KEY_START)]
    write_input_replay(movie, "new3ds", windows, 400, b"Min3DStry Planet Map")

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
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SKYBOX_DRAW width=400 height=240\b", 15)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_ATMOSPHERE_DRAW planet=0 vertices=[1-9]\d*\b", 15)
            capture_globe(rpc, args.output / "new3ds-serpulo.png")
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE planet=serpulo sector=(?!170\b)\d+ available=", 100)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SYSTEM_FRAME bodies=6 selected=serpulo orbits=[1-9]\d*\b", 60)
            for body in (2, 3, 4, 5, 6, 7, 8, 9):
                sp._wait_for_log(log, proc, rf"UPSTREAM_PLANET_MESH_DRAW planet={body} vertices=[1-9]\d* indices=[1-9]\d*", 10)
            png = rpc.screenshot(res_scale=1)
            (args.output / "new3ds-solar-system.png").write_bytes(png)
            sun_points = ((199, 120), (200, 120), (201, 120))
            pixels = sp.az.inspect_png(png, sun_points + tuple(SKYBOX_PIXELS))["samples"]
            sun_pixels = {point: pixels[point] for point in sun_points}
            if not any(r > 160 and r - b > 40 for r, g, b in sun_pixels.values()):
                raise AssertionError(f"The stock Sun was not visible at the overview center: {sun_pixels}")

            sky_pixels = {point: pixels[point] for point in SKYBOX_PIXELS}
            matched = sum(max(abs(a - b) for a, b in zip(actual, SKYBOX_PIXELS[point])) <= 2
                          for point, actual in sky_pixels.items())
            # Allow a few samples to be obscured by an orbit or a moving body.
            if matched < 12:
                raise AssertionError(f"Only {matched} stock skybox samples matched: {sky_pixels}")
            print(f"UPSTREAM_CAMPAIGN_SKYBOX_PIXELS matched={matched}/{len(SKYBOX_PIXELS)}", flush=True)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SYSTEM_SELECTED planet=erekir\b", 50)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE planet=erekir sector=10 available=true\b", 55)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SECTOR_OVERLAY planet=erekir vertices=[1-9]\d* "
                                       r"shadowed=[1-9]\d* outlined=[1-9]\d*", 25)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SECTOR_GRID planet=erekir edges=[1-9]\d* vertices=[1-9]\d*", 25)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE_FRAME planet=erekir sector=10 selection=-1\b", 25)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_ATMOSPHERE_DRAW planet=1 vertices=[1-9]\d*\b", 15)
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
