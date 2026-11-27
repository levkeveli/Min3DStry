#!/usr/bin/env python3
"""Replay original Ground Zero saves with stock ambient light and a stock emitter."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path

import verify_azahar as az
from verify_upstream_live_net import (
    WORLD_LIGHT_AMBIENT, WORLD_LIGHT_GROUND_ZERO_TILE, WORLD_LIGHT_POINTS, WORLD_LIGHT_RED,
    capture_marker_pixels, stop, wait_for, world_light_composite, world_light_terrain,
)
from verify_upstream_singleplayer import _launch_azahar, _stop_proc, _wait_for_log, stage_direct_startup


PHASES = ("clear", "ambient", "emitter")


def create_fixtures(server: Path, output: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-light-") as temp:
        work = Path(temp)
        for phase in PHASES:
            (output / phase).mkdir(parents=True, exist_ok=True)
        server_log = output / "server.log"
        with server_log.open("w", encoding="utf-8") as log:
            proc = subprocess.Popen(
                ["java", "-jar", str(server.resolve())], cwd=work, stdin=subprocess.PIPE,
                stdout=log, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
            )
            try:
                wait_for(server_log, proc, r"Server loaded\.", 30)
                tile_x, tile_y = WORLD_LIGHT_GROUND_ZERO_TILE
                x, y = tile_x * 8, tile_y * 8
                commands = f"""
                    const sector = Packages.mindustry.content.SectorPresets.groundZero.sector;
                    Vars.logic.reset();
                    Vars.world.loadSector(sector);
                    Vars.logic.play();
                    const rules = Vars.state.rules;
                    rules.waves = false;
                    rules.waveTimer = false;
                    rules.unitLight = false;
                    {world_light_terrain(tile_x, tile_y)}
                    const unit = UnitTypes.alpha.create(rules.defaultTeam);
                    unit.spawnedByCore(true);
                    unit.set({x}, {y});
                    unit.add();
                    const save = name => Packages.mindustry.io.SaveIO.write(Packages.arc.files.Fi.get(name + ".msav"));
                    rules.lighting = false;
                    save("clear");
                    rules.ambientLight.set(Color.valueOf("{WORLD_LIGHT_AMBIENT}"));
                    rules.lighting = true;
                    save("ambient");
                    const marker = new MapObjectives.LightMarker({x + 24}, {y - 24}, 5.5,
                        Color.valueOf("{WORLD_LIGHT_RED}"));
                    marker.world = -1;
                    marker.light = 0;
                    Vars.state.markers.add(758, marker);
                    save("emitter");
                    return "UPSTREAM_STOCK_LIGHT_SAVES";
                """
                proc.stdin.write("js (() => { " + " ".join(commands.splitlines()) + " })()\n")
                proc.stdin.flush()
                wait_for(server_log, proc, r"UPSTREAM_STOCK_LIGHT_SAVES\b", 30)
                for phase in PHASES:
                    shutil.copyfile(work / f"{phase}.msav", output / phase / "singleplayer-save.msav")
            finally:
                stop(proc)


def verify(rom: Path, azahar: str, output: Path, fixtures: Path, timeout: float) -> None:
    config, log = az.resolve_azahar_paths(azahar)
    original = config.read_bytes() if config.exists() else None
    baseline = None
    try:
        for phase in PHASES:
            sd = (output / "virtual-sd" / phase).resolve()
            stage_direct_startup(sd)
            shutil.copyfile(fixtures / phase / "singleplayer-save.msav",
                            sd / "3ds/min3dstry/singleplayer-save.msav")
            storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
            config.parent.mkdir(parents=True, exist_ok=True)
            config.write_text(az.patch_qt_config_text((original or b"").decode("utf-8"), storage),
                              encoding="utf-8")
            proc, rpc = _launch_azahar("new3ds", rom, azahar, config, log)
            try:
                _wait_for_log(log, proc, r"UPSTREAM_SP_LOADED source=save map=groundZero size=256x256\b", timeout)
                tile_x, tile_y = WORLD_LIGHT_GROUND_ZERO_TILE
                viewport = rf"UPSTREAM_SP_FRAME unit=\d+ left={tile_x - 8} bottom={tile_y - 6}\b"
                _wait_for_log(log, proc, viewport, timeout)
                expected = None
                if baseline is not None:
                    light = (WORLD_LIGHT_RED,) if phase == "emitter" else ()
                    expected = (world_light_composite(baseline[0], *light), world_light_composite(baseline[1]))
                pixels = capture_marker_pixels(rpc, output, "new3ds", f"ground-zero-light-{phase}",
                                               WORLD_LIGHT_POINTS, timeout, expected, tolerance=6)
                if baseline is None:
                    if not all(any(pixel) for pixel in pixels):
                        raise RuntimeError(f"Ground Zero clear reference is obscured: {pixels}")
                    baseline = pixels
                print(f"[PASS] Ground Zero {phase} stock world light samples={pixels}", flush=True)
            finally:
                rpc.close()
                _stop_proc(proc, azahar)
                shutil.copyfile(log, output / f"new3ds-ground-zero-light-{phase}.log")
    finally:
        if original is None:
            config.unlink(missing_ok=True)
        else:
            config.write_bytes(original)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=90)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="m3ds-light-saves-") as temporary:
        fixtures = Path(temporary)
        create_fixtures(args.server, fixtures)
        verify(args.rom, az.resolve_azahar_bin(args.azahar), args.output, fixtures, args.timeout)


if __name__ == "__main__":
    main()
