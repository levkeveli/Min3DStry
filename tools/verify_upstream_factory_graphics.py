"""Compare stock factory states with the same ARM11 ROM on both Azahar profiles."""
import argparse
from pathlib import Path
import shutil
import time

import verify_upstream_singleplayer as sp
from verify_upstream_factory_plans import start_server, stock_command, write_movie
from verify_upstream_live_net import stop, wait_for


PHASES = ("terrain", "idle", "construction", "payload")
# Glacier camera starts at tile (81,26). The factory at (78,22) occupies a
# 60x60 pixel footprint centered on (150,190), clear of the player HUD.
FACTORY_PIXELS = tuple((x, y) for x in range(124, 177, 2) for y in range(164, 217, 2))


def create_stock_saves(args, output):
    output.mkdir(parents=True)
    log = output / "server.log"
    server = start_server(args, output, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        stock_command(server, log, f"config port {args.port}\nhost Glacier survival",
                      rf"Opened a server on port {args.port}\.")
        stock_command(
            server, log,
            'js (() => { const rules=Vars.state.rules; rules.waves=false; '
            'Vars.world.tile(76,22).setBlock(Blocks.powerSource,rules.defaultTeam,0); '
            'Vars.world.tile(80,22).setBlock(Blocks.copperWall,rules.defaultTeam,0); '
            'return "FACTORY_TERRAIN_READY"; })()',
            "FACTORY_TERRAIN_READY",
        )
        stock_command(server, log, "save terrain", r"Saved to .*terrain\.msav")
        stock_command(
            server, log,
            'js (() => { const tile=Vars.world.tile(78,22); '
            'tile.setBlock(Blocks.groundFactory,Vars.state.rules.defaultTeam,0); '
            'tile.build.currentPlan=-1; return "FACTORY_IDLE_READY"; })()',
            "FACTORY_IDLE_READY",
        )
        stock_command(server, log, "save idle", r"Saved to .*idle\.msav")
        stock_command(
            server, log,
            'js (() => { const f=Vars.world.tile(78,22).build; f.currentPlan=1; '
            'f.progress=300; f.items.add(Items.silicon,8); f.items.add(Items.coal,10); '
            'return "FACTORY_CONSTRUCTION_READY"; })()',
            "FACTORY_CONSTRUCTION_READY",
        )
        stock_command(server, log, "save construction", r"Saved to .*construction\.msav")
        stock_command(
            server, log,
            'js (() => { Vars.world.tile(78,22).build.progress=600; '
            'return "FACTORY_PRODUCTION_STARTED"; })()',
            "FACTORY_PRODUCTION_STARTED",
        )
        time.sleep(0.5)
        stock_command(
            server, log,
            'js (() => { const payload=Vars.world.tile(78,22).build.getPayload(); '
            'if (payload===null || payload.unit.type!==UnitTypes.crawler) throw "Missing Crawler"; '
            'return "FACTORY_PAYLOAD_READY"; })()',
            "FACTORY_PAYLOAD_READY",
        )
        stock_command(server, log, "save payload", r"Saved to .*payload\.msav")
    finally:
        stop(server)
    return output / "config/saves"


def start_live_fixture(args, phase, fixture, output):
    server_dir = output / "stock"
    saves = server_dir / "config/saves"
    saves.mkdir(parents=True)
    shutil.copyfile(fixture / f"{phase}.msav", saves / "factory.msav")
    log = output / "server.log"
    server = start_server(args, server_dir, log)
    try:
        wait_for(log, server, r"Server loaded\.", 30)
        stock_command(server, log, f"config port {args.port}\nload factory",
                      rf"Opened a server on port {args.port}\.")
        setup = {
            "terrain": "if (f && f.block === Blocks.groundFactory) throw 'Unexpected factory';",
            "idle": "if (f.currentPlan !== -1) throw 'Unexpected idle stock plan';",
            "construction": (
                "Vars.state.rules.unitBuildSpeedMultiplier=0; f.progress=300; "
                "if (f.currentPlan !== 1 || f.getPayload() !== null || f.efficiency <= 0) "
                "throw 'Expected a powered factory halfway through the Crawler plan';"
            ),
            "payload": (
                "const p=f.getPayload(); if (p === null || p.unit.type !== UnitTypes.crawler) "
                "throw 'Missing Crawler payload';"
            ),
        }[phase]
        stock_command(server, log, "js (()=>{ const f=Vars.world.tile(78,22).build; " + setup
                      + ' return "STOCK_FACTORY_PHASE_READY"; })()', "STOCK_FACTORY_PHASE_READY")
        return server, log
    except BaseException:
        stop(server)
        raise


def capture(args, model, phase, fixture, reference, threshold):
    azahar = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(azahar)
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    output = args.output / model / phase
    sd = (output / "sdmc").resolve()
    data = sd / "3ds/min3dstry"
    data.mkdir(parents=True)
    sp.stage_direct_startup(sd)
    movie = None
    if args.live:
        (data / "server-endpoint.txt").write_text(f"127.0.0.1 {args.port}\n", encoding="ascii")
        movie = output / "factory.ctm"
        write_movie(movie, model, live=True, taps={}, buttons={0: sp.KEY_X}, seconds=60)
    else:
        shutil.copyfile(fixture / f"{phase}.msav", data / "singleplayer-save.msav")
    overrides = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
    server = None
    try:
        if args.live:
            server, server_log = start_live_fixture(args, phase, fixture, output)
        config.parent.mkdir(parents=True, exist_ok=True)
        initial = (originals[config] or b"").decode("utf-8")
        config.write_text(sp.az.patch_qt_config_text(initial, overrides), encoding="utf-8")
        proc, rpc = sp._launch_azahar(model, args.rom, azahar, config, log, movie)
        try:
            frame = "UPSTREAM_NETWORK_FRAME" if args.live else "UPSTREAM_SP_FRAME"
            sp._wait_for_log(log, proc, rf"{frame} .*left=73 bottom=20 sprites=1\b", 120)
            if server is not None:
                wait_for(server_log, server, r"Min3DStry ARM11 has connected", 20)
            if phase == "construction":
                sp._wait_for_log(log, proc, r"UPSTREAM_UNIT_BUILD_SHADER source=unitbuild\.frag", 30)
            deadline = time.monotonic() + 18
            while time.monotonic() < deadline:
                try:
                    image = rpc.screenshot()
                    report = sp.az.inspect_png(image, FACTORY_PIXELS)
                    samples = report["samples"]
                    changed = 0 if reference is None else sum(
                        samples[point] != reference[point] for point in FACTORY_PIXELS
                    )
                    if report["top_distinct_colors"] >= 8 and changed >= threshold:
                        (output / "world.png").write_bytes(image)
                        print(f"UPSTREAM_FACTORY_GRAPHICS_SCREEN model={model} phase={phase} changed={changed}",
                              flush=True)
                        return samples
                except (RuntimeError, TimeoutError):
                    if proc.poll() is not None or "UPSTREAM_SP_ERROR" in log.read_text(encoding="utf-8"):
                        raise
                time.sleep(0.25)
            raise AssertionError(f"{model} {phase} did not draw the expected factory graphics")
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True, help="Unmodified Mindustry v160.8 server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--azahar")
    parser.add_argument("--live", action="store_true", help="Join the original Java server with each stock fixture")
    parser.add_argument("--port", type=int, default=16589)
    parser.add_argument("--model", choices=("new3ds", "old3ds"), action="append")
    parser.add_argument("--fixture", type=Path, help="An existing directory of stock fixture saves")
    parser.add_argument("--output", type=Path, required=True, help="A new output directory")
    args = parser.parse_args()
    args.output.mkdir(parents=True)
    fixture = args.fixture or create_stock_saves(args, args.output / "stock-server")
    for model in args.model or ("old3ds", "new3ds"):
        terrain = capture(args, model, "terrain", fixture, None, 0)
        idle = capture(args, model, "idle", fixture, terrain, 100)
        capture(args, model, "construction", fixture, idle, 12)
        capture(args, model, "payload", fixture, idle, 12)


if __name__ == "__main__":
    main()
