#!/usr/bin/env python3
"""Verify overlapping stock enemy, turret and conveyor activity in New 3DS Azahar."""

import argparse
from pathlib import Path
import re
import shutil
import tempfile

import verify_upstream_singleplayer as singleplayer

ACTIVE_WORKLOAD = (
    r"UPSTREAM_PLAYABLE_LOAD window=\d+ wave=\d+ enemies=(?:[2-9]\d|[1-9]\d{2,})"
    r" shooting_turrets=[1-9]\d* turret_projectiles=[1-9]\d* belt_items=[1-9]\d*"
    r" camera_tile=-?\d+,-?\d+"
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--timeout", type=float, default=240)
    args = parser.parse_args()

    az = singleplayer.az
    azahar = az.resolve_azahar_bin(args.azahar)
    config, log = az.resolve_azahar_paths(azahar)
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="m3ds-playable-load-") as temp:
        sd = Path(temp).resolve()
        game_dir = sd / "3ds/min3dstry"
        game_dir.mkdir(parents=True)
        shutil.copyfile(args.fixtures / "singleplayer-save.msav", game_dir / "singleplayer-save.msav")
        shutil.copyfile(args.fixtures / "benchmark.txt", game_dir / "benchmark.txt")
        (game_dir / "startup.txt").write_text("direct\n", encoding="ascii")
        try:
            config.parent.mkdir(parents=True, exist_ok=True)
            storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
            config.write_text(az.patch_qt_config_text((originals[config] or b"").decode("utf-8"), storage),
                              encoding="utf-8")
            proc, rpc = singleplayer._launch_azahar("new3ds", args.rom, azahar, config, log)
            try:
                singleplayer._wait_for_log(log, proc, r"UPSTREAM_SP_LOADED source=save map=(?i:ground_?zero)\b",
                                           args.timeout)
                trace = singleplayer._wait_for_log(log, proc, ACTIVE_WORKLOAD, args.timeout)
                match = re.search(ACTIVE_WORKLOAD, trace)
                singleplayer.verify_playable_benchmark(trace[:match.end()])
                (args.output / "new3ds-active-workload.png").write_bytes(rpc.screenshot())
                print(match.group(0), flush=True)
            finally:
                rpc.close()
                singleplayer._stop_proc(proc, azahar)
                if log.is_file():
                    shutil.copyfile(log, args.output / "new3ds-playable-benchmark.log")
        finally:
            for path, previous in originals.items():
                if previous is None:
                    path.unlink(missing_ok=True)
                else:
                    path.write_bytes(previous)


if __name__ == "__main__":
    main()
