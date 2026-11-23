#!/usr/bin/env python3
"""Reopen stock reconstructor payloads before and after the ARM11 port's upgrade."""

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
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-reconstructor-") as temp:
        root = Path(temp)
        shutil.copyfile(args.halfway, root / "halfway.msav")
        shutil.copyfile(args.completed, root / "completed.msav")
        script = """
            const Fi = Packages.arc.files.Fi, SaveIO = Packages.mindustry.io.SaveIO;
            const load = name => {
                Vars.logic.reset();
                SaveIO.load(Fi.get(name));
                const build = Vars.world.build(31, 35);
                if (String(build.block.name) !== 'additive-reconstructor' || build.payload === null)
                    throw 'Expected an original Additive Reconstructor and a unit payload';
                return build;
            };
            const before = load('halfway.msav');
            if (String(before.payload.unit.type.name) !== 'dagger' || before.progress !== 60)
                throw 'Original Java lost upgrade progress';
            const after = load('completed.msav');
            if (String(after.payload.unit.type.name) !== 'mace' || after.items.total() !== 0)
                throw 'Original Java lost the completed upgrade or stock consumption';
            return 'STOCK_RECONSTRUCTOR_SAVES_REOPENED';
        """
        stock_console(args.java, args.server, root, " ".join(script.splitlines()), "STOCK_RECONSTRUCTOR_SAVES_REOPENED")
        print("Stock v160.8 reopened the saved Dagger progress and completed Mace payload")


if __name__ == "__main__":
    main()
