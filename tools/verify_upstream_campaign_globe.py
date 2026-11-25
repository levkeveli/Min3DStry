#!/usr/bin/env python3
"""Replay stock campaign globe rotation, planet switching, and sector launch."""

import argparse
from pathlib import Path
import time

import verify_upstream_singleplayer as sp
from verify_upstream_campaign_launch import write_input_replay


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
    movie = args.output / "new3ds-campaign-globe.ctm"
    # Hold R to open Campaign, X to open its globe, Right to rotate it, Y to
    # switch planets, and A to launch The Onset through the stock sector slot.
    windows = [(0, 24, 1 << 8), (44, 49, sp.KEY_X), (63, 68, 1 << 4),
               (81, 86, sp.KEY_Y), (108, 113, 1), (192, 197, sp.KEY_START)]
    write_input_replay(movie, "new3ds", windows, 197, b"Min3DStry Planet Map")

    emulator = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(emulator)
    originals = {path: path.read_bytes() if path.exists() else None for path in (config, log)}
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.write_text(sp.az.patch_qt_config_text((originals[config] or b"").decode(), storage), encoding="utf-8")
        proc, rpc = sp._launch_azahar("new3ds", args.rom, emulator, config, log, movie)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE planet=serpulo sector=170 available=true\b", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SECTOR_OVERLAY planet=serpulo vertices=[1-9]\d* "
                                       r"shadowed=[1-9]\d* outlined=[1-9]\d*", 25)
            capture_globe(rpc, args.output / "new3ds-serpulo.png")
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE planet=serpulo sector=(?!170\b)\d+ available=", 55)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_GLOBE planet=erekir sector=10 available=true\b", 55)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SECTOR_OVERLAY planet=erekir vertices=[1-9]\d* "
                                       r"shadowed=[1-9]\d* outlined=[1-9]\d*", 25)
            capture_globe(rpc, args.output / "new3ds-erekir.png")
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SELECTED slot=sector-erekir-10\.msav .*source=planet-map\b", 60)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_LOADED source=campaign map=The_Onset size=512x512\b", 130)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 100)
            print("UPSTREAM_CAMPAIGN_GLOBE_PASS model=new3ds planets=2 launched=erekir:10", flush=True)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--azahar")
    parser.add_argument("--output", required=True, type=Path)
    verify(parser.parse_args())


if __name__ == "__main__":
    main()
