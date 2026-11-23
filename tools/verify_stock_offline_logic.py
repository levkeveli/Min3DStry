"""Verify the original offline Canvas, Logic Displays and marker save data."""

import argparse
import json
from pathlib import Path

import verify_upstream_singleplayer as sp
from verify_stock_command_orders import replay
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import stock_command
from verify_upstream_live_net import capture_marker_pixels, stop


PROGRAM = """jump 11 strictEqual @clientUnit null
getlink screen 0
getlink tiled 1
getlink canvas 2
lookup item material COPPER_LOGIC_ID
jump 11 notEqual material @copper
draw clear 30 200 240 0 0 0
drawflush screen
draw clear 160 210 75 0 0 0
drawflush tiled
write 7 canvas 0
end
"""
SETUP = (
    "const rules=Vars.state.rules, team=rules.defaultTeam; "
    "rules.waves=false; rules.fog=false; rules.worldProcessorPlayerLink=true; "
    "rules.allowEditWorldProcessors=true; "
    "Vars.world.tile(77,26).setBlock(Blocks.worldProcessor,team); "
    "Vars.world.tile(77,24).setBlock(Blocks.logicDisplay,team); "
    "Vars.world.tile(76,21).setBlock(Blocks.tileLogicDisplay,team); "
    "Vars.world.tile(77,21).setBlock(Blocks.tileLogicDisplay,team); "
    "Vars.world.tile(82,29).setBlock(Blocks.canvas,team); "
    "const c=Vars.world.build(82,29), screen=Vars.world.build(77,24); "
    "for(let i=0;i<144;i++) c.setPixel(i,i%12<6 ? 1 : 6); "
    "const p=Vars.world.build(77,26), links=new Seq(); "
    "const type=Packages.mindustry.world.blocks.logic.LogicBlock, Link=type.LogicLink; "
    "links.add(new Link(0,-2,'display1',true)); links.add(new Link(-1,-5,'display2',true)); "
    "links.add(new Link(5,3,'canvas1',true)); "
    "const copper=Vars.logicVars.lookupLogicId(Items.copper); if(copper<0) throw 'Missing Copper logic ID'; "
    f"const program={json.dumps(PROGRAM)}.replace('COPPER_LOGIC_ID',String(copper)); "
    "p.configured(null,type.compress(program,links)); "
    "if(p.executor.instructions.length!==12 || p.executor.links.length!==3) throw 'Invalid stock logic program'; "
    "Vars.state.markers.add(781,new MapObjectives.TextureMarker(screen,680,184,24,24)); "
    "if(Vars.world.tile(81,26).block()!==Blocks.coreNucleus) throw 'Fixture touched the core'; "
    "return 'OFFLINE_LOGIC_FIXTURE_READY';"
)


def verify_save(server, log, first_pixel):
    script = (
        "const p=Vars.world.build(77,26), c=Vars.world.build(82,29), display=Vars.world.build(77,24); "
        "const marker=Vars.state.markers.get(781); "
        "if(p.executor.instructions.length!==12 || p.executor.links.length!==3 || "
        "p.executor.links[0]!==display || p.executor.links[1]!==Vars.world.build(76,21) || "
        "p.executor.links[2]!==c || marker==null || marker.texture.value!==display || "
        f"c.getPixel(0)!=={first_pixel} || c.getPixel(143)!==6) throw 'Stock logic or Canvas save mismatch'; "
        "return 'STOCK_OFFLINE_LOGIC_SAVE_OK';"
    )
    stock_command(server, log, "js (() => { " + script + " })()", "STOCK_OFFLINE_LOGIC_SAVE_OK")


def create_fixture(args):
    root = args.output / "stock-fixture"
    server, log = stock_session(args, root)
    try:
        stock_command(server, log, "js (() => { " + SETUP + " })()", "OFFLINE_LOGIC_FIXTURE_READY")
        verify_save(server, log, 1)
        stock_command(server, log, "save offline-logic", r"Saved to .*offline-logic\.msav")
    finally:
        stop(server)
    return root / "config/saves/offline-logic.msav"


def verify_world(log, proc, rpc, output):
    points = ((230, 30), (250, 50), (123, 130), (120, 210), (275, 155))
    expected = ((196, 93, 159), (147, 212, 181), (30, 200, 240), (160, 210, 75), (30, 200, 240))
    capture_marker_pixels(rpc, output, "stock", "offline-logic", points, 30, expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--model", choices=("old3ds", "new3ds"), action="append")
    parser.add_argument("--port", type=int, default=16679)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    fixture = create_fixture(args)
    for model in args.model or ("old3ds", "new3ds"):
        replay(args, model, fixture, False, taps={}, buttons={50: sp.KEY_SELECT, 64: sp.KEY_START},
               verify_input=verify_world, read_stock=lambda server, log: verify_save(server, log, 7))
        print(f"{model}: stock offline Canvas, display, tiled display, marker and Java save readback passed",
              flush=True)


if __name__ == "__main__":
    main()
