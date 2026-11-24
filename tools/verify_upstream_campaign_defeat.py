#!/usr/bin/env python3
"""Replay stock campaign defeat and retry, and verify the saves in unmodified Mindustry."""

from __future__ import annotations

import argparse
import re
import shutil
import time
import tempfile
from pathlib import Path

import verify_upstream_singleplayer as sp
from campaign_settings_fixture import stock_console
from verify_upstream_campaign_launch import write_input_replay

CASES = {
    "serpulo": ("groundZero", "sector-serpulo-170.msav", "copperWall"),
    "erekir": ("onset", "sector-erekir-10.msav", "berylliumWall"),
}


def check(java: str, server: Path, planet: str, world: Path, settings: Path,
          x: int, y: int, spawn: int, recovered: bool) -> None:
    preset, _, block = CASES[planet]
    has_core = str(recovered).lower()
    keep_derelict = str(recovered and planet == "serpulo").lower()
    attempts = 2 if recovered else 1
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-defeat-") as temp:
        root = Path(temp)
        (root / "config").mkdir()
        shutil.copyfile(settings, root / "config/settings.bin")
        shutil.copyfile(world, root / "sector.msav")
        script = f"""
            const sector = Packages.mindustry.content.SectorPresets.{preset}.sector;
            const io = Packages.mindustry.io.SaveIO, slot = Packages.arc.files.Fi.get("sector.msav");
            sector.loadInfo();
            if (sector.info.hasCore !== {has_core} || sector.info.attempts !== {attempts}) {{
                throw new Error("Wrong stored campaign core or retry count");
            }}
            io.load(slot, Vars.world.makeSectorContext(sector));
            const core = Vars.state.rules.defaultTeam.core();
            if (!io.getMeta(slot).rules.sector.equals(sector) || (core != null) !== {has_core}) {{
                throw new Error("Wrong sector or core after load");
            }}
            const building = Vars.world.build({x}, {y});
            const wall = Packages.mindustry.content.Blocks.{block};
            const derelict = building != null && building.block.equals(wall)
                && building.team.equals(Packages.mindustry.game.Team.derelict);
            if (derelict !== {keep_derelict} || ({keep_derelict} && core.pos() !== {spawn})) {{
                throw new Error("Wrong stock rebuilt terrain or spawn");
            }}
            return "UPSTREAM_PC_DEFEAT_REOPENED";
        """
        stock_console(java, server, root, " ".join(script.splitlines()), r"UPSTREAM_PC_DEFEAT_REOPENED")
    name = "recovered" if recovered else "defeated"
    print(f"[PASS] stock v160.8 reopened {name} {planet} sector and settings", flush=True)


def verify(model: str, planet: str, rom: Path, azahar: str, config: Path, log: Path,
           reference: Path, output: Path) -> Path:
    _, name, _ = CASES[planet]
    sector_id = Path(name).stem.rsplit("-", 1)[1]
    result = output / f"{model}-{planet}"
    sd = (result / "virtual-sd").resolve()
    root = sd / "3ds/min3dstry"
    saves = root / "saves"
    slot = saves / name
    settings = root / "settings.bin"
    initial_config = config.read_bytes() if config.is_file() else None
    saves.mkdir(parents=True)
    sp.stage_direct_startup(sd)
    movie = result / "controls.ctm"
    write_input_replay(movie, model, [(88, 90, 1), (108, 110, 1), (220, 222, sp.KEY_START)], 220,
                       b"Min3DStry Campaign Defeat")
    threatened = reference / "threatened" / planet
    try:
        shutil.copyfile(threatened / name, slot)
        shutil.copyfile(threatened / "settings.bin", settings)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(sp.az.patch_qt_config_text((initial_config or b"").decode("utf-8"), storage),
                          encoding="utf-8")
        proc, rpc = sp._launch_azahar(model, rom, azahar, config, log, movie)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_LOADED source=campaign\b", 120)
            sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_DEFEAT sector={sector_id}\b", 95)
            lost = result / "lost"
            lost.mkdir(exist_ok=True)
            shutil.copyfile(slot, lost / name)
            shutil.copyfile(settings, lost / "settings.bin")

            time.sleep(1)
            screenshot = rpc.screenshot()
            if sp.az.inspect_png(screenshot)["top_distinct_colors"] < 8:
                raise AssertionError(f"{model} {planet}: the defeat screen did not render")
            (result / "defeat.png").write_bytes(screenshot)
            sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_WORLD_MENU sector={sector_id}\b", 100)
            sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_GENERATION_DONE planet={planet} sector={sector_id}\b", 190)
            sp._wait_for_log(log, proc, rf"UPSTREAM_CAMPAIGN_SAVED slot={re.escape(name)}\b", 200)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 30)
            shutil.copyfile(slot, result / name)
            shutil.copyfile(settings, result / "settings.bin")
            return result
        finally:
            rpc.close()
            sp._stop_proc(proc, azahar)
            (result / "azahar.log").write_bytes(log.read_bytes())
    finally:
        if initial_config is None:
            config.unlink(missing_ok=True)
        else:
            config.write_bytes(initial_config)

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--rom", type=Path, help="Run the threatened-sector fixture in Azahar before stock readback")
    parser.add_argument("--azahar")
    parser.add_argument("--model", choices=("new3ds", "old3ds"))
    parser.add_argument("--planet", choices=CASES)
    parser.add_argument("--output-dir", type=Path, default=Path("build/campaign-defeat-emu"))
    args = parser.parse_args()

    def readback(planet: str, lost: Path, save: Path, settings: Path) -> None:
        _, name, _ = CASES[planet]
        placement = args.reference / "lost" / planet / "placement.txt"
        x, y, spawn = (int(value) for value in placement.read_text().split())
        check(args.java, args.server, planet, lost / name, lost / "settings.bin", x, y, spawn, False)
        check(args.java, args.server, planet, save, settings, x, y, spawn, True)

    planets = (args.planet,) if args.planet else CASES
    if not args.rom:
        for planet in planets:
            _, name, _ = CASES[planet]
            readback(planet, args.reference / "lost" / planet, args.reference / "saves" / name,
                     args.reference / "settings.bin")
        return

    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    for model in (args.model,) if args.model else ("new3ds", "old3ds"):
        for planet in planets:
            _, name, _ = CASES[planet]
            result = verify(model, planet, args.rom, azahar, config, log, args.reference, args.output_dir)
            readback(planet, result / "lost", result / name, result / "settings.bin")
            print(f"[PASS] {model} {planet} campaign defeat and retry", flush=True)

if __name__ == "__main__":
    main()
