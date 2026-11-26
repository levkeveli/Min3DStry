#!/usr/bin/env python3
"""Replay live stock silicon crafting on a New 3DS connected to Java v160.8."""

import argparse
import hashlib
from itertools import count
from pathlib import Path
import re
import time

from verify_offline_material_buildings import changed_pixels
from verify_upstream_factory_plans import start_server, stock_command
from verify_upstream_live_net import SERVER_SHA256, stop, wait_for, write_controls_replay
import verify_upstream_singleplayer as sp


MODEL = "new3ds"
SMELTER = ("siliconSmelter", "silicon-smelter", 75, 23, 2)


def place_stock_crafter(server, log, port):
    wait_for(log, server, r"Server loaded\.", 30)
    stock_command(server, log, f"config port {port}\nhost Glacier survival", rf"Opened a server on port {port}\.")
    stock_command(
        server, log,
        'js (() => { Vars.state.rules.waves=false; Vars.state.rules.lighting=false; '
        'const team=Vars.state.rules.defaultTeam; '
        'Vars.world.tile(75,23).setBlock(Blocks.siliconSmelter,team,0); '
        'Vars.world.tile(73,23).setBlock(Blocks.largeSolarPanel,team,0); '
        'return "STOCK_LIVE_CRAFTER_PLACED"; })()',
        "STOCK_LIVE_CRAFTER_PLACED",
    )


def wait_for_crafter(server, log, ready, sequence, timeout=15):
    deadline = time.monotonic() + timeout
    state = None
    while time.monotonic() < deadline:
        marker = f"STOCK_CRAFTER_STATE_{next(sequence)}"
        pattern = rf"{marker} warmup=(\d+) output=(\d+) power=(\d+)"
        server.stdin.write(
            'js (() => { const build=Vars.world.tile(75,23).build; '
            f'return "{marker} warmup=" + Math.round(build.warmup*1000) '
            '+ " output=" + build.items.get(Items.silicon) '
            '+ " power=" + Math.round(build.power.status*1000); })()\n'
        )
        server.stdin.flush()
        response = wait_for(log, server, pattern, 5)
        response = re.sub(r"\x1b\[[0-9;]*m", "", response)
        state = tuple(map(int, re.search(pattern, response).groups()))
        if ready(*state):
            return state
        time.sleep(0.15)
    raise AssertionError(f"Stock silicon smelter did not reach its expected state: {state}")


def feed(server, log):
    stock_command(
        server, log,
        'js (() => { const items=Vars.world.tile(75,23).build.items; '
        'items.add(Items.sand, 20); items.add(Items.coal, 20); return "STOCK_LIVE_CRAFTER_FED"; })()',
        "STOCK_LIVE_CRAFTER_FED",
    )


def starve(server, log):
    stock_command(
        server, log,
        'js (() => { const items=Vars.world.tile(75,23).build.items; '
        'items.remove(Items.sand, items.get(Items.sand)); items.remove(Items.coal, items.get(Items.coal)); '
        'return "STOCK_LIVE_CRAFTER_STARVED"; })()',
        "STOCK_LIVE_CRAFTER_STARVED",
    )


def publish_stock_snapshot(server, log, phase):
    # Generic crafters are not in the periodic synced index. Use the stock
    # server console to send the authoritative build state over blockSnapshot.
    marker = f"STOCK_LIVE_CRAFTER_SNAPSHOT_{phase}"
    stock_command(
        server, log,
        'js (() => { const build=Vars.world.tile(75,23).build; '
        'Packages.mindustry.core.NetServer.syncBuilding(build); '
        f'return "{marker}"; }})()',
        marker,
    )


def capture(rpc, path, expected):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        frame = rpc.screenshot(res_scale=1)
        path.write_bytes(frame)
        if expected(frame):
            return frame
        time.sleep(0.15)
    raise AssertionError(f"Expected the stock silicon-smelter frame in {path}")


def verify(args):
    with args.server.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != SERVER_SHA256:
            raise ValueError("Expected the unmodified v160.8 Java server")
    args.output.mkdir(parents=True, exist_ok=False)
    server_dir = args.output / "server"
    server_dir.mkdir()
    server_log = args.output / "server.log"
    virtual_sd = (args.output / "virtual-sd").resolve()
    endpoint = virtual_sd / "3ds/min3dstry/server-endpoint.txt"
    endpoint.parent.mkdir(parents=True)
    endpoint.write_text(f"127.0.0.1 {args.port}\n", encoding="ascii")
    movie = args.output / "new3ds-live-crafting.ctm"
    write_controls_replay(movie, model=MODEL, unified_boot=True, stationary=True)
    emulator = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(emulator)
    originals = {path: path.read_bytes() if path.is_file() else None for path in (config, log)}
    server = start_server(args, server_dir, server_log)
    try:
        place_stock_crafter(server, server_log, args.port)
        sequence = count()
        wait_for_crafter(server, server_log, lambda warmup, output, power: warmup == output == 0 and power > 0, sequence)
        isolated = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": virtual_sd.as_posix() + "/"}}
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text(sp.az.patch_qt_config_text((originals[config] or b"").decode(), isolated), encoding="utf-8")
        proc, rpc = sp._launch_azahar(MODEL, args.rom, emulator, config, log, movie)
        try:
            wait_for(server_log, server, r"Min3DStry ARM11 has connected", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_NETWORK_FRAME .*left=73 bottom=20 sprites=1", 120)
            idle = capture(rpc, args.output / "new3ds-idle.png",
                           lambda frame: sp.az.inspect_png(frame)["top_distinct_colors"] >= 8)

            def activity(frame):
                return changed_pixels(idle, frame, (SMELTER,))[SMELTER[1]]

            feed(server, server_log)
            warmup, produced, _ = wait_for_crafter(
                server, server_log, lambda warmup, output, power: warmup >= 300 and output > 0 and power > 0, sequence
            )
            publish_stock_snapshot(server, server_log, "ACTIVE")
            active = capture(rpc, args.output / "new3ds-crafting.png", lambda frame: activity(frame) >= 8)
            starve(server, server_log)
            wait_for_crafter(server, server_log, lambda warmup, output, power: warmup == 0 and output >= produced, sequence)
            publish_stock_snapshot(server, server_log, "STARVED")
            capture(rpc, args.output / "new3ds-starved.png", lambda frame: activity(frame) <= 3)
            print(f"UPSTREAM_LIVE_CRAFTING model={MODEL} silicon={produced} warmup={warmup} changed={activity(active)}/25",
                  flush=True)
        finally:
            rpc.close()
            sp._stop_proc(proc, emulator)
            if log.is_file():
                (args.output / "new3ds-live-crafting.log").write_bytes(log.read_bytes())
    finally:
        stop(server)
        for path, previous in originals.items():
            if previous is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(previous)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--azahar")
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", type=int, default=16679)
    verify(parser.parse_args())


if __name__ == "__main__":
    main()
