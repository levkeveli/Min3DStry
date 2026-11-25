"""Verify the unified ARM11 controls against the stock Java weapon simulation."""

import argparse
from pathlib import Path
import re
import time

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


REMOTE_PROJECTILES = (
    '(() => { const team=Vars.state.rules.defaultTeam; '
    'const tile=Vars.world.tile(74,30); tile.setBlock(Blocks.microProcessor,team,0); '
    'for(let x=82; x<=89; x++){const cell=Vars.world.tile(x,29); '
    'cell.setBlock(Blocks.air); cell.setFloor(Blocks.stone); } '
    'const unit=UnitTypes.dagger.create(team); unit.set(656,232); unit.rotation=0; unit.add(); '
    'const ai=new Packages.mindustry.ai.types.LogicAI(); ai.controller=tile.build; '
    'ai.aimControl=Packages.mindustry.logic.LUnitControl.target; ai.posTarget.set(760,232); '
    'ai.posTarget.team=team; unit.controller(ai); '
    'let start=0, fired=false; '
    'Events.run(Packages.mindustry.game.EventType.Trigger.update, () => { '
    'ai.controlTimer=600; '
    'const p=Groups.player.find(boolf(v=>String(v.name)==="Min3DStry ARM11")); '
    'if(p==null || p.con.lastReceivedClientSnapshot<=0) return; '
    'const now=Packages.arc.util.Time.millis(); if(start===0) start=now; '
    'ai.shoot=now-start>=6500 && now-start<10800; '
    'if(!fired && ai.shoot && Groups.bullet.contains(boolf(b=>b.owner!=null '
    '&& b.owner.id==unit.id && b.type.id==unit.mounts[0].weapon.bullet.id && b.vel.x>0))){ '
    'fired=true; print("UPSTREAM_REMOTE_PROJECTILE unit="+unit.id+" type=dagger"); } '
    '}); return "UPSTREAM_REMOTE_PROJECTILE_READY"; })()'
)
VISIBLE = r"UPSTREAM_NETWORK_PROJECTILES visible=[1-9]\d* active=[1-9]\d*\b"
CLEARED = r"UPSTREAM_NETWORK_PROJECTILES visible=0 active=0\b"


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


def prepare_projectiles(server, log):
    prepare_server(server, log)
    stock_command(server, log, "js " + REMOTE_PROJECTILES, "UPSTREAM_REMOTE_PROJECTILE_READY")


def capture_bolt(rpc, output, name, background, points, color):
    png = None
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        png = rpc.screenshot(res_scale=1)
        samples = sp.az.inspect_png(png, points)["samples"]
        matches = sum(any(abs(a - b) >= 16 for a, b in zip(pixel, background[point]))
                      and all(abs(a - b) <= 5 for a, b in zip(pixel, color))
                      for point, pixel in samples.items())
        if matches >= 2:
            (output / f"{name}.png").write_bytes(png)
            return
    if png is not None:
        (output / f"{name}-unexpected.png").write_bytes(png)
    raise AssertionError(f"The original {name} colors did not appear in the viewport")


def verify_projectiles(log, proc, rpc, output):
    # The fixed Glacier viewport places the Dagger north of the player's Gamma.
    # These are the original Pal.bulletYellow and Pal.yellowBoltFront colors.
    dagger_points = tuple((x, y) for x in range(248, 353, 2) for y in range(33, 68, 2))
    gamma_points = tuple((x, y) for x in range(264, 388, 2) for y in range(94, 126, 2))
    baseline = rpc.screenshot(res_scale=1)
    (output / "idle-projectiles.png").write_bytes(baseline)
    background = sp.az.inspect_png(baseline, dagger_points + gamma_points)["samples"]
    sp._wait_for_log(log, proc, VISIBLE, 30)
    capture_bolt(rpc, output, "remote-dagger-projectile", background, dagger_points, (255, 248, 232))
    gap = r"[\s\S]*?"
    sp._wait_for_log(log, proc, VISIBLE + gap + CLEARED + gap + VISIBLE, 45)
    capture_bolt(rpc, output, "gamma-projectile", background, gamma_points, (255, 210, 126))
    verify_controls(log, proc, rpc, output)
    states = re.findall(r"UPSTREAM_NETWORK_PROJECTILES visible=(\d+) active=(\d+)", log.read_text(encoding="utf-8", errors="replace"))
    if not states or states[-1] != ("0", "0"):
        raise AssertionError("The live projectile group did not clear before movement resumed")


def read_stock_projectiles(server, log):
    read_stock_weapons(server, log)
    wait_for(log, server, r"UPSTREAM_REMOTE_PROJECTILE unit=\d+ type=dagger\b", 8)


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
    parser.add_argument("--projectiles", action="store_true", help="Require original projectile drawing and cleanup")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    fixture = args.fixture or create_fixture(args, args.output / "stock-fixture")
    prepare = prepare_projectiles if args.projectiles else prepare_server
    verify = verify_projectiles if args.projectiles else verify_controls
    read_stock = read_stock_projectiles if args.projectiles else read_stock_weapons
    for model in args.model or ("new3ds",):
        replay(args, model, fixture, True, taps={}, buttons=BUTTONS, button_durations=DURATIONS, circle=CIRCLE,
               prepare_server=prepare, verify_input=verify, read_stock=read_stock)
        if args.projectiles:
            print(f"UPSTREAM_STOCK_PROJECTILES model={model} gamma=ok remote=dagger cleared=ok", flush=True)
        print(f"UPSTREAM_STOCK_LIVE_WEAPONS model={model} east=ok north=ok releases=ok move=ok", flush=True)


if __name__ == "__main__":
    main()
