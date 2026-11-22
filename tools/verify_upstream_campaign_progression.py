#!/usr/bin/env python3
"""Replay Ground Zero capture, Router research and launch to the original Frozen Forest."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import verify_upstream_singleplayer as sp
from campaign_research_fixture import check_progression
from verify_upstream_campaign_launch import write_input_replay


def save_picture(rpc, destination: Path) -> None:
    image = rpc.screenshot()
    if sp.az.inspect_png(image)["top_distinct_colors"] < 8:
        raise AssertionError(f"{destination.name}: expected a rendered frame")
    destination.write_bytes(image)


def verify(model: str, rom: Path, azahar: str, config: Path, log: Path,
           fixture: Path, output: Path) -> None:
    root = sp.resolve_sd_save_path(log).parent
    saves = root / "saves"
    origin = saves / "sector-serpulo-170.msav"
    forest = saves / "sector-serpulo-64.msav"
    settings = root / "settings.bin"
    settings_backup = root / "settings_backup.bin"
    settings_backups = root / "settings_backups"
    if any(root.glob("sector-*.msav")) or any(saves.glob("sector-*.msav")) or any(
            path.exists() for path in (settings, settings_backup, settings_backups, root / "campaign-select.txt")):
        raise RuntimeError("Campaign replay needs a clean Azahar virtual SD")

    (output / "saves").mkdir(parents=True, exist_ok=True)
    movie = output / "progression.ctm"
    router = int((fixture / "router-row.txt").read_text(encoding="ascii"))
    windows = [(65, 67, (1 << 9) | sp.KEY_SELECT)]
    windows.extend((78 + step * 4, 80 + step * 4, 1 << 7) for step in range(router))
    windows.extend([
        (94, 96, 1), (102, 104, 1),  # Inspect and research Router.
        (110, 112, 1 << 1), (117, 119, 1 << 1),
        (129, 131, (1 << 8) | sp.KEY_SELECT),
        (144, 146, 1 << 7), (149, 151, 1 << 7),  # Frozen Forest is two rows below Ground Zero.
        (157, 159, 1), (177, 179, 1),
        (250, 252, sp.KEY_START),
    ])
    write_input_replay(movie, model, windows, 250, b"Min3DStry Campaign Progress")
    had_saves = saves.is_dir()
    config_snapshot = config.read_bytes() if config.is_file() else None
    try:
        saves.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(fixture / "saves" / origin.name, origin)
        shutil.copyfile(fixture / "settings.bin", settings)
        proc, rpc = sp._launch_azahar(model, rom, azahar, config, log, movie)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_CAPTURED sector=170\b", 135)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_RESEARCH_SELECTED node=router selectable=true\b", 140)
            save_picture(rpc, output / "router.png")
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_RESEARCH_CHANGED node=router unlocked=true\b", 100)
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_LOADOUT sector=64 origin=170 affordable=true\b", 145)
            save_picture(rpc, output / "loadout.png")
            loaded = r"UPSTREAM_SP_LOADED source=campaign-new map=Frozen_Forest size=200x200\b"
            sp._wait_for_log(log, proc, rf"{loaded}(?s:.*?)UPSTREAM_SP_FRAME .*sprites=[1-9]\d*\b", 150)
            save_picture(rpc, output / "frozen-forest.png")
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_SAVED slot=sector-serpulo-64\.msav\b", 170)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 20)
            for slot in (origin, forest):
                shutil.copyfile(slot, output / "saves" / slot.name)
            shutil.copyfile(settings, output / settings.name)
        finally:
            rpc.close()
            sp._stop_proc(proc, azahar)
            (output / "azahar.log").write_bytes(log.read_bytes())
    finally:
        for path in (origin, forest, settings, settings_backup,
                     *(saves / f"{slot.name}-backup.msav" for slot in (origin, forest))):
            path.unlink(missing_ok=True)
        if settings_backups.is_dir():
            shutil.rmtree(settings_backups)
        if not had_saves and saves.is_dir() and not any(saves.iterdir()):
            saves.rmdir()
        if config_snapshot is None:
            config.unlink(missing_ok=True)
        else:
            config.write_bytes(config_snapshot)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--fixture", required=True, type=Path)
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--model", choices=("new3ds", "old3ds"))
    parser.add_argument("--output-dir", type=Path, default=Path("build/campaign-progression-emu"))
    args = parser.parse_args()
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    for model in (args.model,) if args.model else ("new3ds", "old3ds"):
        output = args.output_dir / model
        verify(model, args.rom, azahar, config, log, args.fixture, output)
        check_progression(args.java, args.server, output)
        print(f"[PASS] {model}: captured Ground Zero, researched Router, launched and saved Frozen Forest", flush=True)


if __name__ == "__main__":
    main()
