#!/usr/bin/env python3
"""Reopen the port's Tank Assembler and Vanquish saves in the stock Java release."""

import argparse
from pathlib import Path
import shutil
import tempfile

from campaign_settings_fixture import stock_console


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--halfway", type=Path, required=True)
    parser.add_argument("--completed", type=Path, required=True)
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="m3ds-stock-assembler-") as temp:
        root = Path(temp)
        shutil.copyfile(args.halfway, root / "halfway.msav")
        shutil.copyfile(args.completed, root / "completed.msav")
        script = """
            const Fi = Packages.arc.files.Fi;
            const SaveIO = Packages.mindustry.io.SaveIO;
            const Blocks = Packages.mindustry.content.Blocks;
            const UnitTypes = Packages.mindustry.content.UnitTypes;
            const Groups = Packages.mindustry.gen.Groups;
            const load = name => {
                Vars.logic.reset();
                SaveIO.load(Fi.get(name));
                const build = Groups.build.find(b => b.block === Blocks.tankAssembler);
                if (build == null || build.commandPos == null) throw 'Stock Java lost the assembler or its rally point';
                return build;
            };
            const before = load('halfway.msav');
            if (before.progress < 0.2 || before.progress >= 1
                || before.blocks.get(UnitTypes.stell) !== 4
                || before.blocks.get(Blocks.tungstenWallLarge) !== 10
                || Groups.unit.count(u => u.team === before.team && u.type === before.block.droneType) !== 4)
                throw 'Stock Java lost progress, materials or drones';
            const after = load('completed.msav');
            const output = Groups.unit.find(u => u.team === after.team && u.type === UnitTypes.vanquish);
            if (output == null || after.progress !== 0 || !after.blocks.isEmpty()
                || !after.commandPos.equals(output.command().targetPos))
                throw 'Stock Java lost the completed Vanquish, consumption or rally command';
            return 'STOCK_ASSEMBLER_SAVES_REOPENED';
        """
        stock_console(args.java, args.server, root, " ".join(script.splitlines()), "STOCK_ASSEMBLER_SAVES_REOPENED")
    print("Stock v160.8 reopened the Tank Assembler in progress and the completed Vanquish")


if __name__ == "__main__":
    main()
