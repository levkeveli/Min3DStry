"""Build a stock v160.8 schematic by replaying unified ARM11 touch and pad input."""

import argparse
from pathlib import Path
import shutil
import time

import verify_upstream_singleplayer as sp
from verify_stock_command_orders import replay
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import stock_command
from verify_upstream_live_net import stop


SETUP = (
    'const rules=Vars.state.rules, team=rules.defaultTeam; '
    'rules.waves=false; rules.fog=false; rules.schematicsAllowed=true; '
    'rules.polygonCoreProtection=false; rules.enemyCoreBuildRadius=0; rules.buildSpeedMultiplier=4; '
    'for(let y=23; y<=25; y++){const tile=Vars.world.tile(84,y); '
    'tile.setBlock(Blocks.air); tile.setFloor(Blocks.stone);} '
    'team.core().items.set(Items.lead,50); '
    'const Point2=Packages.arc.math.geom.Point2, Seq=Packages.arc.struct.Seq; '
    'const Schematic=Packages.mindustry.game.Schematic, Schematics=Packages.mindustry.game.Schematics; '
    'const parts=new Seq(), Tile=Schematic.Stile; '
    'parts.add(new Tile(Blocks.itemBridge,0,0,new Point2(2,0),0)); '
    'parts.add(new Tile(Blocks.sorter,1,0,Items.lead,0)); '
    'parts.add(new Tile(Blocks.itemBridge,2,0,null,0)); '
    'const file=new Packages.arc.files.Fi("factory.msch"); '
    'Schematics.write(new Schematic(parts,new Packages.arc.struct.StringMap(),3,1),file); '
    'return "STOCK_SCHEMATIC_FIXTURE_READY";'
)
RESULT = (
    'const source=Vars.world.build(84,23), sorter=Vars.world.build(84,24), receiver=Vars.world.build(84,25); '
    'if(source===null || source.block!==Blocks.itemBridge || receiver===null || '
    'receiver.block!==Blocks.itemBridge || source.link!==receiver.pos()) throw "Rotated bridge link did not build"; '
    'if(sorter===null || sorter.block!==Blocks.sorter || sorter.config()!==Items.lead) throw "Sorter did not keep lead"; '
    'const core=Vars.state.rules.defaultTeam.core(); '
    'if(core.items.get(Items.lead)>=50 || core.items.get(Items.copper)>=200) throw "Stock costs were not used"; '
    'return "STOCK_SCHEMATIC_RESULT builds=3 item=lead bridge=linked";'
)
TAPS = {35: (15, 156), 42: (140, 55), 48: (200, 152)}
ROTATE = 1 << 8
PLACE = 1 << 0


def create_fixture(args, output):
    server, log = stock_session(args, output)
    try:
        stock_command(server, log, "js (() => { " + SETUP + " })()", "STOCK_SCHEMATIC_FIXTURE_READY")
        stock_command(server, log, "save schematic", r"Saved to .*schematic\.msav")
    finally:
        stop(server)
    return output / "config/saves/schematic.msav", output / "factory.msch"


def verify_touch(log, proc, rpc, output, live):
    sp._wait_for_log(log, proc, r"UPSTREAM_SCHEMATIC_SELECTED blocks=3 width=3 height=1", 95)
    sp._wait_for_log(log, proc, r"UPSTREAM_SCHEMATIC_PREVIEW x=83 y=25 turns=1 plans=3", 35)
    time.sleep(1)
    (output / "rotated-preview.png").write_bytes(rpc.screenshot())
    sp._wait_for_log(log, proc, r"UPSTREAM_SCHEMATIC_QUEUED count=3 turns=1", 35)
    prefix = "UPSTREAM_LIVE_BUILD" if live else "UPSTREAM_SP_BUILT"
    for name, y in (("bridge-conveyor", 23), ("sorter", 24), ("bridge-conveyor", 25)):
        sp._wait_for_log(log, proc, rf"{prefix} block={name} x=84 y={y}\b", 50)


def read_stock_buildings(server, log):
    stock_command(server, log, "js (() => { " + RESULT + " })()",
                  "STOCK_SCHEMATIC_RESULT builds=3 item=lead bridge=linked")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--fixture-dir", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=("new3ds", "old3ds"), action="append")
    parser.add_argument("--port", type=int, default=16656)
    parser.add_argument("--live", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.fixture_dir:
        fixture = args.fixture_dir / "config/saves/schematic.msav"
        schematic = args.fixture_dir / "factory.msch"
    else:
        fixture, schematic = create_fixture(args, args.output / "stock-fixture")
    for model in args.model or ("new3ds",):
        for live in (False, True) if args.live else (False,):
            scene = args.output / model / ("live" if live else "offline")
            sd = scene / "sdmc/3ds/min3dstry/schematics"
            sd.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(schematic, sd / schematic.name)
            buttons = {55: ROTATE, 62: PLACE, 94: sp.KEY_START}
            if live:
                buttons[0] = sp.KEY_X
            else:
                buttons[84] = sp.KEY_SELECT
            replay(args, model, fixture, live, taps=TAPS, buttons=buttons,
                   verify_input=lambda log, proc, rpc, output: verify_touch(log, proc, rpc, output, live),
                   read_stock=read_stock_buildings)
            print(f"UPSTREAM_STOCK_SCHEMATIC model={model} live={live} rotation=ok config=ok builds=3", flush=True)


if __name__ == "__main__":
    main()
