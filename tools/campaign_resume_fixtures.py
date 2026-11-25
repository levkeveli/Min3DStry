#!/usr/bin/env python3
"""Create stock v160.8 campaign resume fixtures with the pinned server."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path

from verify_upstream_live_net import stop, wait_for


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
    parser.add_argument("--dense", action="store_true", help="Use the original Serpulo 242 sector instead of Frozen Forest")
    args = parser.parse_args()
    create(args.java, args.server, args.output, args.dense)


if __name__ == "__main__":
    main()
