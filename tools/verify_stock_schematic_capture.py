"""Capture a world region on ARM11 and decode the SD export with stock v160.8."""

import argparse
import json
from pathlib import Path
import subprocess
import time

import verify_upstream_singleplayer as sp
from verify_stock_command_orders import replay
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import stock_command
from verify_upstream_live_net import stop


SETUP = (
    'const rules=Vars.state.rules, team=rules.defaultTeam; rules.waves=false; rules.fog=false; '
    'const source=Vars.world.tile(84,23), sorter=Vars.world.tile(84,24), receiver=Vars.world.tile(84,25); '
    'source.setBlock(Blocks.itemBridge,team,0); sorter.setBlock(Blocks.sorter,team,0); '
    'receiver.setBlock(Blocks.itemBridge,team,0); '
    'source.build.configureAny(new Packages.arc.math.geom.Point2(0,2)); '
    'sorter.build.configureAny(Items.lead); '
    'if (source.build.link!==receiver.pos() || sorter.build.config()!==Items.lead) throw "Fixture differs"; '
    'return "STOCK_CAPTURE_FIXTURE_READY";'
)
TAPS = {35: (15, 155), 42: (268, 20), 48: (216, 184), 55: (216, 152)}
PLACE = 1 << 0
EXISTING = b"preserve existing schematic\n"
CAPTURE_NAME = "Bridge layout"


def create_fixture(args, output):
    server, log = stock_session(args, output)
    try:
        stock_command(server, log, "js (() => { " + SETUP + " })()", "STOCK_CAPTURE_FIXTURE_READY")
        stock_command(server, log, "save capture", r"Saved to .*capture\.msav")
    finally:
        stop(server)
    return output / "config/saves/capture.msav"


def verify_touch(log, proc, rpc, output, use_xdotool):
    sp._wait_for_log(log, proc, r"UPSTREAM_SCHEMATIC_CAPTURE_AREA x=84 y=23 x2=84 y2=25", 105)
    time.sleep(1)
    (output / "capture-area.png").write_bytes(rpc.screenshot())
    sp._wait_for_log(log, proc, r"UPSTREAM_SCHEMATIC_NAME_PROMPT", 35)
    if use_xdotool:
        time.sleep(1)
        subprocess.run(["xdotool", "type", CAPTURE_NAME], check=True, timeout=10)
        subprocess.run(["xdotool", "key", "Return"], check=True, timeout=10)
    else:
        print(f"Enter {CAPTURE_NAME!r} in the Azahar software keyboard and confirm.", flush=True)
    sp._wait_for_log(log, proc, r"UPSTREAM_SCHEMATIC_SAVED file=Schematic-2\.msch blocks=3 width=1 height=3", 90)
    time.sleep(1)
    (output / "capture-saved.png").write_bytes(rpc.screenshot())


def read_stock_capture(server, log, directory):
    existing = directory / "Schematic-1.msch"
    exported = directory / "Schematic-2.msch"
    if existing.read_bytes() != EXISTING:
        raise AssertionError("ARM11 changed the existing .msch file")
    if sorted(path.name for path in directory.iterdir()) != [existing.name, exported.name]:
        raise AssertionError("Capture left a missing or unexpected file in the schematic directory")
    filename = json.dumps(exported.resolve().as_posix())
    script = (
        'const S=Packages.mindustry.game.Schematics; '
        f'const schematic=S.read(new Packages.arc.files.Fi({filename})); '
        'const source=schematic.tiles.find(t=>t.y===0), sorter=schematic.tiles.find(t=>t.y===1), '
        'receiver=schematic.tiles.find(t=>t.y===2); '
        'if (schematic.width!==1 || schematic.height!==3 || schematic.tiles.size!==3 || '
        f'String(schematic.name())!=={json.dumps(CAPTURE_NAME)} || source===null || sorter===null || receiver===null || '
        'source.block!==Blocks.itemBridge || receiver.block!==Blocks.itemBridge || sorter.block!==Blocks.sorter || '
        'source.config.x!==0 || source.config.y!==2 || sorter.config!==Items.lead || source.rotation!==0) '
        'throw "Stock decode differs from the captured factory"; '
        'return "STOCK_CAPTURE_RESULT blocks=3 item=lead link=0,2";'
    )
    stock_command(server, log, "js (() => { " + script + " })()", "STOCK_CAPTURE_RESULT blocks=3 item=lead link=0,2")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=("new3ds", "old3ds"), action="append")
    parser.add_argument("--port", type=int, default=16660)
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--xdotool", action="store_true", help="Enter the name in the Azahar Qt keyboard on X11")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    fixture = args.fixture or create_fixture(args, args.output / "stock-fixture")
    for model in args.model or ("old3ds", "new3ds"):
        for live in (False, True) if args.live else (False,):
            scene = args.output / model / ("live" if live else "offline")
            directory = scene / "sdmc/3ds/min3dstry/schematics"
            directory.mkdir(parents=True)
            (directory / "Schematic-1.msch").write_bytes(EXISTING)
            buttons = {62: PLACE, 94: sp.KEY_START}
            if live:
                buttons[0] = sp.KEY_X
            else:
                buttons[84] = sp.KEY_SELECT
            replay(args, model, fixture, live, taps=TAPS, buttons=buttons,
                   verify_input=lambda log, proc, rpc, target: verify_touch(log, proc, rpc, target, args.xdotool),
                   read_stock=lambda server, log: read_stock_capture(server, log, directory))
            print(f"UPSTREAM_STOCK_SCHEMATIC_CAPTURE model={model} live={live} original=ok link=0,2", flush=True)


if __name__ == "__main__":
    main()
