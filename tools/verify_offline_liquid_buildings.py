"""Replay original liquid buildings, water and gas fills, and linked spans on ARM11."""

import argparse
from pathlib import Path

from verify_offline_material_buildings import capture, changed_pixels
from verify_offline_power_buildings import verify_live
from verify_upstream_factory_plans import start_server, stock_command
from verify_upstream_live_net import stop, wait_for
from verify_upstream_singleplayer import az


BUILDINGS = (
    ("liquidTank", "liquid-tank", 86, 23, 3),
    ("reinforcedLiquidTank", "reinforced-liquid-tank", 86, 28, 3),
    ("bridgeConduit", "bridge-conduit", 76, 22, 1),
    ("reinforcedBridgeConduit", "reinforced-bridge-conduit", 75, 21, 1),
    ("reinforcedPump", "reinforced-pump", 80, 29, 2),
    ("reinforcedConduit", "reinforced-conduit", 83, 21, 1),
    ("liquidJunction", "liquid-junction", 85, 30, 1),
    ("liquidSource", "liquid-source", 83, 30, 1),
)
FILLS = (BUILDINGS[0], BUILDINGS[1], BUILDINGS[-1])
SPANS = (
    (None, "liquid-bridge-span", 76, 23, 1),
    (None, "reinforced-bridge-span", 76, 21, 1),
)


def create_stock_saves(args, root):
    root.mkdir()
    log = root / "server.log"
    server = start_server(args, root, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        stock_command(server, log, f"config port {args.port}\nhost Glacier survival",
                      rf"Opened a server on port {args.port}\.")
        stock_command(server, log, 'js Vars.state.rules.waves=false; "LIQUID_TERRAIN_READY"',
                      "LIQUID_TERRAIN_READY")
        stock_command(server, log, "save terrain", r"Saved to .*terrain\.msav")
        placements = " ".join(
            f"Vars.world.tile({x},{y}).setBlock(Blocks.{field},team,0);"
            for field, _, x, y, _ in BUILDINGS
        )
        stock_command(server, log, "js (() => { const team=Vars.state.rules.defaultTeam; " + placements
                      + ' return "LIQUID_IDLE_READY"; })()', "LIQUID_IDLE_READY")
        stock_command(server, log, "save idle", r"Saved to .*idle\.msav")
        stock_command(
            server, log,
            'js (() => { const team=Vars.state.rules.defaultTeam; '
            'Vars.world.tile(86,23).build.liquids.add(Liquids.water,1500); '
            'Vars.world.tile(86,28).build.liquids.add(Liquids.nitrogen,2000); '
            'Vars.world.tile(83,30).build.configured(null,Liquids.water); '
            'const bridge=Vars.world.tile(76,22).build; const receiver=Vars.world.tile(76,25); '
            'receiver.setBlock(Blocks.bridgeConduit,team,0); '
            'bridge.configured(null,new Packages.arc.math.geom.Point2(0,3)); '
            'const armoredEnd=Vars.world.tile(78,21); armoredEnd.setBlock(Blocks.reinforcedBridgeConduit,team,0); '
            'Vars.world.tile(75,21).build.liquids.add(Liquids.water,100); '
            'armoredEnd.build.liquids.add(Liquids.water,100); '
            'return "LIQUID_FILLS_AND_LINKS_READY"; })()',
            "LIQUID_FILLS_AND_LINKS_READY",
        )
        stock_command(server, log, "save active", r"Saved to .*active\.msav")
        stock_command(server, log, "stop", "Stopped server")
        stock_command(server, log, "load active", r"Save loaded\.")
        stock_command(
            server, log,
            'js (() => { const water=Vars.world.tile(86,23).build.liquids.get(Liquids.water); '
            'const gas=Vars.world.tile(86,28).build.liquids.get(Liquids.nitrogen); '
            'if (water != 1500 || gas != 2000) throw "Stock tank contents changed"; '
            'if (Vars.world.tile(76,22).build.link != Vars.world.tile(76,25).pos()) throw "Liquid link was lost"; '
            'if (Vars.world.tile(75,21).build.findLink() !== Vars.world.tile(78,21).build) throw "Armored link was lost"; '
            'return "LIQUID_STOCK_SAVE_REOPENED"; })()',
            "LIQUID_STOCK_SAVE_REOPENED",
        )
    finally:
        stop(server)
    return root / "config/saves"


def verify_model(args, model, azahar, saves):
    out = args.output / model
    terrain = capture(out / "terrain", model, saves / "terrain.msav", args.rom, azahar)
    idle = capture(out / "idle", model, saves / "idle.msav", args.rom, azahar, terrain, BUILDINGS)
    active = capture(out / "active", model, saves / "active.msav", args.rom, azahar,
                     idle, FILLS + SPANS, min_changed=4)
    buildings = changed_pixels(terrain, idle, BUILDINGS)
    fluids = changed_pixels(idle, active, FILLS + SPANS)
    if min(buildings.values()) < 12 or min(fluids.values()) < 4:
        raise AssertionError(f"{model}: missing original liquid graphics: {buildings=} {fluids=}")
    print(f"UPSTREAM_LIQUID_PIXELS model={model} buildings={buildings} fills_and_links={fluids}", flush=True)
    if args.live:
        live = verify_live(args, model, azahar, saves / "active.msav", idle, out / "live", FILLS + SPANS,
                           min_changed=4)
        print(f"UPSTREAM_LIVE_LIQUID_PIXELS model={model} fills_and_links={live}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--port", type=int, default=16597)
    parser.add_argument("--fixture", type=Path, help="An existing directory of stock fixture saves")
    parser.add_argument("--live", action="store_true", help="Also replay the save on an unmodified Java server")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    azahar = az.resolve_azahar_bin(args.azahar)
    args.output.mkdir(parents=True)
    saves = args.fixture or create_stock_saves(args, args.output / "stock-server")
    for model in ("new3ds",):
        verify_model(args, model, azahar, saves)


if __name__ == "__main__":
    main()
