"""Compare stock Serpulo and Erekir turret sprites and headings on ARM11."""

import argparse
from pathlib import Path

from verify_offline_material_buildings import capture, changed_pixels
from verify_offline_power_buildings import verify_live
from verify_upstream_factory_plans import start_server, stock_command
from verify_upstream_live_net import stop, wait_for
from verify_upstream_singleplayer import az


TURRETS = (
    ("wave", "wave", 75, 23, 2),
    ("ripple", "ripple", 85, 23, 3),
    ("parallax", "parallax", 74, 26, 2),
    ("segment", "segment", 86, 29, 2),
    ("breach", "breach", 79, 30, 3),
    ("sublimate", "sublimate", 86, 26, 3),
)


def create_stock_saves(args, root):
    root.mkdir()
    log = root / "server.log"
    server = start_server(args, root, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        stock_command(server, log, f"config port {args.port}\nhost Glacier survival",
                      rf"Opened a server on port {args.port}\.")
        stock_command(server, log, 'js Vars.state.rules.waves=false; "TURRET_TERRAIN_READY"',
                      "TURRET_TERRAIN_READY")
        stock_command(server, log, "save terrain", r"Saved to .*terrain\.msav")
        placements = " ".join(
            f"Vars.world.tile({x},{y}).setBlock(Blocks.{field}, team, 0);"
            for field, _, x, y, _ in TURRETS
        )
        stock_command(
            server, log,
            "js (() => { const team = Vars.state.rules.defaultTeam; " + placements
            + " Vars.world.tile(75,23).build.liquids.add(Liquids.water,5);"
            ' return "TURRET_BLOCKS_READY"; })()',
            "TURRET_BLOCKS_READY",
        )
        for phase, angle in (("east", 0), ("north", 90)):
            headings = " ".join(f"Vars.world.tile({x},{y}).build.rotation={angle};"
                                for _, _, x, y, _ in TURRETS)
            stock_command(server, log,
                          'js (() => { ' + headings + f' return "TURRET_{phase.upper()}_READY"; }})()',
                          f"TURRET_{phase.upper()}_READY")
            stock_command(server, log, f"save {phase}", rf"Saved to .*{phase}\.msav")
        stock_command(server, log, "stop", "Stopped server")
        stock_command(server, log, "load east", r"Save loaded\.")
        stock_command(server, log,
                      'js (() => { if (Vars.world.tile(79,30).build.drawrot() != -90) throw "Breach heading lost"; '
                      'return "TURRET_STOCK_SAVE_REOPENED"; })()',
                      "TURRET_STOCK_SAVE_REOPENED")
    finally:
        stop(server)
    return root / "config/saves"


def verify_model(args, model, azahar, fixture):
    out = args.output / model
    terrain = capture(out / "terrain", model, fixture / "terrain.msav", args.rom, azahar)
    east = capture(out / "east", model, fixture / "east.msav", args.rom, azahar, terrain, TURRETS)
    north = capture(out / "north", model, fixture / "north.msav", args.rom, azahar, terrain, TURRETS)
    buildings = changed_pixels(terrain, east, TURRETS)
    rotated = changed_pixels(east, north, TURRETS)
    if min(buildings.values()) < 12 or min(rotated.values()) < 2:
        raise AssertionError(f"{model}: stock turret draw or rotation missing: {buildings=} {rotated=}")
    print(f"UPSTREAM_OFFLINE_TURRET_PIXELS model={model} buildings={buildings} rotated={rotated}", flush=True)
    if args.live:
        live = verify_live(args, model, azahar, fixture / "east.msav", terrain, out / "live", TURRETS)
        print(f"UPSTREAM_LIVE_TURRET_PIXELS model={model} buildings={live}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--port", type=int, default=16593)
    parser.add_argument("--fixture", type=Path, help="An existing directory of stock fixture saves")
    parser.add_argument("--live", action="store_true", help="Also verify turret sprites in a stock-server session")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    azahar = az.resolve_azahar_bin(args.azahar)
    args.output.mkdir(parents=True)
    fixture = args.fixture or create_stock_saves(args, args.output / "stock-server")
    for model in ("old3ds", "new3ds"):
        verify_model(args, model, azahar, fixture)


if __name__ == "__main__":
    main()
