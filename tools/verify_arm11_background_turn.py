#!/usr/bin/env python3
"""Run a complete stock background turn in a dedicated ARM11 simulation probe."""

import argparse
from pathlib import Path
import shutil

from campaign_background_fixture import check, check_remote_saves, create
import verify_upstream_singleplayer as sp


def verify(args, model, azahar, config, log, fixture):
    output = args.output / model
    sd = (output / "virtual-sd").resolve()
    directory = sd / "3ds/min3dstry/background-turn"
    shutil.copytree(fixture, directory / "fixture")
    assets = directory / "assets"
    shutil.copytree(args.assets / "planets", assets / "planets")
    shutil.copytree(args.assets / "maps", assets / "maps")
    (assets / "bundles").mkdir()
    shutil.copyfile(args.assets / "bundles/bundle.properties", assets / "bundles/bundle.properties")

    original = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.write_text(sp.az.patch_qt_config_text((original[config] or b"").decode("utf-8"), storage),
                          encoding="utf-8")
        proc, rpc = sp._launch_azahar(model, args.rom, azahar, config, log)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_BACKGROUND_TURN live=37 import=30 remote=100 lead=0 frozen=11", 420)
            result = directory / "result"
            check_remote_saves(fixture, result)
            check(args.java, args.server, result)
            print(f"[PASS] {model}: stock Java read back the original ARM11 background turn", flush=True)
        finally:
            rpc.close()
            sp._stop_proc(proc, azahar)
            (output / "azahar.log").write_bytes(log.read_bytes())
    finally:
        for path, data in original.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True, help="pinned stock maps, planets, and bundles")
    parser.add_argument("--azahar")
    parser.add_argument("--model", choices=("old3ds", "new3ds"), action="append")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    fixture = args.output / "fixture"
    create(args.java, args.server, fixture)
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    for model in args.model or ("old3ds", "new3ds"):
        verify(args, model, azahar, config, log, fixture)


if __name__ == "__main__":
    main()
