"""Draw stock distribution buildings, bridge spans, and carried items from original saves."""

import argparse
from pathlib import Path

from verify_offline_material_buildings import capture, changed_pixels
from verify_offline_power_buildings import verify_live
from verify_upstream_factory_plans import start_server, stock_command
from verify_upstream_live_net import stop, wait_for
from verify_upstream_singleplayer import az


BUILDINGS = (
    ("titaniumConveyor", "titanium-conveyor", 74, 25, 1),
    ("plastaniumConveyor", "plastanium-conveyor", 79, 22, 1),
    ("itemBridge", "bridge-conveyor", 75, 23, 1),
    ("ductBridge", "duct-bridge", 84, 29, 1),
    ("massDriver", "mass-driver", 86, 26, 3),
    ("ductRouter", "duct-router", 84, 22, 1),
    ("unitCargoLoader", "unit-cargo-loader", 86, 22, 3),
)
ACTIVE = (
    (None, "stock-item-stack", 79, 22, 1),
    (None, "item-bridge-span", 75, 24, 1),
    (None, "duct-bridge-span", 85, 29, 1),
)


def create_stock_saves(args, root):
    root.mkdir()
    log = root / "server.log"
    server = start_server(args, root, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        stock_command(server, log, f"config port {args.port}\nhost Glacier survival",
                      rf"Opened a server on port {args.port}\.")
        stock_command(server, log, 'js Vars.state.rules.waves=false; "DISTRIBUTION_TERRAIN_READY"',
                      "DISTRIBUTION_TERRAIN_READY")
        stock_command(server, log, "save terrain", r"Saved to .*terrain\.msav")
        placements = " ".join(
            f"Vars.world.tile({x},{y}).setBlock(Blocks.{field},team,0);"
            for field, _, x, y, _ in BUILDINGS
        )
        stock_command(server, log, "js (() => { const team=Vars.state.rules.defaultTeam; " + placements
                      + ' return "DISTRIBUTION_IDLE_READY"; })()', "DISTRIBUTION_IDLE_READY")
        stock_command(server, log, "save idle", r"Saved to .*idle\.msav")
        stock_command(
            server, log,
            'js (() => { const team=Vars.state.rules.defaultTeam; '
            'const stack=Vars.world.tile(79,22).build; stack.handleStack(Items.copper,10,stack); '
            'const belt=Vars.world.tile(74,25).build; belt.handleStack(Items.lead,1,belt); '
            'const bridge=Vars.world.tile(75,23).build; const receiver=Vars.world.tile(75,26); '
            'receiver.setBlock(Blocks.itemBridge,team,0); bridge.configured(null,new Packages.arc.math.geom.Point2(0,3)); '
            'if (!bridge.block.linkValid(bridge.tile,receiver)) throw "Invalid stock bridge link"; '
            'Vars.world.tile(87,29).setBlock(Blocks.ductBridge,team,0); '
            'return "DISTRIBUTION_LINKS_READY"; })()',
            "DISTRIBUTION_LINKS_READY",
        )
        stock_command(server, log, "save active", r"Saved to .*active\.msav")
        stock_command(server, log, "stop", "Stopped server")
        stock_command(server, log, "load active", r"Save loaded\.")
        stock_command(
            server, log,
            'js (() => { const stack=Vars.world.tile(79,22).build; '
            'if (stack.items.get(Items.copper) != 10) throw "Stock stack contents were lost"; '
            'if (Vars.world.tile(75,23).build.link != Vars.world.tile(75,26).pos()) throw "Item link was lost"; '
            'if (Vars.world.tile(84,29).build.findLink() !== Vars.world.tile(87,29).build) throw "Duct link was lost"; '
            'return "DISTRIBUTION_STOCK_SAVE_REOPENED"; })()',
            "DISTRIBUTION_STOCK_SAVE_REOPENED",
        )
    finally:
        stop(server)
    return root / "config/saves"


def verify_model(args, model, azahar, saves):
    out = args.output / model
    terrain = capture(out / "terrain", model, saves / "terrain.msav", args.rom, azahar)
    idle = capture(out / "idle", model, saves / "idle.msav", args.rom, azahar, terrain, BUILDINGS)
    active = capture(out / "active", model, saves / "active.msav", args.rom, azahar, idle, ACTIVE, min_changed=4)
    buildings = changed_pixels(terrain, idle, BUILDINGS)
    transfer = changed_pixels(idle, active, ACTIVE)
    if min(buildings.values()) < 12 or min(transfer.values()) < 4:
        raise AssertionError(f"{model}: missing stock transport graphics: {buildings=} {transfer=}")
    print(f"UPSTREAM_DISTRIBUTION_PIXELS model={model} buildings={buildings} transfer={transfer}", flush=True)
    if args.live:
        live = verify_live(args, model, azahar, saves / "active.msav", terrain, out / "live",
                           samples=BUILDINGS + ACTIVE, min_changed=4)
        print(f"UPSTREAM_LIVE_DISTRIBUTION_PIXELS model={model} samples={live}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--port", type=int, default=16596)
    parser.add_argument("--fixture", type=Path, help="An existing directory of stock fixture saves")
    parser.add_argument("--live", action="store_true", help="Also replay the save on an unmodified Java server")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    azahar = az.resolve_azahar_bin(args.azahar)
    args.output.mkdir(parents=True)
    saves = args.fixture or create_stock_saves(args, args.output / "stock-server")
    for model in ("old3ds", "new3ds"):
        verify_model(args, model, azahar, saves)


if __name__ == "__main__":
    main()
