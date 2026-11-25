"""Replay original Erekir item selectors on ARM11 and the unmodified Java server."""

import argparse
from pathlib import Path
import shutil
import time

import verify_upstream_singleplayer as sp
from verify_upstream_factory_plans import capture_factory_menu, start_server, stock_command, write_movie
from verify_upstream_live_net import stop, wait_for


SETUP = (
    'const rules=Vars.state.rules, team=rules.defaultTeam; '
    'rules.waves=false; rules.planet=Planets.erekir; '
    'const router=Vars.world.tile(78,22); router.setBlock(Blocks.ductRouter,team,0); '
    'router.build.configured(null,Items.beryllium); '
    'Vars.world.tile(86,26).setBlock(Blocks.ductUnloader,team,0); '
    'return "ITEM_SELECTOR_FIXTURE_READY";'
)
RESULT = (
    'const router=Vars.world.tile(78,22).build, unloader=Vars.world.tile(86,26).build; '
    'if (Vars.state.getPlanet() !== Planets.erekir || router.block !== Blocks.ductRouter || router.config() !== null) '
    'throw "Expected the original router filter to be cleared"; '
    'if (unloader.block !== Blocks.ductUnloader || unloader.config() !== Items.tungsten) '
    'throw "Expected the original unloader to select tungsten"; '
    'return "ITEM_SELECTOR_RESULT router=none unloader=tungsten";'
)
EXPECTED = "ITEM_SELECTOR_RESULT router=none unloader=tungsten"

# Open the saved selection and clear its highlighted first row on page 2.
# Open the other block, go to page 2, and choose the second row.
TAPS = {
    40: (305, 64), 46: (120, 200), 55: (160, 46),
    62: (305, 64), 68: (248, 136), 74: (276, 218), 81: (160, 74),
}


def stock_fixture(args, output):
    output.mkdir()
    log = output / "server.log"
    server = start_server(args, output, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        stock_command(server, log, f"config port {args.port}\nhost Glacier survival",
                      rf"Opened a server on port {args.port}\.")
        stock_command(server, log, "js (() => { " + SETUP + " })()", "ITEM_SELECTOR_FIXTURE_READY")
        stock_command(server, log, "save selectors", r"Saved to .*selectors\.msav")
        stock_command(server, log, "stop", "Stopped server")
        stock_command(server, log, "load selectors", r"Save loaded\.")
        stock_command(
            server, log,
            'js (() => { if (Vars.state.getPlanet() !== Planets.erekir || '
            'Vars.world.tile(78,22).build.config() !== Items.beryllium || '
            'Vars.world.tile(86,26).build.config() !== null) throw "Wrong saved selector state"; '
            'return "STOCK_ITEM_SELECTOR_FIXTURE_REOPENED"; })()',
            "STOCK_ITEM_SELECTOR_FIXTURE_REOPENED",
        )
    finally:
        stop(server)
    return output / "config/saves/selectors.msav"


def start_stock_save(args, output, fixture):
    save = output / "config/saves/selectors.msav"
    save.parent.mkdir(parents=True)
    shutil.copyfile(fixture, save)
    log = output / "server.log"
    server = start_server(args, output, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        stock_command(server, log, f"config port {args.port}\nload selectors",
                      rf"Opened a server on port {args.port}\.")
    except Exception:
        stop(server)
        raise
    return server, log


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
    movie = output / "selectors.ctm"
    buttons = {0: sp.KEY_X, 96: sp.KEY_START} if live else {89: sp.KEY_SELECT, 96: sp.KEY_START}
    write_movie(movie, model, live=live, taps=TAPS, buttons=buttons, seconds=103)
    if live:
        (save.parent / "server-endpoint.txt").write_text(f"127.0.0.1 {args.port}\n", encoding="ascii")
    isolated = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
    server = None
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        initial = (originals[config] or b"").decode("utf-8")
        config.write_text(sp.az.patch_qt_config_text(initial, isolated), encoding="utf-8")
        if live:
            server, server_log = start_stock_save(args, output / "live-server", fixture)
        proc, rpc = sp._launch_azahar(model, args.rom, azahar, config, log, movie)
        try:
            if live:
                sp._wait_for_log(log, proc, r"UPSTREAM_NETWORK_FRAME .*left=73 bottom=20 sprites=1", 130)
                wait_for(server_log, server, r"Min3DStry ARM11 has connected", 20)
            else:
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_READY .*x=648\.0 y=208\.0", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_CONFIG_OPEN x=78 y=22\b", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_MENU_FONT source=v160.8 catalog=item\b", 15)
            (output / "saved-router-selection.png").write_bytes(capture_factory_menu(rpc))
            sp._wait_for_log(log, proc, r"UPSTREAM_CONFIG_LOCAL x=78 y=22 item=none\b", 55)
            sp._wait_for_log(log, proc, r"UPSTREAM_CONFIG_LOCAL x=86 y=26 item=tungsten\b", 90)
            if live:
                time.sleep(1)
                stock_command(server, server_log, "js (() => { " + RESULT + " })()", EXPECTED)
                sp._wait_for_log(log, proc, r"UPSTREAM_LIVE_CONTROLS_LEFT\b", 60)
            else:
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_SAVED ", 80)
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
        stock, stock_log = start_stock_save(args, output / "stock-result", save)
        try:
            stock_command(stock, stock_log, "js (() => { " + RESULT + " })()", EXPECTED)
        finally:
            stop(stock)
    print(f"UPSTREAM_ITEM_SELECTORS model={model} live={live} router=none unloader=tungsten", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", choices=("new3ds", "old3ds"), action="append")
    parser.add_argument("--port", type=int, default=16598)
    parser.add_argument("--live", action="store_true", help="Also verify configuration against the stock server")
    args = parser.parse_args()
    args.output.mkdir(parents=True)
    fixture = args.fixture or stock_fixture(args, args.output / "stock-fixture")
    for model in args.model or ("old3ds", "new3ds"):
        replay(args, model, fixture, False)
        if args.live:
            replay(args, model, fixture, True)


if __name__ == "__main__":
    main()
