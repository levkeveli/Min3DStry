#!/usr/bin/env python3
"""Verify original v160.8 campaign save selection and save-back on Azahar."""

from __future__ import annotations

import argparse
from contextlib import suppress
import re
import struct
import time
import zlib
from pathlib import Path

import verify_upstream_singleplayer as sp
from campaign_resume_fixtures import DENSE_PRESETS, DensePreset, check_dense_preset


def metadata_only(save: bytes) -> bytes:
    data = zlib.decompress(save)
    magic, _, metadata_size = struct.unpack_from(">4sII", data)
    end = 12 + metadata_size
    if magic != b"MSAV" or not 12 < end < len(data):
        raise ValueError("Expected a stock .msav with metadata and world data")
    return zlib.compress(data[:end])


def write_replay(path: Path, model: str, multiple: bool, move_down: bool, dense: bool = False, *,
                 resave: bool = False, sector_menu: bool = True, from_title: bool = False) -> None:
    windows = [(104, 106, sp.KEY_START)] if multiple else [(76, 78, sp.KEY_START)]
    if multiple:
        # From Resume, move down past Research to Load.
        windows += [(48, 50, 1), (76, 77, sp.KEY_X), (82, 84, 1 << 7), (88, 90, 1 << 7), (94, 96, 1)]
        if move_down:
            windows += [(43, 45, 1 << 7)]
    seconds = 114 if multiple else 86
    if dense and multiple:
        # Dense ARM11 frames can be many seconds apart. Hold each button long
        # enough to cross an input poll and leave a neutral window for release.
        windows = [(start, end, key) for start, end, key in windows if sector_menu and start < 60]
        windows += [(175, 207, sp.KEY_X), (250, 282, 1 << 7), (325, 357, 1 << 7),
                    (400, 432, 1), (650, 682, sp.KEY_START)]
        # Keep replay running during the exit save. Azahar pauses when the movie
        # reaches its end, including while the game is compressing the map.
        seconds = 1290
        if resave:
            windows[-1:] = [(650, 682, sp.KEY_SELECT), (1040, 1072, sp.KEY_START)]
            seconds = 1540
    if from_title:
        # Stock title opens Play, then Campaign. A single saved sector is the
        # first row even when the selector lists other launch destinations.
        delay = 100
        menus = [(35, 39, sp.KEY_A), (53, 57, sp.KEY_A), (110, 119, sp.KEY_A)]
        windows = menus + [(start + delay, end + delay, key) for start, end, key in windows if start >= 175]
        seconds += delay
    frames = sp.CTM_SAMPLES_PER_SECOND * seconds
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
           output: Path, fixtures: Path, dense: bool = False, *, resave: bool = False,
           preset: DensePreset | None = None, from_title: bool = False,
           backup_only: bool = False, truncated_primary: bool = False) -> None:
    fixture_settings = fixtures / "settings.bin"
    source_settings = fixture_settings.read_bytes() if fixture_settings.exists() else None
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    source_zero = (fixtures / "sector-serpulo-170.msav").read_bytes() if preset is None else None
    secondary_slot = preset.slot if preset is not None else f"sector-serpulo-{242 if dense else 64}.msav"
    source_secondary = (fixtures / secondary_slot).read_bytes()
    incomplete_secondary = metadata_only(source_secondary) if truncated_primary else None
    secondary_map = r"sector-serpulo-242 size=600x600" if dense else "frozenForest"
    if preset is not None:
        secondary_map = rf"{re.escape(preset.map_name)} size=600x600"
    cases = ((True, secondary_map),) if preset is not None else ((False, "groundZero"), (True, secondary_map))
    try:
        for multiple, map_name in cases:
            sector_menu = multiple and (preset is None or from_title)
            stage = "dense-preset" if preset is not None else "multiple" if multiple else "single"
            sd = (output / "virtual-sd" / model / stage).resolve()
            sd_root = sd / "3ds/min3dstry"
            saves = sd_root / "saves"
            saves.mkdir(parents=True)
            if not from_title:
                sp.stage_direct_startup(sd)
            zero = saves / "sector-serpulo-170.msav"
            secondary = (saves if preset is not None else sd_root) / secondary_slot
            selected = secondary if multiple else zero
            settings = sd_root / "settings.bin"
            settings_backup = sd_root / "settings_backup.bin"
            backup = selected.with_name(f"{selected.name}-backup.msav")
            if source_zero is not None:
                zero.write_bytes(source_zero)
            if multiple:
                (backup if backup_only or truncated_primary else secondary).write_bytes(source_secondary)
                if incomplete_secondary is not None:
                    secondary.write_bytes(incomplete_secondary)
            if source_settings is not None:
                settings.write_bytes(source_settings)

            config.parent.mkdir(parents=True, exist_ok=True)
            storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
            config.write_text(sp.az.patch_qt_config_text((originals[config] or b"").decode("utf-8"), storage),
                              encoding="utf-8")
            replay = output / f"{model}-{stage}.ctm"
            write_replay(replay, model, multiple, move_down=source_settings is None and sector_menu,
                         dense=dense, resave=resave, sector_menu=sector_menu, from_title=from_title)
            proc, rpc = sp._launch_azahar(model, rom, azahar, config, log, replay)
            try:
                if from_title:
                    sp._wait_for_log(log, proc, r"UPSTREAM_BOOT mode=title\b", 50)
                if sector_menu:
                    initial = rf" selected={re.escape(secondary_slot)}" if from_title or source_settings is not None else ""
                    slot_count = r"[1-9]\d*" if from_title else "2"
                    sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_MENU slots={slot_count}{initial}\b",
                                     135 if from_title else 100)
                    time.sleep(2)
                    menu = rpc.screenshot()
                    if sp.az.inspect_png(menu)["top_distinct_colors"] < 8:
                        raise AssertionError(f"{model}: expected the graphical sector list")
                    (output / f"{model}-menu.png").write_bytes(menu)
                selection = re.escape(selected.name)
                source = "" if sector_menu else " source=single"
                sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_SELECTED slot={selection}{source}\b", 100)
                loaded = rf"UPSTREAM_SP_LOADED source=campaign map={map_name}\b"
                load_timeout = 430 if dense and multiple else 100
                sp._wait_for_log(log, proc, loaded, load_timeout)
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_FRAME ", 100)
                rpc.perf_stats(reset=True)
                time.sleep(1.5)
                stats = rpc.perf_stats(reset=True) or {}
                (output / f"{model}-{stage}.png").write_bytes(rpc.screenshot())
                if multiple:
                    if dense:
                        sp._wait_for_log(log, proc, r"UPSTREAM_PAUSE_MENU open=true\b", load_timeout)
                    reloaded = sp._wait_for_log(log, proc, r"UPSTREAM_PAUSE_MENU load=true\b", load_timeout)
                    if len(re.findall(loaded, reloaded)) != 2:
                        raise AssertionError(f"{model}: pause-menu Load did not reopen the selected campaign save")
                    if backup_only and selected.exists():
                        raise AssertionError(f"{model}: opening a backup-only sector wrote a new primary")
                    if incomplete_secondary is not None and selected.read_bytes() != incomplete_secondary:
                        raise AssertionError(f"{model}: loading from backup modified the original primary")
                    if dense:
                        sp._wait_for_log(log, proc, r"(?s)UPSTREAM_PAUSE_MENU load=true\b.*?UPSTREAM_SP_FRAME ", 100)
                        (output / f"{model}-reloaded.png").write_bytes(rpc.screenshot())
                save_timeout = 640 if dense and multiple else load_timeout
                expected_backup = source_secondary if multiple else source_zero
                if dense and multiple and resave:
                    save_marker = rf"UPSTREAM_SP_SAVED path=[^\r\n]*/{selection} bytes=[1-9]\d+\b"
                    checkpoint_log = sp._wait_for_log(log, proc, save_marker, save_timeout)
                    if re.search(r"UPSTREAM_SP_EXIT\b", checkpoint_log) or backup.read_bytes() != expected_backup:
                        raise AssertionError(f"{model}: manual save did not preserve the original PC sector")
                    checkpoint = selected.read_bytes()
                    if checkpoint == expected_backup:
                        raise AssertionError(f"{model}: manual save left the original sector unchanged")
                    (output / f"{model}-{selected.stem}-checkpoint.msav").write_bytes(checkpoint)
                    expected_backup = checkpoint
                    print(f"[PASS] {model}: manual dense save {len(checkpoint)} bytes", flush=True)
                sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_SAVED slot={selection}\b", save_timeout)
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 20)
                if backup.read_bytes() != expected_backup:
                    raise AssertionError(f"{model}: prior save is missing from the stock backup path")
                if multiple and source_zero is not None and zero.read_bytes() != source_zero:
                    raise AssertionError(f"{model}: selecting the second sector modified the Ground Zero save")
                saved = selected.read_bytes()
                if dense and multiple and saved == expected_backup:
                    raise AssertionError(f"{model}: the dense campaign save was left byte-for-byte unchanged")
                (output / f"{model}-{selected.name}").write_bytes(saved)
                if source_settings is not None:
                    if settings_backup.read_bytes() != source_settings:
                        raise AssertionError(f"{model}: the original PC settings backup was lost")
                    (output / f"{model}-{stage}-settings.bin").write_bytes(settings.read_bytes())
                print(f"[PASS] {model} {stage}: {selected.name} saved with stock backup, "
                      f"azahar_game_fps={stats.get('game_fps', 0):.2f}", flush=True)
            except (AssertionError, OSError, RuntimeError):
                # Preserve the original failure if the emulated app cannot answer RPC.
                if proc.poll() is None:
                    with suppress(OSError, RuntimeError, ValueError):
                        (output / f"{model}-{stage}-failure.png").write_bytes(rpc.screenshot())
                raise
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
    parser.add_argument("--model", choices=("new3ds",), default="new3ds")
    parser.add_argument("--output-dir", type=Path, default=Path("build/campaign-resume-emu"))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dense", action="store_true", help="Verify the original 600x600 Serpulo 242 sector")
    mode.add_argument("--dense-preset", choices=DENSE_PRESETS, help="Verify a named original 600x600 sector")
    mode.add_argument("--backup-only", action="store_true", help="Resume the second sector from its stock backup")
    mode.add_argument("--truncated-primary", action="store_true",
                      help="Resume with valid primary metadata and missing world data")
    parser.add_argument("--dense-resave", action="store_true", help="Save after reload and again on exit")
    parser.add_argument("--from-title", action="store_true", help="Open the named sector from Play → Campaign")
    parser.add_argument("--server", type=Path, help="Reopen each dense or recovered save with the original Java server")
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    preset = DENSE_PRESETS.get(args.dense_preset)
    dense = args.dense or preset is not None
    recovery = args.backup_only or args.truncated_primary
    if dense and (args.fixtures / "settings.bin").exists():
        parser.error("Dense replays require the stock fixtures without a settings override")
    if args.dense_resave and (not dense or args.server is None):
        parser.error("--dense-resave requires a dense sector and --server")
    if args.from_title and preset is None:
        parser.error("--from-title requires a named original sector")
    if preset is not None and args.server is None:
        parser.error("--dense-preset requires --server for original save readback")
    if args.server is not None and not (dense or recovery):
        parser.error("--server requires a dense or backup recovery replay")
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    model = args.model
    verify(model, args.rom, azahar, config, log, args.output_dir, args.fixtures, dense,
           resave=args.dense_resave, preset=preset, from_title=args.from_title,
           backup_only=args.backup_only, truncated_primary=args.truncated_primary)
    if preset is not None:
        if args.dense_resave:
            checkpoint = f"{Path(preset.slot).stem}-checkpoint.msav"
            check_dense_preset(args.java, args.server, args.output_dir / f"{model}-{checkpoint}", preset)
        check_dense_preset(args.java, args.server, args.output_dir / f"{model}-{preset.slot}", preset)
    elif args.server is not None and args.dense:
        from verify_stock_campaign_archive import check_saves

        if args.dense_resave:
            check_saves(args.java, args.server,
                        dense_save=args.output_dir / f"{model}-sector-serpulo-242-checkpoint.msav")
        check_saves(args.java, args.server, dense_save=args.output_dir / f"{model}-sector-serpulo-242.msav")
    elif args.server is not None:
        from verify_stock_campaign_archive import check_recovered_save

        check_recovered_save(args.java, args.server, args.fixtures / "sector-serpulo-64.msav",
                             args.output_dir / f"{model}-sector-serpulo-64.msav")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
