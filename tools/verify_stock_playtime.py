#!/usr/bin/env python3
"""Verify the stock v160.8 reader sees the game's saved playtime values."""

import argparse
from pathlib import Path
import shutil
import tempfile

from campaign_settings_fixture import stock_console


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--saves", type=Path, required=True)
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="m3ds-stock-playtime-") as temporary:
        root = Path(temporary)
        for name in ("first", "resumed", "new"):
            shutil.copyfile(args.saves / f"{name}.msav", root / f"{name}.msav")
        script = """
            const Fi = Packages.arc.files.Fi, SaveIO = Packages.mindustry.io.SaveIO;
            for (var [name, expected] of [['first', 376], ['resumed', 626], ['new', 0]]) {
                var meta = SaveIO.getMeta(Fi.get(name + '.msav'));
                if (meta.timePlayed !== expected)
                    throw 'Wrong stock playtime for ' + name + ': ' + meta.timePlayed;
            }
            return 'STOCK_PLAYTIME_READBACK';
        """
        stock_console(args.java, args.server, root, " ".join(script.splitlines()), "STOCK_PLAYTIME_READBACK")
    print("STOCK_PLAYTIME_READBACK first=376 resumed=626 new=0 milliseconds")


if __name__ == "__main__":
    main()
