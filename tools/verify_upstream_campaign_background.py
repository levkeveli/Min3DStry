#!/usr/bin/env python3
"""Run a complete original Serpulo background turn in the graphical ARM11 build."""

import argparse
from pathlib import Path
import re
import shutil

from campaign_background_fixture import LANDING_SLOT, check, check_remote_saves, create
from verify_upstream_campaign_launch import write_input_replay
import verify_upstream_singleplayer as sp


def verify(args, model, azahar, config, log, fixture):
    output = args.output / model
    sd = (output / "virtual-sd").resolve()
    sp.stage_direct_startup(sd)
    campaign = sd / "3ds/min3dstry"
    shutil.copytree(fixture / "saves", campaign / "saves")
    shutil.copyfile(fixture / "settings.bin", campaign / "settings.bin")
    movie = output / "background-turn.ctm"
    # Frame pacing can make wall-clock time diverge from stock campaign time.
    # Save periodically and keep the first checkpoint with the imported copper.
    first_save = 780 if model == "old3ds" else 170
    deadline = 1800
    windows = [(40, 42, 1)]
    windows.extend((second, second + 2, sp.KEY_SELECT) for second in range(first_save, deadline, 90))
    write_input_replay(movie, model, windows, deadline, b"Min3DStry Campaign Background")
    original_config = config.read_bytes() if config.is_file() else None
    original_log = log.read_bytes() if log.is_file() else None
    try:
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(sp.az.patch_qt_config_text((original_config or b"").decode("utf-8"), storage),
                          encoding="utf-8")
        proc, rpc = sp._launch_azahar(model, args.rom, azahar, config, log, movie)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_MENU slots=3 selected=sector-serpulo-170\.msav", 110)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_LOADED source=campaign.*\bcopper=7\b", 110)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_FRAME ", 60)
            image = rpc.screenshot()
            if sp.az.inspect_png(image)["top_distinct_colors"] < 8:
                raise AssertionError(f"{model}: expected a rendered Ground Zero world")
            (output / "ground-zero.png").write_bytes(image)
            saved_turn = rf"UPSTREAM_SP_SAVED .*{re.escape(LANDING_SLOT)} .*\bcopper=37\b"
            text = sp._wait_for_log(log, proc, rf"{saved_turn}|UPSTREAM_SP_EXIT\b", deadline + 60)
            if not re.search(saved_turn, text):
                raise AssertionError(f"{model}: the game exited before saving the campaign turn:\n{text[-2500:]}")
            check_remote_saves(fixture, campaign)
            check(args.java, args.server, campaign)
            print(f"[PASS] {model}: stock Java read back the graphical ARM11 campaign turn", flush=True)
        finally:
            rpc.close()
            sp._stop_proc(proc, azahar)
            (output / "azahar.log").write_bytes(log.read_bytes())
    finally:
        for path, data in ((config, original_config), (log, original_log)):
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--model", choices=("new3ds",), action="append")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True)
    fixture = args.output / "fixture"
    create(args.java, args.server, fixture)
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    for model in args.model or ("new3ds",):
        verify(args, model, azahar, config, log, fixture)


if __name__ == "__main__":
    main()
