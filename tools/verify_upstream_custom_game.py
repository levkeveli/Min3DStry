#!/usr/bin/env python3
"""Check stock Custom Game selection, save, and resume on both Azahar profiles."""

from __future__ import annotations

import argparse
import re
import struct
import time
from pathlib import Path

import verify_upstream_singleplayer as sp


MAPS = {"caldera": "Ancient_Caldera", "archipelago": "Archipelago"}


def write_replay(path: Path, model: str, resume: bool, map_file: str) -> None:
    windows = [(0, 25, 1 << 9)]  # Hold L at boot.
    large_map = map_file == "archipelago"
    select_at = 48 if large_map else 33
    save_at = 112 if large_map else 61
    if resume:
        windows.append((select_at, select_at + 2, 1))  # A on the newly created save slot.
    else:
        if large_map:
            windows.append((select_at - 8, select_at - 6, 1 << 7))  # D-pad down from Ancient Caldera.
        windows += [(select_at - 5, select_at - 3, 1 << 4), (select_at, select_at + 2, 1),
                    (save_at, save_at + 2, sp.KEY_SELECT)]
    frames = sp.CTM_SAMPLES_PER_SECOND * (save_at + 11)
    header = struct.pack("<4sQ20sQQ32sIQq156x", b"CTM\x1b", 0, sp.CTM_REVISION, 1780000000,
                         0x4D334453, b"Min3DStry Custom Game", 0, frames, 0)
    touch_idle = struct.pack("<B6x", 1)
    with path.open("wb") as movie:
        movie.write(header)
        for frame in range(frames):
            second = frame // sp.CTM_SAMPLES_PER_SECOND
            buttons = next((mask for start, end, mask in windows if start <= second < end), 0)
            pad = struct.pack("<BHhh", 0, buttons, 0, 0)
            movie.write(pad + (pad if buttons else touch_idle))
            if model == "new3ds" and frame % 7 in (0, 2, 4):
                movie.write(pad)


def verify(model: str, rom: Path, azahar: str, config: Path, log: Path, output: Path,
           timeout: float, map_file: str) -> None:
    sd = (output / f"{model}-sd").resolve()
    root = sd / "3ds/min3dstry"
    root.mkdir(parents=True)
    save = root / "saves/0.msav"
    backup = save.with_name(f"{save.name}-backup.msav")
    backups = {p: p.read_bytes() if p.is_file() else None for p in (config, log)}
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.write_text(sp.az.patch_qt_config_text((backups[config] or b"").decode("utf-8"), storage),
                          encoding="utf-8")
        game_fps = {}
        for resume in (False, True):
            stage = "resume" if resume else "play"
            replay = output / f"{model}-{stage}.ctm"
            write_replay(replay, model, resume, map_file)
            proc, rpc = sp._launch_azahar(model, rom, azahar, config, log, replay)
            try:
                sp._wait_for_log(log, proc, r"UPSTREAM_CUSTOM_GAME_MENU maps=18\b", timeout)
                if not resume:
                    time.sleep(2)
                    menu = rpc.screenshot()
                    if sp.az.inspect_png(menu)["top_distinct_colors"] < 8:
                        raise AssertionError(f"{model}: expected a rendered Custom Game menu")
                    (output / f"{model}-menu.png").write_bytes(menu)
                if resume:
                    loaded = rf"UPSTREAM_SP_LOADED source=customgame-save map={MAPS[map_file]}\b"
                else:
                    loaded = rf"UPSTREAM_CUSTOM_GAME_STARTED map={re.escape(map_file)}\.msav mode=sandbox\b"
                sp._wait_for_log(log, proc, loaded, timeout)
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_FRAME ", timeout)
                rpc.perf_stats(reset=True)
                time.sleep(1.5)
                game_fps[stage] = (rpc.perf_stats(reset=True) or {}).get("game_fps", 0)
                shot = rpc.screenshot()
                info = sp.az.inspect_png(shot)
                if min(info["top_distinct_colors"], info["bottom_distinct_colors"]) < 8:
                    raise AssertionError(f"{model} {stage}: expected nonblank world and touch HUD")
                (output / f"{model}-{stage}.png").write_bytes(shot)
                if not resume:
                    sp._wait_for_log(log, proc, r"UPSTREAM_CUSTOM_GAME_SLOT file=0\.msav\b", timeout)
                    sp._wait_for_log(log, proc, r"UPSTREAM_SP_SAVED path=.*?/saves/0\.msav\b[\s\S]+"
                                               r"UPSTREAM_SP_SAVED path=.*?/saves/0\.msav\b", timeout)
                    if not backup.is_file():
                        raise AssertionError(f"{model}: SELECT did not update the initial slot with a stock backup")
                    if not save.is_file() or not save.stat().st_size:
                        raise AssertionError(f"{model}: the Custom Game save was not written")
                    (output / f"{model}-sandbox.msav").write_bytes(save.read_bytes())
            finally:
                rpc.close()
                sp._stop_proc(proc, azahar)
                trace = log.read_text(encoding="utf-8", errors="replace")
                (output / f"{model}-{stage}.log").write_text(trace, encoding="utf-8")
        print(f"[PASS] {model}: stock maps=18 {MAPS[map_file]} sandbox save=ok resume=ok "
              f"azahar_game_fps={game_fps['play']:.2f}/{game_fps['resume']:.2f}", flush=True)
    finally:
        for path, original in backups.items():
            if original is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(original)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--azahar")
    parser.add_argument("--model", choices=("new3ds", "old3ds"))
    parser.add_argument("--map", choices=MAPS, default="caldera")
    parser.add_argument("--output-dir", type=Path, default=Path("build/custom-game-emu"))
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for model in (args.model,) if args.model else ("new3ds", "old3ds"):
        verify(model, args.rom, azahar, config, log, args.output_dir, args.timeout, args.map)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
