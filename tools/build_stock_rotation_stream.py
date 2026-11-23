#!/usr/bin/env python3
"""Create the stock v160.8 WorldStream bytes for a second map in a live session."""

import argparse
from pathlib import Path
import shutil
import tempfile

from campaign_settings_fixture import stock_console


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--map", type=Path, required=True, help="original v160.8 map")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-rotation-") as temp:
        root = Path(temp)
        shutil.copyfile(args.map, root / "source.msav")
        script = """
            const Fi = Packages.arc.files.Fi;
            const MapIO = Packages.mindustry.io.MapIO;
            const NetworkIO = Packages.mindustry.net.NetworkIO;
            const Gamemode = Packages.mindustry.game.Gamemode;
            const Player = Packages.mindustry.gen.Player;
            Vars.logic.reset();
            const map = MapIO.createMap(Fi.get('source.msav'), false);
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.logic.play();
            const player = Player.create();
            player.team(Vars.state.rules.defaultTeam);
            player.name = 'Rotation Fixture';
            player.id = 137;
            const stream = new Packages.java.util.zip.DeflaterOutputStream(Fi.get('world.bin').write(false));
            NetworkIO.writeWorld(player, stream);
            stream.close();
            return 'STOCK_ROTATION_STREAM_WRITTEN';
        """
        stock_console(args.java, args.server, root, " ".join(script.splitlines()), "STOCK_ROTATION_STREAM_WRITTEN")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / "world.bin", args.output)
    print(f"[PASS] Stock v160.8 WorldStream created at {args.output}")


if __name__ == "__main__":
    main()
