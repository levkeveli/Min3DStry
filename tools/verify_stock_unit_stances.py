"""Replay original unit commands, mining stances, and Hold Fire on ARM11."""

import argparse
from pathlib import Path

import verify_upstream_singleplayer as sp
from verify_stock_command_orders import replay
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import capture_factory_menu, stock_command
from verify_upstream_live_net import stop


SETUP = (
    'const rules=Vars.state.rules, team=rules.defaultTeam; rules.waves=false; rules.fog=false; '
    'const dagger=UnitTypes.dagger.create(team); dagger.set(600,216); dagger.add(); '
    'const mono=UnitTypes.mono.create(team); mono.set(616,216); mono.add(); '
    'mono.command().command = Packages.mindustry.ai.UnitCommand.moveCommand; '
    'return "STANCE_FIXTURE_READY";'
)
RESULT = (
    'const team=Vars.state.rules.defaultTeam; '
    'const dagger=Groups.unit.find(u => u.team === team && u.type === UnitTypes.dagger); '
    'const mono=Groups.unit.find(u => u.team === team && u.type === UnitTypes.mono); '
    'const command=Packages.mindustry.ai.UnitCommand, stance=Packages.mindustry.ai.UnitStance; '
    'const copper=Packages.mindustry.ai.ItemUnitStance.getByItem(Items.copper); '
    'if (dagger === null || mono === null || dagger.command().currentCommand() !== command.moveCommand || '
    '!dagger.command().hasStance(stance.holdFire) || mono.command().hasStance(stance.holdFire) || '
    'mono.command().currentCommand() !== command.mineCommand || !mono.command().hasStance(copper)) '
    'throw "Expected a mining Mono with the copper stance and a Dagger holding fire"; '
    'return "STOCK_STANCE_RESULT mono=mine-copper dagger=holdfire";'
)
TAPS = {
    36: (305, 128), 41: (72, 120), 46: (104, 120),
    50: (14, 64), 56: (160, 100),
    61: (14, 96), 66: (160, 74),
    71: (14, 96), 75: (270, 215), 80: (160, 50),
}


def create_fixture(args, output):
    server, log = stock_session(args, output)
    try:
        stock_command(server, log, "js (() => { " + SETUP + " })()", "STANCE_FIXTURE_READY")
        stock_command(server, log, "save stances", r"Saved to .*stances\.msav")
    finally:
        stop(server)
    return output / "config/saves/stances.msav"


def verify_stance_input(log, proc, rpc, output):
    sp._wait_for_log(log, proc, r"UPSTREAM_COMMAND_SELECTION units=2 buildings=0", 100)
    sp._wait_for_log(log, proc, r"UPSTREAM_MENU_FONT source=v160.8 catalog=command\b", 25)
    (output / "stock-command-icons.png").write_bytes(capture_factory_menu(rpc))
    sp._wait_for_log(log, proc, r"UPSTREAM_UNIT_COMMAND command=mine units=1\b", 35)
    sp._wait_for_log(log, proc, r"UPSTREAM_MENU_FONT source=v160.8 catalog=stance\b", 25)
    (output / "stock-stance-icons.png").write_bytes(capture_factory_menu(rpc))
    sp._wait_for_log(log, proc, r"UPSTREAM_UNIT_STANCE stance=holdfire enabled=true units=1\b", 30)
    menu_font = r"UPSTREAM_MENU_FONT source=v160.8 catalog=stance\b"
    sp._wait_for_log(log, proc, menu_font + r"[\s\S]*" + menu_font, 25)
    (output / "stock-ore-icons.png").write_bytes(capture_factory_menu(rpc))
    sp._wait_for_log(log, proc, r"UPSTREAM_UNIT_STANCE stance=item-copper enabled=true units=1\b", 35)


def read_stock_stances(server, log):
    stock_command(server, log, "js (() => { " + RESULT + " })()", "STOCK_STANCE_RESULT mono=mine-copper dagger=holdfire")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=("new3ds",), action="append")
    parser.add_argument("--port", type=int, default=16638)
    parser.add_argument("--live", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True)
    fixture = args.fixture or create_fixture(args, args.output / "stock-fixture")
    for model in args.model or ("new3ds",):
        for live in (False, True) if args.live else (False,):
            replay(args, model, fixture, live, taps=TAPS, verify_input=verify_stance_input, read_stock=read_stock_stances)
            print(f"UPSTREAM_STOCK_STANCES model={model} live={live} mine=copper holdfire=ok", flush=True)


if __name__ == "__main__":
    main()
