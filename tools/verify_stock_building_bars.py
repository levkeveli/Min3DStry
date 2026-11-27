#!/usr/bin/env python3
"""Verify original building-specific HUD bars in both New 3DS game modes."""

import argparse
import hashlib
from pathlib import Path
import re
import time

import verify_upstream_singleplayer as sp
from verify_stock_command_orders import replay
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import stock_command
from verify_upstream_live_net import SERVER_SHA256, stop


SETUP = (
    "const rules=Vars.state.rules, team=rules.defaultTeam; rules.waves=false; rules.fog=false; "
    "const source=Vars.world.tile(75,23), factory=Vars.world.tile(85,23); "
    "source.setBlock(Blocks.heatSource,team,0); source.build.heat=Blocks.heatSource.heatOutput; "
    "factory.setBlock(Blocks.airFactory,team,0); factory.build.currentPlan=0; "
    "factory.build.progress=Blocks.airFactory.plans.first().time/2; "
    "return 'STOCK_SPECIFIC_BARS_PLACED';"
)
RESULT = (
    "const source=Vars.world.build(75,23), factory=Vars.world.build(85,23); "
    "const core=Vars.state.rules.defaultTeam.core(); "
    "if (core===null || core.tileX()!==81 || core.tileY()!==26 || Vars.state.gameOver || "
    "source===null || source.block!==Blocks.heatSource || source.heat!==Blocks.heatSource.heatOutput || "
    "factory===null || factory.block!==Blocks.airFactory || factory.currentPlan!==0 || "
    "factory.fraction()!==0.5) throw 'Original bar inputs changed'; "
    "return 'STOCK_SPECIFIC_BARS_READBACK';"
)


def create_fixture(args):
    directory = args.output / "stock-fixture"
    server, log = stock_session(args, directory)
    try:
        stock_command(server, log, "js (() => { " + SETUP + " })()", "STOCK_SPECIFIC_BARS_PLACED")
        read_back(server, log)
        stock_command(server, log, "save specific-bars", r"Saved to .*specific-bars\.msav")
    finally:
        stop(server)
    return directory / "config/saves/specific-bars.msav"


def read_back(server, log):
    stock_command(server, log, "js (() => { " + RESULT + " })()", "STOCK_SPECIFIC_BARS_READBACK")


def verify_bar(log, emulator, rpc, output, block, key, fill, label):
    marker = rf"UPSTREAM_BUILDING_BAR block={block} key={key} y=(\d+) fill={fill} tint=(-?\d+) label={re.escape(label)}"
    response = sp._wait_for_log(log, emulator, marker, 110)
    y, packed = map(int, re.search(marker, response).groups())
    tint = tuple((packed >> shift) & 255 for shift in (24, 16, 8))
    # Bars begin 4 pixels inside the 320-wide viewport. Probe away from their text.
    points = tuple((x, y + dy) for x in range(52, 99, 3) for dy in (4, 7, 10, 13))
    deadline = time.monotonic() + 7
    while time.monotonic() < deadline:
        frame = rpc.screenshot()
        pixels = sp.az.inspect_png(frame, points)["samples"]
        matching = sum(max(abs(a - b) for a, b in zip(rgb, tint)) <= 28 for rgb in pixels.values())
        if matching >= 12:
            (output / f"{key}.png").write_bytes(frame)
            return
        time.sleep(0.2)
    (output / f"{key}.png").write_bytes(frame)
    raise AssertionError(f"{block} {key} did not render the stock bar fill in {output}")


def verify_input(log, emulator, rpc, output):
    verify_bar(log, emulator, rpc, output, "heat-source", "heat", 1000, "Heat")
    verify_bar(log, emulator, rpc, output, "air-factory", "progress", 500, "Progress: 50%")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", type=int, default=16684)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with args.server.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != SERVER_SHA256:
            raise ValueError("Expected the unmodified v160.8 Java server")
    fixture = create_fixture(args)
    for live in (False, True):
        buttons = {0: sp.KEY_X, 122: sp.KEY_START} if live else {111: sp.KEY_SELECT, 122: sp.KEY_START}
        replay(args, "new3ds", fixture, live, taps={62: (72, 184), 88: (232, 184)},
               buttons=buttons, button_durations={122: 5}, movie_seconds=142,
               verify_input=verify_input, read_stock=read_back)
        print(f"UPSTREAM_SPECIFIC_BARS_PASS model=new3ds live={live} heat=ok progress=ok", flush=True)


if __name__ == "__main__":
    main()
