"""Replay original building links in v160.8 offline saves and stock multiplayer."""

import argparse
from pathlib import Path
import time

import verify_upstream_singleplayer as sp
from verify_stock_command_orders import replay
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import stock_command
from verify_upstream_live_net import stop


SETUP = (
    'const rules=Vars.state.rules, team=rules.defaultTeam; rules.waves=false; rules.fog=false; '
    'const node=Vars.world.tile(74,29), battery=Vars.world.tile(78,29); '
    'const item=Vars.world.tile(74,24), itemReceiver=Vars.world.tile(78,24); '
    'const liquid=Vars.world.tile(84,29), liquidReceiver=Vars.world.tile(88,29); '
    'node.setBlock(Blocks.powerNode,team,0); battery.setBlock(Blocks.battery,team,0); '
    'item.setBlock(Blocks.itemBridge,team,0); itemReceiver.setBlock(Blocks.itemBridge,team,0); '
    'liquid.setBlock(Blocks.bridgeConduit,team,0); liquidReceiver.setBlock(Blocks.bridgeConduit,team,0); '
    'item.build.link=itemReceiver.pos(); '
    'if (node.build.power.links.contains(battery.pos()) || liquid.build.link !== -1) '
    'throw "Power and liquid endpoints must start disconnected"; '
    'return "STOCK_LINKS_FIXTURE_READY";'
)
RESULT = (
    'const node=Vars.world.build(74,29), battery=Vars.world.build(78,29); '
    'const item=Vars.world.build(74,24), itemReceiver=Vars.world.build(78,24); '
    'const liquid=Vars.world.build(84,29), liquidReceiver=Vars.world.build(88,29); '
    'if (node.block !== Blocks.powerNode || battery.block !== Blocks.battery || '
    '!node.power.links.contains(battery.pos()) || !battery.power.links.contains(node.pos()) || '
    'node.power.graph !== battery.power.graph) throw "Expected the original joined power graph"; '
    'if (item.block !== Blocks.itemBridge || itemReceiver.block !== Blocks.itemBridge || '
    'item.link !== -1) throw "Expected the original item bridge to be disconnected"; '
    'if (liquid.block !== Blocks.bridgeConduit || liquidReceiver.block !== Blocks.bridgeConduit || '
    'liquid.link !== liquidReceiver.pos()) throw "Expected the original conduit bridge to be connected"; '
    'return "STOCK_LINKS_RESULT power=connected item=disconnected liquid=connected";'
)
TAPS = {
    34: (305, 64), 38: (56, 88), 42: (120, 88), 46: (305, 64),
    50: (305, 64), 54: (56, 168), 58: (120, 168), 62: (305, 64),
    66: (305, 64), 70: (216, 88), 74: (280, 88), 78: (305, 64),
    # Reopen the same source after the endpoint tap so the live result waits for both.
    82: (305, 64), 87: (216, 88),
}


def create_fixture(args, output):
    server, log = stock_session(args, output)
    try:
        stock_command(server, log, "js (() => { " + SETUP + " })()", "STOCK_LINKS_FIXTURE_READY")
        stock_command(server, log, "save links", r"Saved to .*links\.msav")
    finally:
        stop(server)
    return output / "config/saves/links.msav"


def verify_touch(log, proc, rpc, output):
    sp._wait_for_log(log, proc, r"UPSTREAM_LINK_CONFIG x=74 y=29 block=power-node\b", 90)
    sp._wait_for_log(log, proc, r"UPSTREAM_LINK_CONFIG x=74 y=24 block=bridge-conveyor\b", 35)
    conduit = r"UPSTREAM_LINK_CONFIG x=84 y=29 block=bridge-conduit\b"
    sp._wait_for_log(log, proc, conduit, 35)
    # The 3DS log may arrive before Azahar has presented the selected source.
    point = (256, 328)  # Bottom-screen center of the source conduit cell.
    deadline = time.monotonic() + 6
    while time.monotonic() < deadline:
        screenshot = rpc.screenshot()
        red, green, blue = sp.az.inspect_png(screenshot, (point,))["samples"][point]
        if red > 230 and green > 190 and blue < 130:
            (output / "selected-conduit.png").write_bytes(screenshot)
            break
        time.sleep(0.2)
    else:
        raise AssertionError("The active source conduit was not highlighted on the touch grid")
    sp._wait_for_log(log, proc, conduit + r"[\s\S]*" + conduit, 35)


def read_stock_links(server, log):
    stock_command(server, log, "js (() => { " + RESULT + " })()",
                  "STOCK_LINKS_RESULT power=connected item=disconnected liquid=connected")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=("new3ds",), action="append")
    parser.add_argument("--port", type=int, default=16652)
    parser.add_argument("--live", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    fixture = args.fixture or create_fixture(args, args.output / "stock-fixture")
    for model in args.model or ("new3ds",):
        for live in (False, True) if args.live else (False,):
            replay(args, model, fixture, live, taps=TAPS, verify_input=verify_touch, read_stock=read_stock_links)
            print(f"UPSTREAM_STOCK_BUILDING_LINKS model={model} live={live} power=ok item=ok liquid=ok", flush=True)


if __name__ == "__main__":
    main()
