#!/usr/bin/env python3
"""Read the port's original player metadata with the unmodified Java game."""

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
    with tempfile.TemporaryDirectory(prefix="m3ds-saved-player-") as temporary:
        root = Path(temporary)
        for name in ("groups.json", "unit-roundtrip.msav", "turret-roundtrip.msav", "fresh.msav"):
            shutil.copyfile(args.saves / name, root / name)
        script = """
            const Fi = Packages.arc.files.Fi, SaveIO = Packages.mindustry.io.SaveIO;
            const groups = String(Fi.get('groups.json').readString());
            const tags = name => SaveIO.getMeta(Fi.get(name + '.msav')).tags;
            for (var [name, type, point] of [
                ['unit-roundtrip', 'dagger', '(480.0,480.0)'],
                ['turret-roundtrip', 'block', '(520.0,480.0)']
            ]) {
                var saved = tags(name);
                if (saved.getInt('playerteam') !== 2 || String(saved.get('viewpos')) !== point
                    || String(saved.get('controlledType')) !== type || String(saved.get('controlGroups')) !== groups)
                    throw 'Stock metadata differs for ' + name;
            }
            const fresh = tags('fresh');
            if (fresh.getInt('playerteam') !== 1 || String(fresh.get('controlGroups')) !== 'null')
                throw 'A fresh match inherited old metadata';
            return 'STOCK_SAVED_PLAYER_READBACK';
        """
        stock_console(args.java, args.server, root, " ".join(script.splitlines()), "STOCK_SAVED_PLAYER_READBACK")
    print("STOCK_SAVED_PLAYER_READBACK team=crux viewpos=exact unit=dagger turret=block groups=exact")


if __name__ == "__main__":
    main()
