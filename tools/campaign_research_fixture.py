#!/usr/bin/env python3
"""Generate and reopen research fixtures with the unmodified v160.8 server."""

from __future__ import annotations

import argparse
import re
import shutil
import tempfile
from pathlib import Path

from campaign_settings_fixture import stock_console

CASES = {
    "serpulo": ("groundZero", "sector-serpulo-170.msav", "copper", 3, "conveyor", 3, 0, False),
    "erekir": ("onset", "sector-erekir-10.msav", "beryllium", 8, "duct", 4, 4, True),
}


def create(java: str, server: Path, output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    for planet, (preset, name, item, amount, _, _, _, _) in CASES.items():
        with tempfile.TemporaryDirectory(prefix="m3ds-stock-research-") as temp:
            root = Path(temp)
            script = f"""
                const sector = Packages.mindustry.content.SectorPresets.{preset}.sector;
                const io = Packages.mindustry.io.SaveIO;
                Vars.logic.reset();
                Vars.world.loadSector(sector);
                Vars.logic.play();
                const storage = Vars.state.rules.defaultTeam.items();
                storage.clear();
                const resource = Packages.mindustry.content.Items.{item};
                resource.unlock();
                storage.set(resource, {amount});
                const file = Packages.arc.files.Fi.get("{name}");
                io.write(file);
                Packages.arc.Core.settings.forceSave();
                if (!io.getMeta(file).rules.sector.equals(sector)) throw new Error("Wrong sector");
                Packages.arc.files.Fi.get("config/settings.bin").copyTo(
                    Packages.arc.files.Fi.get("{planet}-settings.bin"));
                if ("{planet}" === "serpulo") {{
                    sector.info.hasCore = true;
                    sector.info.waves = false;
                    sector.info.attack = false;
                    sector.info.storageCapacity = 1000;
                    sector.info.items.set(resource, {amount});
                    sector.saveInfo();
                    Packages.mindustry.content.Planets.erekir.techTree.content.unlock();
                    Packages.arc.Core.settings.forceSave();
                    Packages.arc.files.Fi.get("config/settings.bin").copyTo(
                        Packages.arc.files.Fi.get("serpulo-globe-settings.bin"));
                }}
                return "UPSTREAM_PC_RESEARCH_FIXTURE";
            """
            stock_console(java, server, root, " ".join(script.splitlines()), r"UPSTREAM_PC_RESEARCH_FIXTURE")
            shutil.copyfile(root / name, output / name)
            shutil.copyfile(root / f"{planet}-settings.bin", output / f"{planet}-settings.bin")
            if planet == "serpulo":
                shutil.copyfile(root / "serpulo-globe-settings.bin", output / "serpulo-globe-settings.bin")
            print(f"[PASS] stock v160.8 created {planet} research fixture", flush=True)


def check(java: str, server: Path, planet: str, world: Path, settings: Path, *, globe: bool = False) -> None:
    preset, name, item, _, block, amount, remaining, unlocked = CASES[planet]
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-research-readback-") as temp:
        root = Path(temp)
        (root / "config").mkdir()
        shutil.copyfile(settings, root / "config/settings.bin")
        shutil.copyfile(world, root / name)
        script = f"""
            const sector = Packages.mindustry.content.SectorPresets.{preset}.sector;
            const settings = Packages.arc.Core.settings;
            const io = Packages.mindustry.io.SaveIO;
            const slot = Packages.arc.files.Fi.get("{name}");
            const item = Packages.mindustry.content.Items.{item};
            let inventory;
            if ({str(globe).lower()}) {{
                sector.loadInfo();
                inventory = sector.info.items.get(item);
            }} else {{
                io.load(slot, Vars.world.makeSectorContext(sector));
                inventory = Vars.state.rules.defaultTeam.items().get(item);
            }}
            if (!io.getMeta(slot).rules.sector.equals(sector)
                || inventory !== {remaining}
                || settings.getInt("req-{block}-{item}", -1) !== {amount}
                || settings.getBool("{block}-unlocked", false) !== {str(unlocked).lower()}) {{
                throw new Error("Unexpected sector inventory or research state");
            }}
            return "UPSTREAM_PC_RESEARCH_REOPENED";
        """
        stock_console(java, server, root, " ".join(script.splitlines()), r"UPSTREAM_PC_RESEARCH_REOPENED")
    source = "saved sector inventory" if globe else "live world inventory"
    print(f"[PASS] stock v160.8 reopened {planet} {source} and research settings", flush=True)


def check_progression(java: str, server: Path, output: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-research-progression-") as temp:
        root = Path(temp)
        (root / "config").mkdir()
        shutil.copyfile(output / "settings.bin", root / "config/settings.bin")
        for sector in (170, 64):
            name = f"sector-serpulo-{sector}.msav"
            shutil.copyfile(output / "saves" / name, root / name)
        script = """
            const presets = Packages.mindustry.content.SectorPresets;
            const blocks = Packages.mindustry.content.Blocks;
            const from = presets.groundZero.sector, to = presets.frozenForest.sector;
            const io = Packages.mindustry.io.SaveIO, fi = Packages.arc.files.Fi;
            from.loadInfo();
            to.loadInfo();
            if (!presets.frozenForest.unlocked() || !blocks.router.unlocked() || !blocks.junction.unlocked()
                || !from.info.wasCaptured || !to.info.origin.equals(from) ||
                String(Packages.arc.Core.settings.getString("last-sector-save", "")) !== "sector-serpulo-64") {
                throw new Error("The original unlock and capture did not round-trip");
            }
            io.load(fi.get("sector-serpulo-170.msav"), Vars.world.makeSectorContext(from));
            if (Vars.state.rules.waves || !Vars.state.rules.sector.equals(from)) {
                throw new Error("Ground Zero lost its capture state");
            }
            io.load(fi.get("sector-serpulo-64.msav"), Vars.world.makeSectorContext(to));
            if (!Vars.state.rules.sector.equals(to) || !Vars.state.map.name().equals("Frozen Forest")
                || Vars.state.rules.defaultTeam.core() == null || Vars.world.width() !== 200 || Vars.world.height() !== 200) {
                throw new Error("Could not reopen the original Frozen Forest");
            }
            return "UPSTREAM_PC_RESEARCH_PROGRESSION_REOPENED";
        """
        stock_console(java, server, root, " ".join(script.splitlines()), r"UPSTREAM_PC_RESEARCH_PROGRESSION_REOPENED")
    print("[PASS] stock v160.8 reopened captured Ground Zero and newly unlocked Frozen Forest", flush=True)

def create_progression(java: str, server: Path, output: Path) -> None:
    slot = "sector-serpulo-170.msav"
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-capture-checkpoint-") as temp:
        root = Path(temp)
        script = f"""
            const presets = Packages.mindustry.content.SectorPresets;
            const items = Packages.mindustry.content.Items;
            const blocks = Packages.mindustry.content.Blocks;
            const zero = presets.groundZero.sector;
            const settings = Packages.arc.Core.settings;
            Vars.logic.reset();
            Vars.world.loadSector(zero);
            Vars.logic.play();
            items.copper.unlock();
            blocks.conveyor.unlock();
            blocks.junction.unlock();
            const state = Vars.state;
            const core = state.rules.defaultTeam.core();
            core.items.set(items.copper, core.storageCapacity);
            core.items.set(items.lead, core.storageCapacity);
            if (state.rules.winWave <= 0 || !state.rules.waves || zero.info.wasCaptured ||
                presets.frozenForest.unlocked() || blocks.router.unlocked()) {{
                throw new Error("Expected uncaptured Ground Zero and locked Router and Frozen Forest");
            }}
            state.wave = state.rules.winWave;
            const rows = [];
            function append(node) {{
                rows.push(String(node.content.name));
                if (node.content.unlockedHost()) {{
                    for (let i = 0; i < node.children.size; i++) append(node.children.get(i));
                }}
            }}
            append(zero.planet.techTree);
            const router = rows.indexOf("router");
            if (router < 0 || state.enemies !== 0) throw new Error("Invalid final-wave checkpoint");
            Packages.mindustry.io.SaveIO.write(Packages.arc.files.Fi.get("{slot}"));
            settings.forceSave();
            return "UPSTREAM_PC_CAPTURE_CHECKPOINT routerRow=" + router;
        """
        marker = r"UPSTREAM_PC_CAPTURE_CHECKPOINT routerRow=(\d+)"
        stock_console(java, server, root, " ".join(script.splitlines()), marker)
        router = re.search(marker, (root / "server.log").read_text(encoding="utf-8"))
        if router is None:
            raise AssertionError("Stock fixture did not report the Router research row")
        (output / "saves").mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / slot, output / "saves" / slot)
        shutil.copyfile(root / "config/settings.bin", output / "settings.bin")
        (output / "router-row.txt").write_text(router[1] + "\n", encoding="ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--create-in", type=Path)
    action.add_argument("--check", nargs=3, metavar=("PLANET", "WORLD", "SETTINGS"))
    action.add_argument("--check-progression", type=Path, help="Serpulo output directory from the JVM reference")
    action.add_argument("--create-progression-in", type=Path, help="Create the stock final-wave checkpoint")
    args = parser.parse_args()
    if args.create_in:
        create(args.java, args.server, args.create_in)
    elif args.check_progression:
        check_progression(args.java, args.server, args.check_progression)
    elif args.create_progression_in:
        create_progression(args.java, args.server, args.create_progression_in)
    else:
        planet, world, settings = args.check
        if planet not in CASES:
            parser.error("--check planet must be serpulo or erekir")
        check(args.java, args.server, planet, Path(world), Path(settings))


if __name__ == "__main__":
    main()
