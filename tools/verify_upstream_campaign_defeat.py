#!/usr/bin/env python3
"""Reopen defeated and recovered campaign saves in the unmodified v160.8 server."""

from __future__ import annotations

import argparse
import shutil
import tempfile
from pathlib import Path

from campaign_settings_fixture import stock_console

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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    args = parser.parse_args()
    for planet, (_, name, _) in CASES.items():
        lost = args.reference / "lost" / planet
        x, y, spawn = (int(value) for value in (lost / "placement.txt").read_text().split())
        check(args.java, args.server, planet, lost / name, lost / "settings.bin", x, y, spawn, False)
        check(args.java, args.server, planet, args.reference / "saves" / name,
              args.reference / "settings.bin", x, y, spawn, True)


if __name__ == "__main__":
    main()
