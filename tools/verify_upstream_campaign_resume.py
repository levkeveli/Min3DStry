#!/usr/bin/env python3
"""Verify original v160.8 campaign save selection and save-back on Azahar."""

from __future__ import annotations

import argparse
import struct
import time
from pathlib import Path

import verify_upstream_singleplayer as sp


def write_replay(path: Path, model: str, multiple: bool, move_down: bool) -> None:
    windows = [(104, 106, sp.KEY_START)] if multiple else [(76, 78, sp.KEY_START)]
    if multiple:
        # From Resume, move down past Research to Load.
        windows += [(48, 50, 1), (76, 77, sp.KEY_X), (82, 84, 1 << 7), (88, 90, 1 << 7), (94, 96, 1)]
        if move_down:
            windows += [(43, 45, 1 << 7)]
    frames = sp.CTM_SAMPLES_PER_SECOND * (114 if multiple else 86)
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
    fixture_settings = fixtures / "settings.bin"
    source_settings = fixture_settings.read_bytes() if fixture_settings.exists() else None
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    source_zero = (fixtures / "sector-serpulo-170.msav").read_bytes()
    source_forest = (fixtures / "sector-serpulo-64.msav").read_bytes()
    try:
        for multiple, map_name in ((False, "groundZero"), (True, "frozenForest")):
            stage = "multiple" if multiple else "single"
            sd = (output / "virtual-sd" / model / stage).resolve()
            sd_root = sd / "3ds/min3dstry"
            saves = sd_root / "saves"
            saves.mkdir(parents=True)
            sp.stage_direct_startup(sd)
            zero = saves / "sector-serpulo-170.msav"
            forest = sd_root / "sector-serpulo-64.msav"
            selected = forest if multiple else zero
            settings = sd_root / "settings.bin"
            settings_backup = sd_root / "settings_backup.bin"
            backup = selected.with_name(f"{selected.name}-backup.msav")
            zero.write_bytes(source_zero)
            if multiple:
                forest.write_bytes(source_forest)
            if source_settings is not None:
                settings.write_bytes(source_settings)

            config.parent.mkdir(parents=True, exist_ok=True)
            storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
            config.write_text(sp.az.patch_qt_config_text((originals[config] or b"").decode("utf-8"), storage),
                              encoding="utf-8")
            replay = output / f"{model}-{stage}.ctm"
            write_replay(replay, model, multiple, move_down=source_settings is None)
            proc, rpc = sp._launch_azahar(model, rom, azahar, config, log, replay)
            try:
                if multiple:
                    initial = r" selected=sector-serpulo-64\.msav" if source_settings is not None else ""
                    sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_MENU slots=2{initial}\b", 100)
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
                if multiple:
                    reloaded = sp._wait_for_log(log, proc, r"UPSTREAM_PAUSE_MENU load=true\b", 100)
                    if reloaded.count(f"UPSTREAM_SP_LOADED source=campaign map={map_name} ") != 2:
                        raise AssertionError(f"{model}: pause-menu Load did not reopen the selected campaign save")
                sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_SAVED slot={selection}\.msav\b", 100)
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 20)
                expected = source_forest if multiple else source_zero
                if backup.read_bytes() != expected:
                    raise AssertionError(f"{model}: original PC save is missing from the stock backup path")
                if multiple and zero.read_bytes() != source_zero:
                    raise AssertionError(f"{model}: selecting Frozen Forest modified the Ground Zero save")
                (output / f"{model}-{selected.name}").write_bytes(selected.read_bytes())
                if source_settings is not None:
                    if settings_backup.read_bytes() != source_settings:
                        raise AssertionError(f"{model}: the original PC settings backup was lost")
                    (output / f"{model}-{stage}-settings.bin").write_bytes(settings.read_bytes())
                print(f"[PASS] {model} {stage}: {map_name} saved with stock backup, "
                      f"azahar_game_fps={stats.get('game_fps', 0):.2f}", flush=True)
            finally:
                rpc.close()
                sp._stop_proc(proc, azahar)
                (output / f"{model}-{stage}.log").write_bytes(log.read_bytes())
    finally:
        for path, original in originals.items():
            if original is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(original)


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
