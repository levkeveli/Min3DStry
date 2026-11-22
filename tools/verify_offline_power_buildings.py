"""Replay original power blocks and a connected Erekir beam on both Azahar profiles."""

import argparse
from pathlib import Path

from verify_offline_material_buildings import capture, changed_pixels
from verify_upstream_factory_plans import start_server, stock_command
from verify_upstream_live_net import stop, wait_for
from verify_upstream_singleplayer import az


REACTOR = ("thoriumReactor", "thorium-reactor", 86, 29, 3)
BUILDINGS = (
    ("solarPanel", "solar-panel", 76, 23, 1),
    ("batteryLarge", "battery-large", 85, 23, 3),
    REACTOR,
    ("turbineCondenser", "turbine-condenser", 79, 30, 3),
    ("chemicalCombustionChamber", "chemical-combustion-chamber", 86, 26, 3),
    ("beamNode", "beam-node-south", 74, 22, 1),
    ("beamNode", "beam-node-north", 74, 27, 1),
)
SAMPLES = BUILDINGS + ((None, "beam-between-nodes", 74, 24, 1),)


def create_fixtures(args, output):
    output.mkdir()
    log = output / "server.log"
    server = start_server(args, output, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        stock_command(server, log, f"config port {args.port}\nhost Glacier survival",
                      rf"Opened a server on port {args.port}\.")
        stock_command(server, log, 'js Vars.state.rules.waves=false; "POWER_TERRAIN_READY"',
                      "POWER_TERRAIN_READY")
        stock_command(server, log, "save terrain", r"Saved to .*terrain\.msav")
        placements = " ".join(
            f"Vars.world.tile({x},{y}).setBlock(Blocks.{field},team,0);"
            for field, _, x, y, _ in BUILDINGS
        )
        stock_command(
            server, log,
            'js (() => { const team=Vars.state.rules.defaultTeam; ' + placements +
            ' const beam=Vars.world.tile(74,22).build; beam.updateDirections(); '
            'if (beam.links[1] !== Vars.world.tile(74,27).build) throw "Missing north beam link"; '
            'return "POWER_BUILDINGS_READY"; })()',
            "POWER_BUILDINGS_READY",
        )
        stock_command(server, log, "save stock", r"Saved to .*stock\.msav")
        stock_command(
            server, log,
            'js (() => { Vars.world.tile(86,29).build.heat=0.95; return "POWER_REACTOR_HEATED"; })()',
            "POWER_REACTOR_HEATED",
        )
        stock_command(server, log, "save heated", r"Saved to .*heated\.msav")
    finally:
        stop(server)
    return output / "config/saves"


def check_pixels(model, before, after, samples):
    counts = changed_pixels(before, after, samples)
    for name, changed in counts.items():
        print(f"UPSTREAM_OFFLINE_POWER_PIXELS model={model} sample={name} changed={changed}/25", flush=True)
    if min(counts.values()) < 12:
        raise AssertionError(f"{model}: power graphics did not cover their expected sample points")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--port", type=int, default=16590)
    parser.add_argument("--fixture", type=Path, help="An existing directory of stock fixture saves")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    azahar = az.resolve_azahar_bin(args.azahar)
    args.output.mkdir(parents=True)
    fixture = args.fixture or create_fixtures(args, args.output / "stock-server")
    for model in ("old3ds", "new3ds"):
        out = args.output / model
        terrain = capture(out / "terrain", model, fixture / "terrain.msav", args.rom, azahar)
        stock = capture(out / "stock", model, fixture / "stock.msav", args.rom, azahar, terrain, SAMPLES)
        check_pixels(model, terrain, stock, SAMPLES)
        heated = capture(out / "heated", model, fixture / "heated.msav", args.rom, azahar, stock, (REACTOR,))
        check_pixels(model, stock, heated, (REACTOR,))


if __name__ == "__main__":
    main()
