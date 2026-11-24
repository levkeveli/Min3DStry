#!/usr/bin/env python3
"""Replay campaign research on both Azahar profiles and reopen the results in stock Mindustry."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import verify_upstream_singleplayer as sp
from campaign_research_fixture import CASES, check
from verify_upstream_campaign_launch import write_input_replay


def verify(model: str, planet: str, rom: Path, azahar: str, config: Path, log: Path,
           fixtures: Path, output: Path) -> tuple[Path, Path]:
    _, name, _, _, block, _, _, unlocked = CASES[planet]
    prefix = f"{model}-{planet}"
    sd = (output / "virtual-sd" / prefix).resolve()
    root = sd / "3ds/min3dstry"
    saves = root / "saves"
    slot = saves / name
    settings = root / "settings.bin"
    replay = output / f"{prefix}.ctm"
    window = [
        (65, 67, (1 << 9) | sp.KEY_SELECT),
        (80, 82, 1 << 7),
        (86, 88, 1),
        (101, 103, 1),
        (108, 110, 1 << 1),
        (115, 117, 1 << 1),
        (129, 131, sp.KEY_START),
    ]
    write_input_replay(replay, model, window, 129, b"Min3DStry Campaign Research")

    config_snapshot = config.read_bytes() if config.is_file() else None
    saves.mkdir(parents=True)
    sp.stage_direct_startup(sd)
    try:
        shutil.copyfile(fixtures / name, slot)
        shutil.copyfile(fixtures / f"{planet}-settings.bin", settings)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(sp.az.patch_qt_config_text((config_snapshot or b"").decode("utf-8"), storage),
                          encoding="utf-8")
        proc, rpc = sp._launch_azahar(model, rom, azahar, config, log, replay)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_LOADED source=campaign\b", 120)
            sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_RESEARCH_OPEN tree={planet}\b", 160)
            sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_RESEARCH_SELECTED node={block} selectable=true\b", 90)
            screenshot = rpc.screenshot()
            if sp.az.inspect_png(screenshot)["top_distinct_colors"] < 8:
                raise AssertionError(f"{prefix}: research details did not render")
            (output / f"{prefix}-research.png").write_bytes(screenshot)
            sp._wait_for_log(log, proc,
                             rf"UPSTREAM_CAMPAIGN_RESEARCH_CHANGED node={block} unlocked={str(unlocked).lower()}\b", 90)
            sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_SAVED slot={name}\b", 90)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 20)
            result = output / f"{prefix}-{name}"
            result_settings = output / f"{prefix}-settings.bin"
            shutil.copyfile(slot, result)
            shutil.copyfile(settings, result_settings)
            return result, result_settings
        finally:
            rpc.close()
            sp._stop_proc(proc, azahar)
            (output / f"{prefix}.log").write_bytes(log.read_bytes())
    finally:
        if config_snapshot is None:
            config.unlink(missing_ok=True)
        else:
            config.write_bytes(config_snapshot)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--model", choices=("new3ds", "old3ds"))
    parser.add_argument("--planet", choices=CASES)
    parser.add_argument("--output-dir", type=Path, default=Path("build/campaign-research-emu"))
    args = parser.parse_args()

    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for model in (args.model,) if args.model else ("new3ds", "old3ds"):
        for planet in (args.planet,) if args.planet else CASES:
            world, settings = verify(model, planet, args.rom, azahar, config, log, args.fixtures, args.output_dir)
            check(args.java, args.server, planet, world, settings)
            print(f"[PASS] {model} {planet} controller research", flush=True)


if __name__ == "__main__":
    main()
