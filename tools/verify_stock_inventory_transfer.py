"""Replay inventory transfers on ARM11 against a stock Mindustry v160.8 server."""

import argparse
from pathlib import Path
import shutil
import time

import verify_upstream_singleplayer as sp
from verify_upstream_factory_plans import capture_factory_menu, start_server, stock_command, write_movie
from verify_upstream_live_net import stop, wait_for


SETUP = (
    'const rules = Vars.state.rules, team = rules.defaultTeam; '
    'rules.waves = false; '
    'const source = Vars.world.tile(76,24); source.setBlock(Blocks.container, team, 0); '
    'source.build.items.set(Items.copper, 200); source.build.items.set(Items.lead, 10); '
    'Vars.world.tile(86,26).setBlock(Blocks.duo, team, 0); '
    'return "INVENTORY_FIXTURE_READY unit=" + team.core().block.unitType.name;'
)
RESULT = (
    'const team = Vars.state.rules.defaultTeam; '
    'const capacity = team.core().block.unitType.itemCapacity; '
    'const source = Vars.world.build(76,24), turret = Vars.world.build(86,26); '
    'const multiplier = Blocks.duo.ammoTypes.get(Items.copper).ammoMultiplier; '
    'const fed = Math.floor(Blocks.duo.maxAmmo / multiplier); '
    'if (capacity <= fed || source.block !== Blocks.container || source.items.get(Items.lead) !== 10 || '
    'source.items.get(Items.copper) !== 200 - capacity || turret.block !== Blocks.duo || '
    'turret.totalAmmo !== fed * multiplier || turret.peekAmmo() !== Blocks.duo.ammoTypes.get(Items.copper)) '
    'throw "Wrong transfer: capacity=" + capacity + " copper=" + source.items.get(Items.copper) + '
    '" ammo=" + turret.totalAmmo; '
)
LIVE_RESULT = (
    'const player = Groups.player.find(p => String(p.name) === "Min3DStry ARM11"); '
    'if (player === null || player.unit().item() !== Items.copper || player.unit().stack.amount !== capacity - fed) '
    'throw "Wrong carried stack on the stock server"; '
)
EXPECTED = "STOCK_INVENTORY_TRANSFER_RESULT"
TAPS = {
    39: (15, 96), 45: (88, 168), 54: (160, 46),
    62: (160, 218), 68: (15, 128), 74: (248, 136),
}


def stock_session(args, output, fixture=None):
    output.mkdir(parents=True)
    saves = output / "config/saves"
    if fixture is not None:
        saves.mkdir(parents=True)
        shutil.copyfile(fixture, saves / "inventory.msav")
    log = output / "server.log"
    server = start_server(args, output, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        action = "load inventory" if fixture is not None else "host Glacier survival"
        stock_command(server, log, f"config port {args.port}\n{action}", rf"Opened a server on port {args.port}\.")
    except Exception:
        stop(server)
        raise
    return server, log


def create_fixture(args, output):
    server, log = stock_session(args, output)
    try:
        stock_command(server, log, "js (() => { " + SETUP + " })()", "INVENTORY_FIXTURE_READY")
        stock_command(server, log, "save inventory", r"Saved to .*inventory\.msav")
    finally:
        stop(server)
    return output / "config/saves/inventory.msav"


def assert_stock_result(server, log, live=False):
    script = RESULT + (LIVE_RESULT if live else "") + f'return "{EXPECTED}";'
    stock_command(server, log, "js (() => { " + script + " })()", EXPECTED)


def replay(args, model, fixture, live):
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    output = args.output / model / ("live" if live else "offline")
    sd = (output / "sdmc").resolve()
    save = sd / "3ds/min3dstry/singleplayer-save.msav"
    save.parent.mkdir(parents=True)
    sp.stage_direct_startup(sd)
    shutil.copyfile(fixture, save)
    movie = output / "inventory.ctm"
    buttons = {0: sp.KEY_X, 93: sp.KEY_START} if live else {84: sp.KEY_SELECT, 93: sp.KEY_START}
    write_movie(movie, model, live=live, taps=TAPS, buttons=buttons, seconds=100)
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
        proc, rpc = sp._launch_azahar(model, args.rom, azahar, config, log, movie)
        try:
            if live:
                sp._wait_for_log(log, proc, r"UPSTREAM_NETWORK_FRAME .*left=73 bottom=20 sprites=1", 130)
                wait_for(server_log, server, r"Min3DStry ARM11 has connected", 20)
            else:
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_READY .*x=648\.0 y=208\.0", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_INVENTORY_OPEN x=76 y=24\b", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_MENU_FONT source=v160.8 catalog=item\b", 15)
            (output / "inventory-menu.png").write_bytes(capture_factory_menu(rpc))
            sp._wait_for_log(log, proc, r"UPSTREAM_INVENTORY_WITHDRAW x=76 y=24 item=copper requested=[1-9][0-9]*\b", 55)
            sp._wait_for_log(log, proc, r"UPSTREAM_INVENTORY_DEPOSIT x=86 y=26 item=copper\b", 80)
            if live:
                time.sleep(1)
                assert_stock_result(server, server_log, live=True)
                sp._wait_for_log(log, proc, r"UPSTREAM_LIVE_CONTROLS_LEFT\b", 60)
            else:
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_SAVED ", 70)
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 50)
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
            assert_stock_result(stock, stock_log)
        finally:
            stop(stock)
    print(f"UPSTREAM_STOCK_INVENTORY model={model} live={live} withdraw=ok duo=ok", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=("new3ds",), action="append")
    parser.add_argument("--port", type=int, default=16608)
    parser.add_argument("--live", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True)
    fixture = args.fixture or create_fixture(args, args.output / "stock-fixture")
    for model in args.model or ("new3ds",):
        replay(args, model, fixture, False)
        if args.live:
            replay(args, model, fixture, True)


if __name__ == "__main__":
    main()
