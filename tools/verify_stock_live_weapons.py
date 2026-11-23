"""Verify the unified ARM11 controls against the stock Java weapon simulation."""

import argparse
from pathlib import Path

import verify_upstream_singleplayer as sp
from verify_stock_command_orders import replay
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import stock_command
from verify_upstream_live_net import stop, wait_for


KEY_RIGHT = 1 << 4
KEY_L = 1 << 9
BUTTONS = {
    0: sp.KEY_X, 34: sp.KEY_X | KEY_RIGHT, 42: KEY_L, 53: sp.KEY_X,
    61: KEY_L, 73: KEY_RIGHT, 94: sp.KEY_START,
}
DURATIONS = {34: 4, 42: 4, 53: 5, 61: 4, 73: 3}
CIRCLE = {second: (0, 106) for second in range(53, 58)}
OBSERVER = (
    '(() => { let phase=0, shotSnapshot=0, unitId=-1, x=0, y=0; '
    'Events.run(Packages.mindustry.game.EventType.Trigger.update, () => { '
    'const p=Groups.player.find(boolf(v=>String(v.name)==="Min3DStry ARM11")); '
    'if (phase>=5 || p==null || p.dead()) return; '
    'const u=p.unit(), seq=p.con.lastReceivedClientSnapshot; '
    'if (seq<=0 || String(u.type.name)!=="gamma") return; '
    'if (unitId<0){ unitId=u.id; x=u.x; y=u.y; } '
    'if (u.id!=unitId) return; '
    'if (phase===4){ if (u.x>x+4 && !u.isShooting){ '
    'print("UPSTREAM_WEAPON_MOVEMENT_RESUMED unit="+unitId); phase++; } return; } '
    'if (Math.abs(u.x-x)>1 || Math.abs(u.y-y)>1) return; '
    'const east=phase<2, axis=east ? "east" : "north"; '
    'if (phase%2===1){ if (seq>shotSnapshot && !u.isShooting){ '
    'print("UPSTREAM_WEAPON_RELEASE axis="+axis+" unit="+unitId); phase++; } return; } '
    'const dx=u.aimX-u.x, dy=u.aimY-u.y; '
    'if (!u.isShooting || !(east ? dx>0 && Math.abs(dy)<0.1 : dy>0 && Math.abs(dx)<0.1)) return; '
    'const shot=Groups.bullet.find(boolf(b=>b.owner!=null && b.owner.id==unitId '
    '&& b.type.id==u.mounts[0].weapon.bullet.id && (east ? b.vel.x>0 && Math.abs(b.vel.y)<b.vel.x*0.25 '
    ': b.vel.y>0 && Math.abs(b.vel.x)<b.vel.y*0.25))); '
    'if (shot!=null){ shotSnapshot=seq; phase++; '
    'print("UPSTREAM_WEAPON_SHOT axis="+axis+" unit="+unitId+" bullet="+shot.id); } '
    '}); return "UPSTREAM_WEAPON_OBSERVER_READY"; })()'
)


def create_fixture(args, output):
    server, log = stock_session(args, output)
    try:
        stock_command(server, log, 'js Vars.state.rules.waves=false; Vars.state.rules.fog=false; "WEAPON_WORLD_READY"',
                      "WEAPON_WORLD_READY")
        stock_command(server, log, "save weapons", r"Saved to .*weapons\.msav")
    finally:
        stop(server)
    return output / "config/saves/weapons.msav"


def prepare_server(server, log):
    stock_command(server, log, "js " + OBSERVER, "UPSTREAM_WEAPON_OBSERVER_READY")


def verify_controls(log, proc, rpc, output):
    sp._wait_for_log(log, proc, r"UPSTREAM_LIVE_CONTROLS_STOPPED unit=\d+ input=dpad\b", 110)
    (output / "after-fire-and-move.png").write_bytes(rpc.screenshot())


def read_stock_weapons(server, log):
    for axis in ("east", "north"):
        wait_for(log, server, rf"UPSTREAM_WEAPON_SHOT axis={axis} unit=\d+ bullet=\d+\b", 8)
        wait_for(log, server, rf"UPSTREAM_WEAPON_RELEASE axis={axis} unit=\d+\b", 8)
    wait_for(log, server, r"UPSTREAM_WEAPON_MOVEMENT_RESUMED unit=\d+\b", 8)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=("new3ds", "old3ds"), action="append")
    parser.add_argument("--port", type=int, default=16664)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    fixture = args.fixture or create_fixture(args, args.output / "stock-fixture")
    for model in args.model or ("old3ds", "new3ds"):
        replay(args, model, fixture, True, taps={}, buttons=BUTTONS, button_durations=DURATIONS, circle=CIRCLE,
               prepare_server=prepare_server, verify_input=verify_controls, read_stock=read_stock_weapons)
        print(f"UPSTREAM_STOCK_LIVE_WEAPONS model={model} east=ok north=ok releases=ok move=ok", flush=True)


if __name__ == "__main__":
    main()
