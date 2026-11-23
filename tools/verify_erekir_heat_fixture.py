"""Create the stock Erekir heat fixture and verify port save readback in Java v160.8."""

import argparse
from pathlib import Path
import time

from verify_offline_material_buildings import capture
from verify_stock_command_orders import replay
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import stock_command
from verify_upstream_live_net import stop, wait_for
import verify_upstream_singleplayer as sp


PLACEMENTS = (
    ("neoplasiaReactor", 271, 103, 5),
    ("heatRedirector", 275, 103, 3),
    ("carbideCrucible", 278, 103, 3),
    ("reinforcedLiquidTank", 271, 107, 3),
    ("liquidSource", 268, 103, 1),
    ("liquidSource", 271, 100, 1),
    ("beamNode", 271, 98, 1),
    ("beamNode", 278, 98, 1),
)
REFERENCES = (
    "const r=Vars.world.build(271,103), h=Vars.world.build(275,103), "
    "c=Vars.world.build(278,103), o=Vars.world.build(271,107); "
)


def create(args, base, output):
    server, log = stock_session(args, output, base)
    try:
        checks = " ".join(
            f"check({x},{y},{size});" for _, x, y, size in PLACEMENTS
        )
        buildings = " ".join(
            f"Vars.world.tile({x},{y}).setBlock(Blocks.{name},team,0);" for name, x, y, _ in PLACEMENTS
        )
        setup = (
            "const rules=Vars.state.rules, team=rules.defaultTeam; "
            "const check=(x,y,n)=> { const low=-Math.floor((n-1)/2), high=Math.floor(n/2); "
            "for(let dx=low;dx<=high;dx++) for(let dy=low;dy<=high;dy++) "
            "if (Vars.world.build(x+dx,y+dy) !== null) throw 'Occupied heat fixture tile'; }; "
            + checks + buildings + REFERENCES
            + "rules.waves=false; rules.fog=false; "
            "r.items.add(Items.phaseFabric,8); c.items.add(Items.tungsten,12); c.items.add(Items.graphite,18); "
            "const SaveIO=Packages.mindustry.io.SaveIO, Fi=Packages.arc.files.Fi; "
            "SaveIO.save(Fi.get('config/saves/heat-cold.msav')); "
            "Vars.world.build(268,103).source=Liquids.arkycite; Vars.world.build(271,100).source=Liquids.water; "
            "SaveIO.save(Fi.get('config/saves/heat-onset.msav')); "
            "let stage=0; Events.run(Packages.mindustry.game.EventType.Trigger.update, () => { "
            "if(stage===0 && Math.abs(c.heat-36)<0.01 && r.heat===60 && "
            "c.items.get(Items.carbide)>0 && o.liquids.get(Liquids.neoplasm)>0) { "
            "stage=1; h.rotation=1; print('STOCK_HEAT_ACTIVE 60/36/36'); "
            "} else if(stage===1 && c.heat===0) { stage=2; h.rotation=0; print('STOCK_HEAT_ISOLATED'); "
            "} else if(stage===2 && Math.abs(c.heat-36)<0.01) { "
            "stage=3; print('STOCK_HEAT_RESTORED'); } }); return 'STOCK_HEAT_FIXTURE_SAVED';"
        )
        stock_command(server, log, "js (() => { " + setup + " })()", "STOCK_HEAT_FIXTURE_SAVED")
        wait_for(log, server, r"STOCK_HEAT_ACTIVE 60/36/36", 35)
        wait_for(log, server, r"STOCK_HEAT_ISOLATED", 10)
        wait_for(log, server, r"STOCK_HEAT_RESTORED", 10)
    finally:
        stop(server)
    print("Stock Java v160.8 produced carbide and neoplasm, then isolated and restored heat.")
    return output / "config/saves/heat-onset.msav"


def check(args, save, output):
    server, log = stock_session(args, output, save)
    try:
        check_loaded(server, log)
    finally:
        stop(server)
    print("Stock Java v160.8 reopened the port save and verified the original chain and outputs.")


def check_loaded(server, log):
    checks = (
        REFERENCES
        + "if (r.block!==Blocks.neoplasiaReactor || h.block!==Blocks.heatRedirector || h.rotation!==0 "
        "|| c.block!==Blocks.carbideCrucible || c.items.get(Items.carbide)<=0 "
        "|| o.block!==Blocks.reinforcedLiquidTank || o.liquids.get(Liquids.neoplasm)<=0 "
        "|| r.heat<59.9 || r.productionEfficiency<0.99) throw 'Heat production state did not round-trip'; "
        "return 'STOCK_HEAT_READBACK_OK';"
    )
    stock_command(server, log, "js (() => { " + checks + " })()", "STOCK_HEAT_READBACK_OK")


def verify_rom(args, model, fixture):
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    visible = r"UPSTREAM_SP_FRAME .*left=269 bottom=102 sprites=[1-9]\d*\b"
    cold = capture(args.output / model / "cold", model, fixture.with_name("heat-cold.msav"),
                   args.rom, azahar, frame_pattern=visible)
    points = tuple((x, y) for x in range(150, 191, 2) for y in range(189, 230, 2))
    baseline = sp.az.inspect_png(cold, points)["samples"]

    def verify_input(log, proc, rpc, output):
        sp._wait_for_log(log, proc, visible, 120)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            image = rpc.screenshot()
            active = sp.az.inspect_png(image, points)["samples"]
            heated = sum(active[p][0] >= baseline[p][0] + 20 for p in points)
            if heated >= 24:
                (output / "heat-active.png").write_bytes(image)
                print(f"{model}: original heat layers brightened {heated} redirector samples", flush=True)
                return
            time.sleep(0.25)
        raise AssertionError(f"{model}: original heat layers missing from the redirector")

    replay(args, model, fixture, False, taps={}, buttons={58: sp.KEY_SELECT, 72: sp.KEY_START},
           verify_input=verify_input, read_stock=check_loaded,
           offline_ready=r"UPSTREAM_SP_READY .*x=2212\.0 y=860\.0")
    print(f"{model}: stock Java reopened the ARM11 heat, carbide and neoplasm save", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", type=int, default=16683)
    operation = parser.add_mutually_exclusive_group(required=True)
    operation.add_argument("--create", type=Path, help="Original Onset .msav")
    operation.add_argument("--check", type=Path, help="Port-written .msav to reopen in stock Java")
    operation.add_argument("--fixture", type=Path, help="Stock heat-onset.msav and sibling heat-cold.msav")
    parser.add_argument("--rom", type=Path)
    parser.add_argument("--azahar")
    parser.add_argument("--model", choices=("old3ds", "new3ds"), action="append")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.create:
        print(create(args, args.create, args.output))
    elif args.check:
        check(args, args.check, args.output)
    else:
        if not args.rom:
            parser.error("--fixture needs the --rom to verify")
        for model in args.model or ("old3ds", "new3ds"):
            verify_rom(args, model, args.fixture)


if __name__ == "__main__":
    main()
