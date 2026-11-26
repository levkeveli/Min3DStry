"""Replay the original Gamma weapons offline on Azahar in New 3DS mode by default."""

import argparse
from pathlib import Path
import re
import shutil
import tempfile

import verify_upstream_singleplayer as sp
from verify_upstream_factory_plans import start_server, stock_command, write_movie
from verify_upstream_live_net import stop, wait_for


KEY_DRIGHT = 1 << 4
KEY_L = 1 << 9
# Give the offline loop time to observe movement before pulsing X. The pulse is
# aligned to the ARM11 input poll on Azahar 2126.2 and stays below the aim threshold.
BUTTONS = {32: KEY_DRIGHT, 33.7: sp.KEY_X | KEY_DRIGHT, 42: sp.KEY_X, 50: sp.KEY_X | KEY_DRIGHT,
           60: KEY_L, 72: sp.KEY_SELECT, 82: sp.KEY_START}
SETUP = (
    'const rules=Vars.state.rules; rules.waves=false; '
    'for (let y=24; y<=28; y++) for (let x=84; x<=88; x++) Vars.world.tile(x,y).setBlock(Blocks.air); '
    'const enemy=UnitTypes.fortress.create(rules.waveTeam); enemy.set(696,208); '
    'enemy.apply(StatusEffects.unmoving,36000); enemy.apply(StatusEffects.disarmed,36000); enemy.add(); '
    'if (rules.defaultTeam.core().block.unitType !== UnitTypes.gamma) throw "Expected Gamma"; '
    'return "WEAPON_FIXTURE_READY";'
)
RESULT = (
    'const target=Groups.unit.find(u => u.type === UnitTypes.fortress && u.team === Vars.state.rules.waveTeam); '
    'if (target === null || target.health <= 0 || target.health >= target.maxHealth) '
    'throw "Expected the stock Fortress with saved damage"; '
    'return "STOCK_WEAPON_DAMAGE health=" + target.health + " max=" + target.maxHealth;'
)


def verify_stock(args, output, save=None):
    output.mkdir(parents=True)
    saves = output / "config/saves"
    if save is not None:
        saves.mkdir(parents=True)
        shutil.copyfile(save, saves / "weapons.msav")
    log = output / "server.log"
    server = start_server(args, output, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        action = "host Glacier survival" if save is None else "load weapons"
        stock_command(server, log, f"config port {args.port}\n{action}", rf"Opened a server on port {args.port}\.")
        if save is None:
            stock_command(server, log, "js (() => { " + SETUP + " })()", "WEAPON_FIXTURE_READY")
            stock_command(server, log, "save weapons", r"Saved to .*weapons\.msav")
        else:
            stock_command(server, log, "js (() => { " + RESULT + " })()", "STOCK_WEAPON_DAMAGE")
    finally:
        stop(server)
    return saves / "weapons.msav"


def replay(args, model, fixture):
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    output = args.output / model
    sd = (output / "sdmc").resolve()
    save = sd / "3ds/min3dstry/singleplayer-save.msav"
    save.parent.mkdir(parents=True)
    sp.stage_direct_startup(sd)
    shutil.copyfile(fixture, save)
    movie = output / "weapons.ctm"
    write_movie(movie, model, taps={}, buttons=BUTTONS, seconds=90, button_durations={32: 1.7, 33.7: 0.23})
    isolated = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        initial = (originals[config] or b"").decode("utf-8")
        config.write_text(sp.az.patch_qt_config_text(initial, isolated), encoding="utf-8")
        proc, rpc = sp._launch_azahar(model, args.rom, azahar, config, log, movie)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_READY .*x=648\.0 y=208\.0", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_ENEMY type=fortress\b", 30)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_MOVING .*input=dpad\b", 70)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_PAUSE paused=true\b", 80)
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_PAUSE paused=false\b", 35)
            fire = r"UPSTREAM_SP_FIRE shooting=true unit=gamma x=([0-9.]+) y=([0-9.]+)"
            fire_log = sp._wait_for_log(log, proc, fire, 50)
            aim_x, aim_y = map(float, re.search(fire, fire_log).groups())
            if aim_x < 750 or abs(aim_y - 208) >= 3:
                raise AssertionError(f"Expected eastward aim, got ({aim_x}, {aim_y})")
            release = r"UPSTREAM_SP_FIRE shooting=false unit=gamma .* shots=([1-9][0-9]*)"
            release_log = sp._wait_for_log(log, proc, release, 30)
            shots = int(re.search(release, release_log).group(1))
            sp._wait_for_log(log, proc, rf"UPSTREAM_SP_WEAPON_SAVED shots={shots}\b", 50)
            (output / "after-fire.png").write_bytes(rpc.screenshot())
            sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 40)
            if "UPSTREAM_SP_MOVING" in fire_log.split("UPSTREAM_SP_PAUSE paused=false", 1)[1]:
                raise AssertionError("Aim input should leave movement idle")
        finally:
            rpc.close()
            sp._stop_proc(proc, azahar)
            if log.is_file():
                shutil.copyfile(log, output / "azahar.log")
    finally:
        for path, data in originals.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)
    verify_stock(args, output / "stock-result", save)
    print(f"UPSTREAM_STOCK_OFFLINE_WEAPONS model={model} pause=ok aim=ok release=ok damage=ok", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--output", type=Path, required=True, help="Evidence directory; reruns create a child directory")
    parser.add_argument("--model", choices=("new3ds",), action="append")
    parser.add_argument("--port", type=int, default=16618)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.iterdir()):
        args.output = Path(tempfile.mkdtemp(prefix="replay-", dir=args.output))
        print(f"Writing new evidence to {args.output}", flush=True)
    fixture = args.fixture or verify_stock(args, args.output / "stock-fixture")
    for model in args.model or ("new3ds",):
        replay(args, model, fixture)


if __name__ == "__main__":
    main()
