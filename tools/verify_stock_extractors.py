"""Verify stock floor and wall extractor sprites with original Glacier saves."""

import argparse
from pathlib import Path

from verify_offline_material_buildings import capture, changed_pixels
from verify_offline_power_buildings import verify_live
from verify_upstream_factory_plans import start_server, stock_command
from verify_upstream_live_net import stop, wait_for
from verify_upstream_singleplayer import az


EXTRACTORS = (
    ("laserDrill", "laser-drill", 75, 23, 3),
    ("impactDrill", "impact-drill", 78, 21, 4),
    ("plasmaBore", "plasma-bore", 84, 30, 2),
    ("cliffCrusher", "cliff-crusher", 86, 25, 2),
)


def create_stock_saves(args, root):
    root.mkdir()
    log = root / "server.log"
    server = start_server(args, root, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        stock_command(server, log, f"config port {args.port}\nhost Glacier survival",
                      rf"Opened a server on port {args.port}\.")
        stock_command(
            server, log,
            'js (() => { Vars.state.rules.waves = false; '
            'for (let i = 0; i < 2; i++) { '
            'Vars.world.tile(86,30+i).setBlock(Blocks.graphiticWall); '
            'Vars.world.tile(88,25+i).setBlock(Blocks.beryllicStoneWall); } '
            'Vars.world.tile(75,23).setOverlay(Blocks.oreCopper); '
            'Vars.world.tile(78,21).setOverlay(Blocks.oreCopper); '
            'return "EXTRACTOR_TERRAIN"; })()',
            "EXTRACTOR_TERRAIN",
        )
        stock_command(server, log, "save terrain", r"Saved to .*terrain\.msav")
        placements = " ".join(
            f"Vars.world.tile({x},{y}).setBlock(Blocks.{field}, team, 0);"
            for field, _, x, y, _ in EXTRACTORS
        )
        stock_command(server, log,
                      'js (() => { const team = Vars.state.rules.defaultTeam; '
                      + placements + ' return "EXTRACTOR_BUILDINGS"; })()', "EXTRACTOR_BUILDINGS")
        stock_command(server, log, "save extractors", r"Saved to .*extractors\.msav")
        stock_command(server, log, "stop", "Stopped server")
        stock_command(server, log, "load extractors", r"Save loaded\.")
        stock_command(
            server, log,
            'js (() => { const core = Vars.state.rules.defaultTeam.core(); '
            'if (core.tileX() != 81 || core.tileY() != 26) throw "Glacier core moved"; '
            'if (Vars.world.tile(75,23).build.dominantItem != Items.copper) throw "Missing laser ore"; '
            'if (Vars.world.tile(78,21).build.dominantItem != Items.copper) throw "Missing burst ore"; '
            'if (Vars.world.tile(84,30).build.facingAmount != 2) throw "Missing beam ore"; '
            'const crusher = Vars.world.tile(86,25).build; '
            'if (!crusher.block.canPlaceOn(crusher.tile, crusher.team, 0)) throw "Missing crusher sand"; '
            'return "EXTRACTOR_TARGETS_REOPENED"; })()',
            "EXTRACTOR_TARGETS_REOPENED",
        )
    finally:
        stop(server)
    return root / "config/saves"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--port", type=int, default=16670)
    parser.add_argument("--fixture", type=Path, help="An existing directory of stock fixture saves")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    args.output.mkdir(parents=True)
    fixture = args.fixture or create_stock_saves(args, args.output / "stock-server")
    azahar = az.resolve_azahar_bin(args.azahar)
    for model in ("new3ds",):
        output = args.output / model
        before = capture(output / "terrain", model, fixture / "terrain.msav", args.rom, azahar)
        after = capture(output / "stock", model, fixture / "extractors.msav",
                        args.rom, azahar, before, EXTRACTORS, min_changed=25)
        offline = changed_pixels(before, after, EXTRACTORS)
        if min(offline.values()) != 25:
            raise AssertionError(f"{model}: missing offline extractors ({offline})")
        print(f"UPSTREAM_OFFLINE_EXTRACTOR_PIXELS model={model} blocks={offline}", flush=True)
        live = verify_live(args, model, azahar, fixture / "extractors.msav", before,
                           output / "live", EXTRACTORS, min_changed=25)
        print(f"UPSTREAM_LIVE_EXTRACTOR_PIXELS model={model} blocks={live}", flush=True)


if __name__ == "__main__":
    main()
