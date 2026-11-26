#!/usr/bin/env python3
"""Create stock v160.8 campaign resume fixtures with the pinned server."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import shutil
import subprocess
import tempfile
from pathlib import Path

from campaign_settings_fixture import stock_console
from verify_upstream_live_net import SERVER_SHA256, stop, wait_for


@dataclass(frozen=True)
class DensePreset:
    planet: str
    sector: int
    map_name: str
    patch_count: int
    core: str

    @property
    def slot(self) -> str:
        return f"sector-{self.planet}-{self.sector}.msav"


DENSE_PRESETS = {
    "littoral-shipyard": DensePreset("serpulo", 204, "littoralShipyard", 2, "core-shard"),
    "origin": DensePreset("erekir", 12, "origin", 0, "core-acropolis"),
}


def _assert_dense_world(preset: DensePreset) -> str:
    return f"""
        const rules = Vars.state.rules, core = rules.defaultTeam.core();
        if (String(rules.sector.planet.name) !== "{preset.planet}" || rules.sector.id !== {preset.sector} ||
            String(Vars.state.map.name()) !== "{preset.map_name}" || Vars.world.width() !== 600 ||
            Vars.world.height() !== 600 || Vars.state.data.getPatches().size !== {preset.patch_count} ||
            core === null || String(core.block.name) !== "{preset.core}")
            throw new Error("Wrong original dense sector, map, dimensions, patches or core");
    """


def _check_stock_jar(server: Path) -> None:
    with server.open("rb") as file:
        if hashlib.file_digest(file, "sha256").hexdigest() != SERVER_SHA256:
            raise ValueError("Expected the official v160.8 server-release.jar")


def create_dense_preset(java: str, server: Path, output: Path, preset: DensePreset) -> None:
    _check_stock_jar(server)
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-dense-preset-") as temp:
        root = Path(temp)
        marker = f"STOCK_DENSE_PRESET_WRITTEN sector={preset.planet}:{preset.sector}"
        script = f"""
            const sector = Packages.mindustry.content.SectorPresets.{preset.map_name}.sector;
            Vars.logic.reset();
            Vars.world.loadSector(sector);
            Vars.logic.play();
            {_assert_dense_world(preset)}
            Packages.mindustry.io.SaveIO.write(Packages.arc.files.Fi.get("{preset.slot}"));
            return "{marker}";
        """
        stock_console(java, server, root, " ".join(script.splitlines()), marker)
        shutil.copyfile(root / preset.slot, output / preset.slot)
    print(marker, flush=True)


def check_dense_preset(java: str, server: Path, save: Path, preset: DensePreset) -> None:
    _check_stock_jar(server)
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-dense-readback-") as temp:
        root = Path(temp)
        shutil.copyfile(save, root / preset.slot)
        marker = f"STOCK_DENSE_PRESET_READBACK sector={preset.planet}:{preset.sector} map={preset.map_name}"
        script = f"""
            const sector = Packages.mindustry.content.SectorPresets.{preset.map_name}.sector;
            const file = Packages.arc.files.Fi.get("{preset.slot}");
            Packages.mindustry.io.SaveIO.load(file, Vars.world.makeSectorContext(sector));
            {_assert_dense_world(preset)}
            return "{marker}";
        """
        stock_console(java, server, root, " ".join(script.splitlines()), marker)
    print(marker, flush=True)


def create(java: str, server: Path, output: Path, dense: bool = False, *, globe_lights: bool = False) -> None:
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-resume-") as temp:
        root = Path(temp)
        log = root / "server.log"
        with log.open("w", encoding="utf-8") as stream:
            proc = subprocess.Popen([java, "-jar", str(server.resolve())], cwd=root, stdin=subprocess.PIPE,
                                    stdout=stream, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
            try:
                wait_for(log, proc, r"Server loaded\.", 30)
                presets = "Packages.mindustry.content.SectorPresets"
                dense_sector = "Packages.mindustry.content.Planets.serpulo.sectors.get(242)"
                secondary = (dense_sector, 242) if dense else (f"{presets}.frozenForest.sector", 64)
                ground_zero = (f"{presets}.groundZero.sector", 170)
                cases = (ground_zero,) if globe_lights else (secondary, ground_zero)
                captured = """
                    Vars.state.rules.waves = false;
                    Vars.state.rules.attackMode = false;
                    sector.info.hasCore = true;
                    sector.info.waves = false;
                    sector.info.attack = false;
                    sector.info.wasCaptured = true;
                    sector.info.lightCoverage = 50000;
                    sector.info.contentIcon = Packages.mindustry.content.Items.copper;
                    sector.saveInfo();
                    Packages.arc.Core.settings.forceSave();
                """ if globe_lights else ""
                for expression, sector in cases:
                    name = f"sector-serpulo-{sector}.msav"
                    script = f"""
                        const sector = {expression};
                        if (sector.id !== {sector}) throw new Error("Unexpected preset assignment");
                        Vars.logic.reset();
                        Vars.world.loadSector(sector);
                        Vars.logic.play();
                        if ({sector} === 242 && (Vars.world.width() !== 600 || Vars.world.height() !== 600 ||
                            Vars.state.data.getPatches().size !== 1)) throw new Error("Unexpected Serpulo 242 fixture");
                        {captured}
                        Packages.mindustry.io.SaveIO.write(Packages.arc.files.Fi.get("{name}"));
                        return "UPSTREAM_STOCK_RESUME_{sector}";
                    """
                    proc.stdin.write("js (() => { " + " ".join(script.splitlines()) + " })()\n")
                    proc.stdin.flush()
                    wait_for(log, proc, rf"UPSTREAM_STOCK_RESUME_{sector}\b", 30)
                    shutil.copyfile(root / name, output / name)
                if globe_lights:
                    shutil.copyfile(root / "config/settings.bin", output / "settings.bin")
            finally:
                stop(proc)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dense", action="store_true", help="Use the original Serpulo 242 sector instead of Frozen Forest")
    mode.add_argument("--dense-preset", choices=DENSE_PRESETS, help="Write one named 600x600 original sector")
    args = parser.parse_args()
    if args.dense_preset:
        create_dense_preset(args.java, args.server, args.output, DENSE_PRESETS[args.dense_preset])
    else:
        create(args.java, args.server, args.output, args.dense)


if __name__ == "__main__":
    main()
