"""Replay ARM11 unit orders and factory rally points with stock Mindustry v160.8."""

import argparse
from pathlib import Path
import shutil
import time

import verify_upstream_singleplayer as sp
from verify_stock_inventory_transfer import stock_session
from verify_upstream_factory_plans import stock_command, write_movie
from verify_upstream_live_net import stop, wait_for


SETUP = (
    'const rules=Vars.state.rules, team=rules.defaultTeam; rules.waves=false; rules.fog=false; '
    'for (let y=26; y<=30; y++) for (let x=74; x<=78; x++) Vars.world.tile(x,y).setBlock(Blocks.air); '
    'for (let x=75; x<=77; x+=2) { '
    'var unit=UnitTypes.dagger.create(team); unit.set(x*Vars.tilesize,27*Vars.tilesize); '
    'if (!unit.isCommandable()) throw "Expected original commandable Daggers"; unit.add(); } '
    'if (Groups.unit.count(u => u.team === team && u.type === UnitTypes.dagger) !== 2) throw "Expected two Daggers"; '
    'Vars.world.tile(78,22).setBlock(Blocks.groundFactory,team,0); '
    'return "COMMAND_FIXTURE_READY";'
)
RESULT = (
    'const team=Vars.state.rules.defaultTeam; '
    'const allied = u => u.team === team && u.type === UnitTypes.dagger; '
    'if (Groups.unit.count(allied) !== 2 || Groups.unit.count(u => allied(u) && u.y > 218) !== 2) '
    'throw "Expected both stock Daggers to move north"; '
    'const factory=Vars.world.build(78,22); '
    'if (factory.block !== Blocks.groundFactory || factory.commandPos === null || '
    'factory.commandPos.x !== 688 || factory.commandPos.y !== 192) throw "Wrong original factory rally point"; '
    'return "STOCK_COMMAND_RESULT daggers=2 rally=688,192";'
)
TAPS = {
    36: (305, 128), 42: (72, 120), 47: (104, 120), 53: (88, 88),
    62: (120, 200), 68: (248, 168),
}
QUEUE_ORDER = 1 << 8


def create_fixture(args, output):
    server, log = stock_session(args, output)
    try:
        stock_command(server, log, "js (() => { " + SETUP + " })()", "COMMAND_FIXTURE_READY")
        stock_command(server, log, "save commands", r"Saved to .*commands\.msav")
    finally:
        stop(server)
    return output / "config/saves/commands.msav"


def read_stock_orders(server, log):
    stock_command(server, log, "js (() => { " + RESULT + " })()", "STOCK_COMMAND_RESULT daggers=2 rally=688,192")


def verify_commands(log, proc, rpc, output):
    sp._wait_for_log(log, proc, r"UPSTREAM_COMMAND_SELECTION units=2 buildings=0", 100)
    (output / "selected-daggers.png").write_bytes(rpc.screenshot())
    sp._wait_for_log(log, proc, r"UPSTREAM_COMMAND_ORDER kind=move units=2 queued=true x=608\.0 y=232\.0", 45)
    sp._wait_for_log(log, proc, r"UPSTREAM_COMMAND_RALLY buildings=1 x=688\.0 y=192\.0", 55)


def replay(args, model, fixture, live, *, taps=TAPS, buttons=None,
           verify_input=verify_commands, read_stock=read_stock_orders, prepare_server=None,
           button_durations=None, circle=None, offline_ready=r"UPSTREAM_SP_READY .*x=648\.0 y=208\.0",
           movie_seconds=102):
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    output = args.output / model / ("live" if live else "offline")
    sd = (output / "sdmc").resolve()
    save = sd / "3ds/min3dstry/singleplayer-save.msav"
    save.parent.mkdir(parents=True, exist_ok=True)
    sp.stage_direct_startup(sd)
    shutil.copyfile(fixture, save)
    movie = output / "commands.ctm"
    if buttons is None:
        buttons = {0: sp.KEY_X, 94: sp.KEY_START} if live else {84: sp.KEY_SELECT, 94: sp.KEY_START}
    write_movie(movie, model, live=live, taps=taps, buttons=buttons, seconds=movie_seconds,
                button_durations=button_durations, circle=circle)
    if live:
        (save.parent / "server-endpoint.txt").write_text(f"127.0.0.1 {args.port}\n", encoding="ascii")
    isolated = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
    server = None
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        initial = (originals[config] or b"").decode("utf-8")
        config.write_text(sp.az.patch_qt_config_text(initial, isolated), encoding="utf-8")
        if live:
            server, server_log = stock_session(args, output / "live-server", fixture)
            if prepare_server is not None:
                prepare_server(server, server_log)
        proc, rpc = sp._launch_azahar(model, args.rom, azahar, config, log, movie)
        try:
            if live:
                sp._wait_for_log(log, proc, r"UPSTREAM_NETWORK_FRAME .*left=73 bottom=20 sprites=1", 130)
                wait_for(server_log, server, r"Min3DStry ARM11 has connected", 20)
            else:
                sp._wait_for_log(log, proc, offline_ready, 120)
            verify_input(log, proc, rpc, output)
            if live:
                time.sleep(3)
                read_stock(server, server_log)
                sp._wait_for_log(log, proc, r"UPSTREAM_LIVE_CONTROLS_LEFT\b", 60)
            else:
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_SAVED ", 65)
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 45)
        finally:
            rpc.close()
            sp._stop_proc(proc, azahar)
            if log.is_file():
                shutil.copyfile(log, output / "azahar.log")
    finally:
        if server is not None:
            stop(server)
        for path, data in originals.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)

    if not live:
        stock, stock_log = stock_session(args, output / "stock-result", save)
        try:
            read_stock(stock, stock_log)
        finally:
            stop(stock)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=("new3ds",), action="append")
    parser.add_argument("--port", type=int, default=16628)
    parser.add_argument("--live", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True)
    fixture = args.fixture or create_fixture(args, args.output / "stock-fixture")
    for model in args.model or ("new3ds",):
        replay(args, model, fixture, False, buttons={50: QUEUE_ORDER, 84: sp.KEY_SELECT, 94: sp.KEY_START})
        print(f"UPSTREAM_STOCK_COMMANDS model={model} live=False daggers=2 rally=ok", flush=True)
        if args.live:
            replay(args, model, fixture, True, buttons={0: sp.KEY_X, 50: QUEUE_ORDER, 94: sp.KEY_START})
            print(f"UPSTREAM_STOCK_COMMANDS model={model} live=True daggers=2 rally=ok", flush=True)


if __name__ == "__main__":
    main()
