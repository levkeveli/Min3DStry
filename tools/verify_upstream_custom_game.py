#!/usr/bin/env python3
"""Check stock Custom Game selection, save, and resume on both Azahar profiles."""

from __future__ import annotations

import argparse
import struct
import time
from pathlib import Path

import verify_upstream_singleplayer as sp


def write_replay(path: Path, model: str, resume: bool) -> None:
    windows = [(0, 25, 1 << 9)]  # Hold L at boot.
    if resume:
        windows.append((33, 35, 1))  # A on Load Game.
    else:
        windows += [(28, 30, 1 << 4), (33, 35, 1), (61, 63, sp.KEY_SELECT)]
    frames = sp.CTM_SAMPLES_PER_SECOND * 72
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


def verify(model: str, rom: Path, azahar: str, config: Path, log: Path, output: Path, timeout: float) -> None:
    save = sp.resolve_sd_save_path(log).with_name("custom-game-save.msav")
    if any(save.with_name("maps").glob("*.msav")):
        raise RuntimeError("Use an Azahar SD directory without user maps for the stock catalog test")
    backup = save.with_name(f"{save.name}-backup.msav")
    backups = {p: p.read_bytes() if p.is_file() else None for p in (config, save, backup)}
    save.parent.mkdir(parents=True, exist_ok=True)
    save.unlink(missing_ok=True)
    backup.unlink(missing_ok=True)
    try:
        game_fps = {}
        for resume in (False, True):
            stage = "resume" if resume else "play"
            replay = output / f"{model}-{stage}.ctm"
            write_replay(replay, model, resume)
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
                    loaded = r"UPSTREAM_SP_LOADED source=customgame-save map=Ancient_Caldera\b"
                else:
                    loaded = r"UPSTREAM_CUSTOM_GAME_STARTED map=caldera\.msav mode=sandbox\b"
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
                    sp._wait_for_log(log, proc, r"UPSTREAM_SP_SAVED path=\S*custom-game-save\.msav\b", timeout)
                    if not save.is_file() or not save.stat().st_size:
                        raise AssertionError(f"{model}: the Custom Game save was not written")
                    (output / f"{model}-sandbox.msav").write_bytes(save.read_bytes())
            finally:
                rpc.close()
                sp._stop_proc(proc, azahar)
                trace = log.read_text(encoding="utf-8", errors="replace")
                (output / f"{model}-{stage}.log").write_text(trace, encoding="utf-8")
        print(f"[PASS] {model}: stock maps=18 Ancient Caldera sandbox save=ok resume=ok "
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
    parser.add_argument("--output-dir", type=Path, default=Path("build/custom-game-emu"))
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for model in (args.model,) if args.model else ("new3ds", "old3ds"):
        verify(model, args.rom, azahar, config, log, args.output_dir, args.timeout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
