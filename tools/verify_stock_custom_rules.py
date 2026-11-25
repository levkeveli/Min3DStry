#!/usr/bin/env python3
"""Generate stock v160.8 Custom Game rule exports and reopen a resulting save."""

import argparse
from pathlib import Path
import shutil
import tempfile

from campaign_settings_fixture import stock_console


def fixture(java, server, base_map, output):
    output.mkdir(parents=True, exist_ok=True)
    name = base_map.stem + ".sandbox.json"
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-rule-fixture-") as temp:
        root = Path(temp)
        shutil.copyfile(base_map, root / "base.msav")
        script = """
            const Fi = Packages.arc.files.Fi, MapIO = Packages.mindustry.io.MapIO,
                  JsonIO = Packages.mindustry.io.JsonIO, mode = Packages.mindustry.game.Gamemode;
            const map = MapIO.createMap(Fi.get('base.msav'), false);
            const rules = map.applyRules(mode.sandbox);
            rules.waves = true;
            rules.waveTimer = true;
            rules.wavesSpawnAtCores = true;
            rules.infiniteResources = false;
            rules.waveSpacing = 420;
            rules.initialWaveSpacing = 8;
            rules.buildCostMultiplier = 0.5;
            rules.unitCap = 30;
            rules.spawns.clear();
            Fi.get('rules.json').writeString(JsonIO.write(rules), false, 'UTF-8');
            return 'STOCK_CUSTOM_RULES_EXPORTED';
        """
        stock_console(java, server, root, " ".join(script.splitlines()), "STOCK_CUSTOM_RULES_EXPORTED")
        shutil.copyfile(root / "rules.json", output / name)
        print(f"Stock v160.8 wrote {output / name}")


def reopen(java, server, save):
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-rules-reopen-") as temp:
        root = Path(temp)
        shutil.copyfile(save, root / "custom-rules.msav")
        script = """
            const Fi = Packages.arc.files.Fi, SaveIO = Packages.mindustry.io.SaveIO;
            SaveIO.load(Fi.get('custom-rules.msav'));
            const rules = Vars.state.rules;
            if (String(Vars.state.map.name()) !== 'Glacier' || Vars.state.wave !== 2 ||
                !rules.waves || !rules.waveTimer || rules.infiniteResources || rules.sector !== null ||
                rules.initialWaveSpacing !== 8 || rules.waveSpacing !== 420 ||
                rules.buildCostMultiplier !== 0.5 || rules.unitCap !== 30)
                throw 'Stock Java reopened incorrect Custom Game rules';
            return 'STOCK_CUSTOM_RULES_REOPENED';
        """
        stock_console(java, server, root, " ".join(script.splitlines()), "STOCK_CUSTOM_RULES_REOPENED")
        print("Stock v160.8 reopened the Custom Game save with the imported rules")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--map", type=Path)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--rules-dir", type=Path)
    modes.add_argument("--reopen", type=Path)
    args = parser.parse_args()
    if args.rules_dir:
        if args.map is None:
            parser.error("--rules-dir requires the original Glacier --map")
        fixture(args.java, args.server, args.map, args.rules_dir)
    else:
        reopen(args.java, args.server, args.reopen)


if __name__ == "__main__":
    main()
