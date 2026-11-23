"""Replay original unit and turret possession with the stock v160.8 reference."""

import argparse
from pathlib import Path

import verify_upstream_singleplayer as sp
from verify_stock_command_orders import replay
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import stock_command
from verify_upstream_live_net import stop


SETUP = (
    'const rules=Vars.state.rules, team=rules.defaultTeam; '
    'rules.waves=false; rules.fog=false; rules.possessionAllowed=true; '
    'const dagger=UnitTypes.dagger.create(team); dagger.set(600,216); dagger.add(); '
    'const tile=Vars.world.tile(78,22); tile.setBlock(Blocks.duo,team,0); '
    'tile.build.handleStack(Items.copper,15,dagger); '
    'tile.build.rotation = 0; '
    'return "POSSESSION_FIXTURE_READY";'
)
RESULT = (
    'const team=Vars.state.rules.defaultTeam, turret=Vars.world.build(78,22); '
    'if (turret === null || turret.block !== Blocks.duo || turret.controlled() || '
    'turret.totalAmmo >= 30 || turret.totalAmmo <= 0) throw "Expected released Duo with ammo consumed by manual fire"; '
    'const angle=turret.sense(Packages.mindustry.logic.LAccess.rotation); '
    'if (Math.abs(angle - 90) > 5) throw "The released Duo lost its northward aim"; '
    'const dagger=Groups.unit.find(u=>u.type===UnitTypes.dagger && u.team===team); '
    'if(dagger===null || dagger.isPlayer()) throw "Expected released allied Dagger"; '
)
LIVE_RESULT = (
    'const actor=Groups.player.find(p=>String(p.name)==="Min3DStry ARM11"); '
    'if(actor===null || actor.dead() || actor.unit().type!==UnitTypes.gamma || !actor.unit().spawnedByCore()) '
    'throw "Expected the ARM11 player to return to the original core unit"; '
)
TAPS = {
    35: (305, 156), 41: (72, 120), 49: (305, 156), 55: (216, 216),
    73: (305, 156), 78: (305, 192),
}
AIM_NORTH = 1 << 6
FIRE = 1 << 9


def create_fixture(args, output):
    server, log = stock_session(args, output)
    try:
        stock_command(server, log, "js (() => { " + SETUP + " })()", "POSSESSION_FIXTURE_READY")
        stock_command(server, log, "save possession", r"Saved to .*possession\.msav")
    finally:
        stop(server)
    return output / "config/saves/possession.msav"


def verify_controls(log, proc, rpc, output):
    sp._wait_for_log(log, proc, r"UPSTREAM_CONTROL_FOCUS target=dagger\b", 90)
    sp._wait_for_log(log, proc, r"UPSTREAM_CONTROL_FOCUS target=duo\b", 45)
    (output / "controlled-duo.png").write_bytes(rpc.screenshot())
    release = r"UPSTREAM_CONTROL_RELEASE\b[\s\S]*UPSTREAM_CONTROL_FOCUS target=gamma\b"
    sp._wait_for_log(log, proc, release, 55)


def read_stock_result(server, log, live):
    script = RESULT + (LIVE_RESULT if live else "") + 'return "STOCK_POSSESSION_RESULT duo=fired release=ok";'
    stock_command(server, log, "js (() => { " + script + " })()", "STOCK_POSSESSION_RESULT duo=fired release=ok")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=("new3ds", "old3ds"), action="append")
    parser.add_argument("--port", type=int, default=16648)
    parser.add_argument("--live", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True)
    fixture = args.fixture or create_fixture(args, args.output / "stock-fixture")
    for model in args.model or ("old3ds", "new3ds"):
        for live in (False, True) if args.live else (False,):
            buttons = ({0: sp.KEY_X, 61: AIM_NORTH, 67: FIRE, 94: sp.KEY_START} if live
                       else {61: AIM_NORTH, 67: FIRE, 87: sp.KEY_SELECT, 94: sp.KEY_START})
            replay(args, model, fixture, live, taps=TAPS, buttons=buttons, verify_input=verify_controls,
                   read_stock=lambda server, log: read_stock_result(server, log, live))
            print(f"UPSTREAM_STOCK_POSSESSION model={model} live={live} dagger=ok duo=fired release=ok", flush=True)


if __name__ == "__main__":
    main()
