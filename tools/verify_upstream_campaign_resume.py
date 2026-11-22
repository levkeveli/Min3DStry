#!/usr/bin/env python3
"""Verify original v160.8 campaign save selection and save-back on Azahar."""

from __future__ import annotations

import argparse
import struct
import time
from pathlib import Path

import verify_upstream_singleplayer as sp


def write_replay(path: Path, model: str, multiple: bool) -> None:
    windows = [(76, 78, sp.KEY_START)]
    if multiple:
        windows += [(40, 42, 1 << 7), (44, 46, 1 << 6), (48, 50, 1)]
    frames = sp.CTM_SAMPLES_PER_SECOND * 86
    header = struct.pack("<4sQ20sQQ32sIQq156x", b"CTM\x1b", 0, sp.CTM_REVISION, 1780000000,
                         0x4D334453, b"Min3DStry Campaign", 0, frames, 0)
    idle = struct.pack("<B6x", 1)
    with path.open("wb") as movie:
        movie.write(header)
        for frame in range(frames):
            second = frame // sp.CTM_SAMPLES_PER_SECOND
            buttons = next((mask for start, end, mask in windows if start <= second < end), 0)
            pad = struct.pack("<BHhh", 0, buttons, 0, 0)
            movie.write(pad + (pad if buttons else idle))
            if model == "new3ds" and frame % 7 in (0, 2, 4):
                movie.write(pad)


def verify(model: str, rom: Path, azahar: str, config: Path, log: Path,
           output: Path, fixtures: Path) -> None:
    sd_root = sp.resolve_sd_save_path(log).parent
    saves = sd_root / "saves"
    if any(sd_root.glob("sector-*.msav")) or any(saves.glob("sector-*.msav")):
        raise RuntimeError("Use an Azahar virtual SD without existing campaign saves")
    if (sd_root / "campaign-select.txt").exists():
        raise RuntimeError("Use an Azahar virtual SD without a campaign selection override")
    initial_config = config.read_bytes() if config.is_file() else None
    had_saves = saves.exists()
    saves.mkdir(parents=True, exist_ok=True)
    zero = saves / "sector-serpulo-170.msav"
    forest = sd_root / "sector-serpulo-64.msav"
    source_zero = (fixtures / zero.name).read_bytes()
    source_forest = (fixtures / forest.name).read_bytes()
    backups = {slot: slot.with_name(f"{slot.name}-backup.msav") for slot in (zero, forest)}
    try:
        for multiple, selected, map_name in ((False, zero, "groundZero"), (True, forest, "frozenForest")):
            stage = "multiple" if multiple else "single"
            zero.write_bytes(source_zero)
            if multiple:
                forest.write_bytes(source_forest)
            replay = output / f"{model}-{stage}.ctm"
            write_replay(replay, model, multiple)
            proc, rpc = sp._launch_azahar(model, rom, azahar, config, log, replay)
            try:
                if multiple:
                    sp._wait_for_log(log, proc, r"UPSTREAM_CAMPAIGN_MENU slots=2\b", 100)
                    time.sleep(2)
                    menu = rpc.screenshot()
                    if sp.az.inspect_png(menu)["top_distinct_colors"] < 8:
                        raise AssertionError(f"{model}: expected the graphical sector list")
                    (output / f"{model}-menu.png").write_bytes(menu)
                selection = "sector-serpulo-64" if multiple else "sector-serpulo-170"
                source = "" if multiple else " source=single"
                sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_SELECTED slot={selection}\.msav{source}\b", 100)
                sp._wait_for_log(log, proc, rf"UPSTREAM_SP_LOADED source=campaign map={map_name}\b", 100)
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_FRAME ", 100)
                rpc.perf_stats(reset=True)
                time.sleep(1.5)
                stats = rpc.perf_stats(reset=True) or {}
                (output / f"{model}-{stage}.png").write_bytes(rpc.screenshot())
                sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_SAVED slot={selection}\.msav\b", 100)
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 20)
                expected = source_forest if multiple else source_zero
                if backups[selected].read_bytes() != expected:
                    raise AssertionError(f"{model}: original PC save is missing from the stock backup path")
                if multiple and zero.read_bytes() != source_zero:
                    raise AssertionError(f"{model}: selecting Frozen Forest modified the Ground Zero save")
                (output / f"{model}-{selected.name}").write_bytes(selected.read_bytes())
                print(f"[PASS] {model} {stage}: {map_name} saved with stock backup, "
                      f"azahar_game_fps={stats.get('game_fps', 0):.2f}", flush=True)
            finally:
                rpc.close()
                sp._stop_proc(proc, azahar)
                (output / f"{model}-{stage}.log").write_bytes(log.read_bytes())
    finally:
        for path in (zero, forest, *backups.values()):
            path.unlink(missing_ok=True)
        if not had_saves and not any(saves.iterdir()):
            saves.rmdir()
        if initial_config is None:
            config.unlink(missing_ok=True)
        else:
            config.write_bytes(initial_config)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--fixtures", required=True, type=Path)
    parser.add_argument("--azahar")
    parser.add_argument("--model", choices=("new3ds", "old3ds"))
    parser.add_argument("--output-dir", type=Path, default=Path("build/campaign-resume-emu"))
    args = parser.parse_args()
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for model in (args.model,) if args.model else ("new3ds", "old3ds"):
        verify(model, args.rom, azahar, config, log, args.output_dir, args.fixtures)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
