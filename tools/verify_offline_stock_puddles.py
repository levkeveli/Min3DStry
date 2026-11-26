"""Verify original water and neoplasm puddles in a stock Glacier save."""

import argparse
from pathlib import Path

from stock_puddle_fixture import DEPOSIT, SETUP, changed_colors
from verify_offline_material_buildings import capture
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import stock_command
from verify_upstream_live_net import stop
import verify_upstream_singleplayer as sp


CHECK_PUDDLES = (
    "if(!puddles.hasLiquid(Vars.world.tile(75,24),Liquids.water) "
    "|| !puddles.hasLiquid(Vars.world.tile(85,24),Liquids.neoplasm)) throw 'Puddles were not present'; "
)


def create(args):
    root = args.output / "stock-fixture"
    stock, log = stock_session(args, root)
    try:
        stock_command(stock, log, "js (()=>{" + SETUP + "return 'STOCK_PUDDLE_TERRAIN';})()",
                      "STOCK_PUDDLE_TERRAIN")
        stock_command(stock, log, "save puddles-clear", r"Saved to .*puddles-clear\.msav")
        command = (
            "js (()=>{" + DEPOSIT + CHECK_PUDDLES
            + "Packages.mindustry.io.SaveIO.save(Packages.arc.files.Fi.get('config/saves/puddles-active.msav')); "
            "return 'STOCK_PUDDLES_SAVED';})()"
        )
        stock_command(stock, log, command, "STOCK_PUDDLES_SAVED")
        stock_command(stock, log, "stop", r"Stopped server")
        stock_command(stock, log, "load puddles-active", r"Save loaded\.")
        stock_command(stock, log, "js (()=>{const puddles=Packages.mindustry.entities.Puddles; "
                      + CHECK_PUDDLES + "return 'STOCK_PUDDLES_REOPENED';})()", "STOCK_PUDDLES_REOPENED")
    finally:
        stop(stock)
    saves = root / "config/saves"
    return saves / "puddles-clear.msav", saves / "puddles-active.msav"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", type=int, default=16684)
    parser.add_argument("--rom", type=Path)
    parser.add_argument("--azahar")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    clear, active = create(args)
    if not args.rom:
        print(f"Stock Java wrote {clear.name} and {active.name}.")
        return
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    for model in ("new3ds",):
        before = capture(args.output / model / "clear", model, clear, args.rom, azahar)
        after = capture(args.output / model / "active", model, active, args.rom, azahar)
        water, neoplasm = changed_colors(before, after)
        if min(water, neoplasm) < 12:
            raise AssertionError(f"{model}: expected water and neoplasm colors, got {water}/{neoplasm}")
        print(f"{model}: original water and neoplasm pixels matched {water}/{neoplasm} samples.")


if __name__ == "__main__":
    main()
