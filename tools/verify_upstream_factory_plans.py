"""Verify native touch factory production and stock save interoperability."""
import argparse
from pathlib import Path
import shutil
import struct
import subprocess
import time

import verify_upstream_singleplayer as sp
from verify_upstream_live_net import stop, wait_for

FACTORY = (
    'const rules = Vars.state.rules, team = rules.defaultTeam; '
    'rules.waves = false; rules.unitBuildSpeedMultiplier = 60; rules.bannedUnits.add(UnitTypes.dagger); '
    'Vars.world.tile(78,22).setBlock(Blocks.groundFactory, team, 0); '
    'Vars.world.tile(80,22).setBlock(Blocks.copperWall, team, 0); '
    'const f = Vars.world.tile(78,22).build; '
    'f.currentPlan = -1; f.items.add(Items.silicon,8); f.items.add(Items.coal,10); '
    'for (var i=0; i<2; i++) { const t = Vars.world.tile(76,21+2*i); '
    't.setBlock(Blocks.combustionGenerator, team, 0); t.build.items.add(Items.coal,20); } '
    'return "FACTORY_FIXTURE_CREATED";'
)
RESULT = (
    'const f = Vars.world.tile(78,22).build; '
    'if (f.block !== Blocks.groundFactory || f.currentPlan !== 1 || f.unit() !== UnitTypes.crawler) '
    'throw "Wrong stock plan"; '
    'const p = f.getPayload(); '
    'if (p === null || p.unit.type !== UnitTypes.crawler) '
    'throw "Expected Crawler payload at progress=" + f.progress + " power=" + f.power.status; '
    'if (f.items.get(Items.silicon) !== 0 || f.items.get(Items.coal) !== 0) throw "Recipe inputs remain"; '
    'return "UPSTREAM_STOCK_FACTORY plan=1 payload=crawler consumed=ok";'
)
EXPECTED = "UPSTREAM_STOCK_FACTORY plan=1 payload=crawler consumed=ok"


def start_server(args, output, log):
    with log.open("wb") as console:
        return subprocess.Popen(
            [args.java, "-jar", str(args.server.resolve())], cwd=output,
            stdin=subprocess.PIPE, stdout=console, stderr=subprocess.STDOUT, text=True,
        )


def stock_command(server, log, command, expected):
    server.stdin.write(command + "\n")
    server.stdin.flush()
    wait_for(log, server, expected, 30)


def stock_server(args, output, result=None):
    output.mkdir(parents=True)
    log = output / "server.log"
    if result is not None:
        saves = output / "config/saves"
        saves.mkdir(parents=True)
        shutil.copyfile(result, saves / "factory-result.msav")
    server = start_server(args, output, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        if result is None:
            stock_command(server, log, f"config port {args.port}\nhost Glacier survival",
                          rf"Opened a server on port {args.port}\.")
            stock_command(server, log, "js (() => { " + FACTORY + " })()", "FACTORY_FIXTURE_CREATED")
            stock_command(server, log, "save factory-fixture", r"Saved to .*factory-fixture\.msav")
            return output / "config/saves/factory-fixture.msav"
        stock_command(server, log, f"config port {args.port}\nload factory-result", r"Save loaded\.")
        stock_command(server, log, "js (() => { " + RESULT + " })()", EXPECTED)
    finally:
        stop(server)


def write_movie(path, model, live=False, *, taps=None, buttons=None, seconds=90, button_durations=None, circle=None):
    samples = sp.CTM_SAMPLES_PER_SECOND
    frames = seconds * samples
    if taps is None:
        taps = ({42: (305, 64), 49: (120, 200), 60: (160, 46)} if live
                else {40: (305, 64), 45: (120, 200), 55: (160, 46)})
    if buttons is None:
        buttons = {0: sp.KEY_X, 80: sp.KEY_START} if live else {75: sp.KEY_SELECT, 82: sp.KEY_START}
    button_durations = button_durations or {}
    circle = circle or {}
    windows = [(round(start * samples),
                round((start + button_durations.get(start, 6 if live and start == 0 else 2)) * samples), mask)
               for start, mask in buttons.items()]
    with path.open("wb") as movie:
        movie.write(struct.pack("<4sQ20sQQ32sIQq156x", b"CTM\x1b", 0, sp.CTM_REVISION, 1780000000,
                                0x4D334453, b"Min3DStry touch replay", 0, frames, 0))
        idle_touch = struct.pack("<B6x", 1)
        for frame in range(frames):
            sec = frame // samples
            point = next((touch for start, touch in taps.items() if start <= sec < start + 2), None)
            mask = next((mask for start, end, mask in windows if start <= frame < end), 0)
            dx, dy = circle.get(sec, (0, 0))
            pad = struct.pack("<BHhh", 0, mask, dx, dy)
            touch = struct.pack("<BHHBx", 1, *point, 1) if point else idle_touch
            if point:
                movie.write(touch * 2)
            elif mask or dx or dy:
                movie.write(pad * 2)
            else:
                movie.write(pad + idle_touch)
            if model == "new3ds" and frame % 7 in (0, 2, 4):
                movie.write(touch if point else pad)


def capture_factory_menu(rpc):
    # Lower-screen padding is 40 pixels. The first row icon is centered at (27, 55).
    points = tuple((x, y) for x in range(60, 75) for y in range(288, 304))
    deadline = time.monotonic() + 6
    while time.monotonic() < deadline:
        try:
            screenshot = rpc.screenshot()
            colors = sp.az.inspect_png(screenshot, points)["samples"]
            if len(set(colors.values())) >= 4:
                return screenshot
        except (RuntimeError, TimeoutError):
            pass  # The stock-font log can precede the completed GPU frame.
        time.sleep(0.2)
    raise AssertionError("The factory menu did not render the first unit icon")


def replay(args, model, fixture):
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    output = args.output / model
    sd = (output / "sdmc").resolve()
    save = sd / "3ds/min3dstry/singleplayer-save.msav"
    save.parent.mkdir(parents=True)
    shutil.copyfile(fixture, save)
    movie = output / "factory.ctm"
    write_movie(movie, model, live=args.live)
    if args.live:
        (save.parent / "server-endpoint.txt").write_text(f"127.0.0.1 {args.port}\n", encoding="ascii")
    isolated = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
    server = None
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        initial = (originals[config] or b"").decode("utf-8")
        config.write_text(sp.az.patch_qt_config_text(initial, isolated), encoding="utf-8")
        if args.live:
            server_log = output / "server.log"
            work = output / "live-server"
            paths = work / "config/saves"
            paths.mkdir(parents=True)
            shutil.copyfile(fixture, paths / "factory-fixture.msav")
            server = start_server(args, work, server_log)
            wait_for(server_log, server, r"Server loaded\.", 30)
            stock_command(server, server_log, f"config port {args.port}\nload factory-fixture",
                          rf"Opened a server on port {args.port}\.")
            # Keep the factory powered while the client joins and reaches its touch inputs.
            stock_command(server, server_log,
                          'js (() => { Vars.world.tile(76,22).setBlock(Blocks.powerSource, '
                          'Vars.state.rules.defaultTeam, 0); return "FACTORY_POWER_READY"; })()',
                          "FACTORY_POWER_READY")
        proc, rpc = sp._launch_azahar(model, args.rom, azahar, config, log, movie)
        try:
            if args.live:
                sp._wait_for_log(log, proc, r"UPSTREAM_NETWORK_FRAME .*left=73 bottom=20 sprites=1", 130)
                wait_for(server_log, server, r"Min3DStry ARM11 has connected", 20)
            else:
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_READY .*x=648\.0 y=208\.0", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_CONFIG_OPEN x=78 y=22\b", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_MENU_FONT source=v160.8 catalog=unit\b", 15)
            (output / "factory-menu.png").write_bytes(capture_factory_menu(rpc))
            sp._wait_for_log(log, proc, r"UPSTREAM_FACTORY_PLAN x=78 y=22 plan=1 unit=crawler\b", 55)
            if args.live:
                # The server's original simulation runs independent of ARM11 frame rate.
                time.sleep(4)
                stock_command(server, server_log, "js (() => { " + RESULT + " })()", EXPECTED)
                sp._wait_for_log(log, proc, r"UPSTREAM_LIVE_CONTROLS_LEFT\b", 80)
            else:
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_SAVED ", 120)
                sp._wait_for_log(log, proc, r"UPSTREAM_SP_EXIT\b", 35)
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
    if args.live:
        print(f"[PASS] {model}: unmodified Java server accepted the 3DS plan and produced Crawler", flush=True)
    else:
        stock_server(args, output / "stock-result", save)
        print(f"[PASS] {model}: selected and produced stock Crawler, reopened by Java v160.8", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--fixture", type=Path, help="An existing stock factory fixture")
    parser.add_argument("--output", type=Path, required=True, help="A new output directory")
    parser.add_argument("--model", choices=("new3ds", "old3ds"), action="append")
    parser.add_argument("--port", type=int, default=16588)
    parser.add_argument("--live", action="store_true", help="Verify plan configuration against a stock server")
    args = parser.parse_args()
    args.output.mkdir(parents=True)
    fixture = args.fixture or stock_server(args, args.output / "stock-fixture")
    for model in args.model or ("new3ds",):
        replay(args, model, fixture)


if __name__ == "__main__":
    main()
