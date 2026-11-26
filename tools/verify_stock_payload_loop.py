#!/usr/bin/env python3
"""Reopen the port's in-progress and finished Erekir payload saves in stock Java."""

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

    with tempfile.TemporaryDirectory(prefix="m3ds-stock-payload-loop-") as temp:
        root = Path(temp)
        shutil.copyfile(args.halfway, root / "halfway.msav")
        shutil.copyfile(args.completed, root / "completed.msav")
        script = """
            const Fi = Packages.arc.files.Fi;
            const SaveIO = Packages.mindustry.io.SaveIO;
            const Blocks = Packages.mindustry.content.Blocks;
            const Items = Packages.mindustry.content.Items;
            const Groups = Packages.mindustry.gen.Groups;
            const load = name => {
                Vars.logic.reset();
                SaveIO.load(Fi.get(name));
                const constructor = Groups.build.find(b => b.block === Blocks.constructor);
                const recycler = Groups.build.find(b => b.block === Blocks.smallDeconstructor);
                if (constructor == null || constructor.recipe !== Blocks.reinforcedContainer
                    || !constructor.items.empty() || recycler == null)
                    throw 'Stock Java lost the configured line or material consumption';
                return recycler;
            };
            const before = load('halfway.msav');
            if (before.progress < 0.33 || before.progress >= 1 || before.accum == null
                || before.accum.length !== 2 || before.deconstructing == null
                || before.deconstructing.build.block !== Blocks.reinforcedContainer
                || before.items.get(Items.tungsten) <= 0 || before.items.get(Items.graphite) <= 0)
                throw 'Stock Java lost the active payload, progress, or fractional material buffer';
            const after = load('completed.msav');
            if (after.deconstructing != null || after.accum != null
                || after.items.get(Items.tungsten) !== 45 || after.items.get(Items.graphite) !== 60)
                throw 'Stock Java lost the completed stock recycling output';
            return 'STOCK_PAYLOAD_LOOP_SAVES_REOPENED';
        """
        stock_console(args.java, args.server, root, " ".join(script.splitlines()), "STOCK_PAYLOAD_LOOP_SAVES_REOPENED")
    print("Stock v160.8 reopened both Erekir saves and read 45 tungsten and 60 graphite after recycling")


if __name__ == "__main__":
    main()
