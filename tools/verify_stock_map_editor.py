#!/usr/bin/env python3
"""Replay the stock ARM11 map editor and inspect its export in unmodified Java."""

import argparse
from pathlib import Path
import re
import shutil
import tempfile

from campaign_settings_fixture import stock_console
from verify_upstream_campaign_launch import write_input_replay
import verify_upstream_singleplayer as sp


def compare_stock(args, exported, x, y, original, replacement):
    with tempfile.TemporaryDirectory(prefix="m3ds-editor-readback-") as temp:
        directory = Path(temp)
        shutil.copyfile(args.map, directory / "original.msav")
        shutil.copyfile(exported, directory / "edited.msav")
        script = f"""
            const Fi = Packages.arc.files.Fi, MapIO = Packages.mindustry.io.MapIO;
            const load = name => {{
                Vars.logic.reset();
                const map = MapIO.createMap(Fi.get(name), true);
                Vars.world.loadMap(map, map.applyRules(Packages.mindustry.game.Gamemode.sandbox));
                return map;
            }};
            const original = load('original.msav');
            if (String(Vars.world.tile({x},{y}).floor().name) !== '{original}')
                throw 'Unexpected original floor';
            const edited = load('edited.msav');
            if (edited.width !== original.width || edited.height !== original.height ||
                String(edited.name()) !== String(original.name()) ||
                String(Vars.world.tile({x},{y}).floor().name) !== '{replacement}')
                throw 'Unexpected exported map';
            return 'STOCK_EDITOR_EXPORT_REOPENED';
        """
        stock_console(args.java, args.server, directory, " ".join(script.splitlines()), "STOCK_EDITOR_EXPORT_REOPENED")


def replay(args, model, azahar, config, log):
    output = args.output / model
    sd = (output / "virtual-sd").resolve()
    root = sd / "3ds/min3dstry"
    root.mkdir(parents=True)
    source = root / "editor-map.msav"
    shutil.copyfile(args.map, source)
    movie = output / "editor.ctm"
    write_input_replay(movie, model, [
        (0, 24, (1 << 8) | (1 << 9)),
        (44, 46, 1 << 8),
        (53, 55, 1),
        (69, 71, 1 << 10),
        (83, 85, 1 << 11),
        (95, 97, sp.KEY_SELECT),
        (105, 107, sp.KEY_START),
    ], 109, b"Min3DStry Map Editor")
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.write_text(sp.az.patch_qt_config_text((originals[config] or b"").decode("utf-8"), storage),
                          encoding="utf-8")
        emulator, rpc = sp._launch_azahar(model, args.rom, azahar, config, log, movie)
        try:
            sp._wait_for_log(log, emulator, r"UPSTREAM_EDITOR_OPEN source=editor-map\.msav width=150 height=250", 150)
            sp._wait_for_log(log, emulator, r"UPSTREAM_INTERFACE_GRAPHICS ready", 150)
            (output / "before.png").write_bytes(rpc.screenshot())
            undo = sp._wait_for_log(log, emulator,
                                   r"UPSTREAM_EDITOR_STROKE action=undo x=75 y=125 floor=([\w-]+)", 190)
            redo = sp._wait_for_log(log, emulator,
                                   r"UPSTREAM_EDITOR_STROKE action=redo x=75 y=125 floor=([\w-]+)", 110)
            (output / "edited.png").write_bytes(rpc.screenshot())
            sp._wait_for_log(log, emulator, r"UPSTREAM_EDITOR_EXPORTED file=Edited-1\.msav", 90)
        finally:
            rpc.close()
            sp._stop_proc(emulator, azahar)
            (output / "editor.log").write_bytes(log.read_bytes())
        original = re.search(r"floor=([\w-]+)", undo.split("action=undo x=75 y=125")[-1]).group(1)
        replacement = re.search(r"floor=([\w-]+)", redo.split("action=redo x=75 y=125")[-1]).group(1)
        if original == replacement:
            raise AssertionError("The pencil left the stock Glacier floor unchanged")
        if source.read_bytes() != args.map.read_bytes():
            raise AssertionError("Export wrote over the input map")
        compare_stock(args, root / "maps/Edited-1.msav", 75, 125, original, replacement)
        print(f"[PASS] {model}: stock Glacier floor {original} -> {replacement}, undo/redo and Java export",
              flush=True)
    finally:
        for path, data in originals.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--map", type=Path, required=True, help="original Glacier .msav")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--model", action="append", choices=("old3ds", "new3ds"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    for model in args.model or ("old3ds", "new3ds"):
        replay(args, model, azahar, config, log)


if __name__ == "__main__":
    main()
