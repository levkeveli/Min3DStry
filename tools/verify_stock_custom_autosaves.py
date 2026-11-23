#!/usr/bin/env python3
"""Read the port's new Custom Game slots and display names using stock Java."""

import argparse
from pathlib import Path
import shutil
import tempfile

from campaign_settings_fixture import stock_console


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--profile", required=True, type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-new-slots-") as temp:
        root = Path(temp)
        config = root / "config"
        config.mkdir()
        shutil.copyfile(args.profile / "settings.bin", config / "settings.bin")
        for i in range(3):
            name = f"{i}.msav"
            shutil.copyfile(args.profile / "saves" / name, root / name)
        script = """
            const Fi = Packages.arc.files.Fi, SaveIO = Packages.mindustry.io.SaveIO;
            const settings = Packages.arc.Core.settings, items = Packages.mindustry.content.Items;
            const verify = (index, name, sandbox, copper) => {
                Vars.logic.reset();
                SaveIO.load(Fi.get(index + '.msav'));
                const rules = Vars.state.rules;
                if (String(Vars.state.map.name()) !== name || rules.infiniteResources !== sandbox ||
                    rules.defaultTeam.core().items.get(items.copper) !== copper || rules.sector !== null ||
                    !String(settings.getString('save-' + index + '-name', '')).startsWith(name + ' '))
                    throw 'Unexpected stock slot ' + index;
            };
            verify(0, 'Glacier', true, 41);
            verify(1, 'Ancient Caldera', false, 200);
            verify(2, 'Glacier', true, 200);
            return 'STOCK_CUSTOM_AUTOSAVES_REOPENED';
        """
        stock_console(args.java, args.server, root, " ".join(script.splitlines()), "STOCK_CUSTOM_AUTOSAVES_REOPENED")
        print("Stock v160.8 reopened both automatic slots and the separate manual save")


if __name__ == "__main__":
    main()
