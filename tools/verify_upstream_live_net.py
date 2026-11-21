#!/usr/bin/env python3
"""Join an official v160.8 loopback server from both Azahar console profiles."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import time

import verify_azahar as az


SERVER_SHA256 = "faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c"
ATLAS_SHA256 = "fcfb2171eed35b60e7a0d696b63943883a191e7f547ecfef372bd42d72675334"
PLAYER = "Min3DStry ARM11"
NUMBER = r"-?\d+(?:\.\d+)?"


def write_controls_replay(path, first_move=22, building=False, touch_build=False, model="old3ds"):
    """Write the Azahar 2126.2 CTM input for this probe's pad/touch polling."""
    # The pinned Azahar movie header and HID sample definitions use packed little-endian fields.
    revision = bytes.fromhex("219d743a1e698101fe1ab8bef43e6563126c3c27")
    samples_per_second = 234
    frames = (first_move + 23) * samples_per_second
    header = struct.pack("<4sQ20sQQ32sIQq156x", b"CTM\x1b", 0, revision, 1780000000,
                         0x4D334453, b"Min3DStry input verification", 0, frames, 0)
    touch_idle = struct.pack("<B6x", 1)
    taps = {35: (200, 24), 39: (232, 136), 46: (120, 24),
            50: (248, 136), 57: (280, 24), 61: (248, 136)} if touch_build else {}
    with path.open("wb") as movie:
        movie.write(header)
        for frame in range(frames):
            seconds = frame // samples_per_second
            buttons = (1 << 4) if seconds == first_move else (1 << 3) if seconds == first_move + 16 else 0
            if building and seconds in (35, 37, 39):
                buttons = 1  # A, with releases so a late HID poll can observe another press.
            circle_y = 106 if seconds == first_move + 6 else 0
            point = taps.get(seconds)
            pad = struct.pack("<BHhh", 0, buttons, 0, circle_y)
            if touch_build and point:
                # Extra HID polls on New 3DS can fall between pad/touch pairs.
                # Keep the touch held for the whole burst regardless of the poll phase.
                touch = struct.pack("<BHHBx", 1, *point, 1)
                movie.write(touch * 2)
            elif touch_build and (buttons or circle_y):
                movie.write(pad * 2)
            else:
                movie.write(pad + touch_idle)
            if touch_build and model == "new3ds" and frame % 7 in (0, 2, 4):
                movie.write(touch if point else pad)


def stop(proc):
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)


def wait_for(path, proc, pattern, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = path.read_text(encoding="utf-8", errors="replace")
        if re.search(pattern, text):
            return text
        if proc.poll() is not None or re.search(r"Core::LogException|UPSTREAM_LIVE_NET_ERROR", text):
            raise RuntimeError(f"Process stopped before {pattern}:\n{text[-2500:]}")
        time.sleep(0.05)
    raise RuntimeError(f"Timed out waiting for {pattern}:\n{text[-2500:]}")


def server_position(server_log, server, marker):
    # Read the stock server's unit and connection without mutating gameplay state.
    server.stdin.write(
        f'js (() => {{ const p = Groups.player.find(boolf(v => String(v.name) === "{PLAYER}")); '
        'const u = p.unit(); '
        f'return "{marker} unit=" + u.id + " snapshot=" + p.con.lastReceivedClientSnapshot '
        '+ " x=" + u.x + " y=" + u.y; })()\n'
    )
    server.stdin.flush()
    state = re.compile(rf"{marker} unit=(\d+) snapshot=(\d+) x=({NUMBER}) y=({NUMBER})")
    return state.search(wait_for(server_log, server, state, 5))


def verify_movement(log, emulator, server_log, server, join, timeout):
    movement = re.compile(rf"UPSTREAM_LIVE_NET_MOVEMENT join={join} unit=(\d+) snapshot=(\d+) "
                          rf"fromX=({NUMBER}) x=({NUMBER}) y=({NUMBER})")
    trace = movement.search(wait_for(log, emulator, movement, timeout))
    unit, sequence, start_x, x, y = trace.groups()
    if float(x) - float(start_x) < 32:
        raise RuntimeError("The upstream client unit did not move at least four tiles")
    observed = server_position(server_log, server, f"UPSTREAM_SERVER_MOVEMENT join={join}")
    server_unit, server_sequence, server_x, server_y = observed.groups()
    if (unit != server_unit or int(server_sequence) < int(sequence) - 3
            or abs(float(x) - float(server_x)) > 2 or abs(float(y) - float(server_y)) > 2):
        raise RuntimeError(f"Unexpected server movement: {observed.group(0)} vs {trace.group(0)}")
    print(observed.group(0), flush=True)


def inspect_building(server_log, server, phase, tile_x, tile_y, builder=PLAYER):
    marker = f"UPSTREAM_SERVER_BUILDING phase={phase}"
    server.stdin.write(
        f'js (() => {{ const p = Groups.player.find(boolf(v => String(v.name) === "{PLAYER}")); '
        f'const builder = Groups.player.find(boolf(v => String(v.name) === {json.dumps(builder)})); '
        f'const tile = Vars.world.tile({tile_x}, {tile_y}); '
        'const owned = builder != null && tile.build != null && '
        'String(tile.build.lastAccessed) === String(builder.coloredName()); '
        f'return "{marker} block=" + tile.block().name + " team=" + tile.team().id '
        '+ " rotation=" + (tile.build == null ? -1 : tile.build.rotation) '
        '+ " copper=" + p.team().core().items.get(Items.copper) + " builder=" + owned; })()\n'
    )
    server.stdin.flush()
    check = re.compile(rf"{marker} block=(\S+) team=(\d+) rotation=(-?\d+) copper=(\d+) builder=(true|false)")
    return check.search(wait_for(server_log, server, check, 5)).groups()


def verify_conveyor(log, emulator, server_log, server, timeout, capture, position, desktop_name):
    _, _, x, y = position
    tx, ty = math.floor(float(x) / 8 + 0.5) + 4, math.floor(float(y) / 8 + 0.5)

    original = inspect_building(server_log, server, "before", tx, ty)
    print(f"Press A to queue a conveyor at ({tx}, {ty}).", flush=True)
    wait_for(log, emulator, rf"UPSTREAM_LIVE_BUILD_PLAN x={tx} y={ty} rotation=0\b", timeout)
    completed = re.compile(rf"UPSTREAM_LIVE_BUILD block=conveyor x={tx} y={ty} rotation=0 team=(\d+)")
    accepted = completed.search(wait_for(log, emulator, completed, timeout))
    finished = inspect_building(server_log, server, "after", tx, ty)
    if (original[0] != "air" or finished[:3] != ("conveyor", accepted[1], "0")
            or int(original[3]) - int(finished[3]) != 1 or finished[4] != "true"):
        raise RuntimeError(f"Unexpected stock conveyor or copper cost: {original} -> {finished}")
    wait_for(log, emulator, rf"UPSTREAM_NETWORK_CONVEYOR x={tx} y={ty} rotation=0\b", timeout)
    capture("conveyor", position)
    print(f"Stock server placed the conveyor on team {finished[1]} for one copper.", flush=True)
    if desktop_name:
        print(f"Confirm the conveyor at ({tx}, {ty}) in the stock PC client. Then place another "
              f"conveyor within the 3DS viewport, for example at ({tx}, {ty + 2}).", flush=True)
        pc_build = re.compile(rf"UPSTREAM_LIVE_BUILD block=conveyor (?!x={tx} y={ty} )x=(\d+) y=(\d+) "
                              rf"rotation=([0-3]) team={finished[1]}\b")
        desktop_x, desktop_y, rotation = pc_build.search(wait_for(log, emulator, pc_build, timeout)).groups()
        second_finished = inspect_building(server_log, server, "desktop-after",
                                           int(desktop_x), int(desktop_y), desktop_name)
        if (second_finished[:3] != ("conveyor", finished[1], rotation)
                or int(finished[3]) - int(second_finished[3]) != 1 or second_finished[4] != "true"):
            raise RuntimeError(f"Unexpected PC conveyor builder or copper cost: {finished} -> {second_finished}")
        wait_for(log, emulator, rf"UPSTREAM_NETWORK_CONVEYOR x={desktop_x} y={desktop_y} rotation={rotation}\b", timeout)
        capture("desktop-conveyor", position)
        print(f"The ARM11 client applied and drew the PC conveyor at ({desktop_x}, {desktop_y}).", flush=True)


def verify_touch_build(log, emulator, server_log, server, timeout, capture, position, ground_pixels):
    _, _, x, y = position
    tx, ty = math.floor(float(x) / 8 + 0.5) + 4, math.floor(float(y) / 8 + 0.5)
    print("Tap Rotate, the grid, Copper Wall, the adjacent grid cell, then Remove and the wall.", flush=True)
    original = inspect_building(server_log, server, "touch-before", tx, ty)
    if original[0] != "air":
        raise RuntimeError(f"Expected an empty Glacier conveyor cell: {original}")
    copper = int(original[3])
    for block, tile_x, rotation, cost in (("conveyor", tx, 1, 1), ("copper-wall", tx + 1, 0, 6)):
        wait_for(log, emulator, rf"UPSTREAM_TOUCH_PLAN block={block} x={tile_x} y={ty} rotation={rotation}\b", timeout)
        wait_for(log, emulator,
                 rf"UPSTREAM_LIVE_BUILD block={block} x={tile_x} y={ty} rotation={rotation} team=1\b", timeout)
        copper -= cost
        result = inspect_building(server_log, server, f"touch-{block}", tile_x, ty)
        if result != (block, "1", str(rotation), str(copper), "true"):
            raise RuntimeError(f"Unexpected stock {block} placement: {result}")
        build_pixels = capture(f"touch-{block}", position)
    wait_for(log, emulator, rf"UPSTREAM_TOUCH_PLAN block=break x={tx + 1} y={ty}\b", timeout)
    wait_for(log, emulator, rf"UPSTREAM_LIVE_REMOVED x={tx + 1} y={ty}\b", timeout)
    removed = inspect_building(server_log, server, "touch-removed", tx + 1, ty)
    retained = inspect_building(server_log, server, "touch-retained", tx, ty)
    if removed != ("air", "0", "-1", str(copper + 3), "false") or retained[:3] != ("conveyor", "1", "1"):
        raise RuntimeError(f"Unexpected stock removal, copper refund or adjacent conveyor: {removed}, {retained}")
    removed_pixels = capture("touch-removed", position)
    if build_pixels == ground_pixels or removed_pixels != ground_pixels:
        raise RuntimeError(f"Wall sprite did not appear and clear: {ground_pixels} -> "
                           f"{build_pixels} -> {removed_pixels}")
    print(f"Stock server verified both touch placements and the wall refund; copper={removed[3]}.", flush=True)


def verify_controls(log, emulator, server_log, server, timeout, capture=None,
                    building=False, desktop_name=None, touch_build=False):
    wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_READY unit=\d+", timeout)
    baseline = server_position(server_log, server, "UPSTREAM_SERVER_CONTROLS_READY").groups()
    ground_pixels = capture("spawn", baseline) if capture else None
    if building:
        verify_conveyor(log, emulator, server_log, server, timeout, capture, baseline, desktop_name)
    if touch_build:
        verify_touch_build(log, emulator, server_log, server, timeout, capture, baseline, ground_pixels)
    for device, direction, axis in (("dpad", "right", 2), ("circle", "up", 3)):
        print(f"Move {direction} with the {device}, then release it.", flush=True)
        stopped = re.compile(rf"UPSTREAM_LIVE_CONTROLS_STOPPED unit=(\d+) input={device} "
                             rf"x=({NUMBER}) y=({NUMBER})")
        trace = stopped.search(wait_for(log, emulator, stopped, timeout))
        time.sleep(0.4)  # Allow the stock server to finish interpolating the stopped unit.
        result = server_position(server_log, server, f"UPSTREAM_SERVER_CONTROLS_STOP input={device}")
        settled = result.groups()
        for sample in range(6):
            time.sleep(0.3)
            later = server_position(
                server_log, server, f"UPSTREAM_SERVER_CONTROLS_IDLE input={device} sample={sample}"
            ).groups()
            if int(later[1]) > int(settled[1]):
                break
        if (trace[1] != settled[0] or settled[0] != baseline[0] or settled[0] != later[0]
                or int(later[1]) <= int(settled[1])
                or float(settled[axis]) - float(baseline[axis]) < 0.05
                or abs(float(trace[2]) - float(settled[2])) > 0.2
                or abs(float(trace[3]) - float(settled[3])) > 0.2
                or any(abs(float(settled[i]) - float(later[i])) > 0.05 for i in (2, 3))):
            raise RuntimeError(f"{device} did not settle with continuing snapshots: "
                               f"client={trace.group(0)}, server={settled}, idle={later}")
        baseline = later
        if capture:
            capture(device, later)
        print(result.group(0), flush=True)
    server.stdin.write("runwave\n")
    server.stdin.flush()
    wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_STATE wave=2\b", timeout)


def capture_viewport(log, emulator, rpc, destination, position, require_palette=False):
    unit, _, x, y = position
    # Glacier is 150 × 250 tiles and the renderer centers a 16 × 12 tile viewport.
    left = max(0, min(150 - 16, math.floor(float(x) / 8 + 0.5) - 8))
    bottom = max(0, min(250 - 12, math.floor(float(y) / 8 + 0.5) - 6))
    frame = f"UPSTREAM_NETWORK_FRAME unit={unit} left={left} bottom={bottom} sprites=1"
    wait_for(log, emulator, rf"{frame}\b", 5)
    screenshot = rpc.screenshot(res_scale=1)
    destination.write_bytes(screenshot)
    # Default layout centers the 320px bottom screen at x=40 below the top screen.
    palette_pixels = {(70, 296): (24, 27, 32), (72, 296): (65, 74, 88)}
    cursor_pixels = {
        "touch-conveyor": ((284, 100), (250, 213, 107)),
        "touch-copper-wall": ((304, 100), (250, 213, 107)),
        "touch-removed": ((304, 100), (241, 117, 97)),
    }
    points = dict(palette_pixels)
    for phase, (point, color) in cursor_pixels.items():
        if destination.stem.endswith(f"-{phase}"):
            points[point] = color
    wall_interior = ((308, 108), (310, 110), (312, 112))
    pixels = az.inspect_png(screenshot, (*points, *wall_interior) if require_palette else ())
    if pixels["width"] != 400 or pixels["height"] != 480 or pixels["top_distinct_colors"] < 8:
        raise RuntimeError(f"Expected the textured Glacier viewport on the top screen: {pixels}")
    if require_palette:
        if any(pixels["samples"][point] != color for point, color in points.items()):
            raise RuntimeError(f"Expected palette and cursor pixels {points}, observed {pixels['samples']}")
    print(frame, flush=True)
    if require_palette:
        return tuple(pixels["samples"][point] for point in wall_interior)


def run_model(args, model, config, original_config, log, output):
    overrides = {
        "Miscellaneous": {"log_filter": "*:Info Debug.Emulated:Debug Service.SOC:Debug",
                          "check_for_update_on_start": "false"},
        "Debugging": {"enable_rpc_server": "true", "instant_debug_log": "true"},
        "UI": {"firstStart": "false", "confirmClose": "false", "pause_when_in_background": "false"},
        "System": {"is_new_3ds": "true" if model == "new3ds" else "false"},
        "Renderer": {"graphics_api": "2"},
        "Data Storage": {"use_virtual_sd": "true"},
    }
    if args.touch_build:
        # The synthetic bursts intentionally offer one sample type to all devices.
        overrides["Miscellaneous"]["log_filter"] += " Movie:Critical"
    if args.terrain_atlas:
        overrides["Layout"] = {"layout_option": "0", "swap_screen": "false",
                               "upright_screen": "false", "render_3d": "0", "screen_gap": "0"}
    config.write_text(az.patch_qt_config_text(original_config, overrides), encoding="utf-8")
    log.write_bytes(b"")
    server_log = output / f"{model}-server.log"
    with tempfile.TemporaryDirectory(prefix="min3dstry-live-net-") as work, server_log.open("wb") as terminal:
        server = subprocess.Popen(
            [args.java, "-jar", str(args.server)], cwd=work, stdin=subprocess.PIPE,
            stdout=terminal, stderr=subprocess.STDOUT, text=True,
        )
        try:
            server.stdin.write(f"config port {args.port}\nhost Glacier attack\n")
            server.stdin.flush()
            wait_for(server_log, server, f"Opened a server on port {args.port}\\.", 25)
            if args.desktop_name:
                print(f"{model}: Join 127.0.0.1:{args.port} from the stock PC client as "
                      f"{args.desktop_name!r}.", flush=True)
                wait_for(server_log, server, rf"{re.escape(args.desktop_name)} has connected", args.timeout)
            command = [str(args.azahar)]
            if args.controls_replay:
                replay = Path(work) / "controls.ctm"
                first_move = 42 if args.terrain_atlas else 22
                if args.building and args.desktop_name:
                    first_move = 120  # Allow time to inspect and place a block in the desktop client.
                if args.touch_build:
                    first_move = 68
                write_controls_replay(replay, first_move=first_move, building=args.building,
                                      touch_build=args.touch_build, model=model)
                command += ["-p", str(replay)]
            command.append(str(args.rom))
            emulator = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            rpc = az.AzaharRpcClient(timeout=2, retries=2)
            try:
                if args.controls:
                    def capture(phase, position):
                        return capture_viewport(log, emulator, rpc, output / f"{model}-{phase}.png", position,
                                                require_palette=args.touch_build)

                    verify_controls(log, emulator, server_log, server, args.timeout,
                                    capture if args.terrain_atlas else None, args.building, args.desktop_name,
                                    args.touch_build)
                    (output / f"{model}.png").write_bytes(rpc.screenshot())
                    print("Wave 2 applied. Press the emulated START button to leave.", flush=True)
                    emu_log = wait_for(log, emulator, "UPSTREAM_LIVE_CONTROLS_LEFT", args.timeout)
                else:
                    for join in (1, 2):
                        wait_for(log, emulator, rf"UPSTREAM_LIVE_NET_STATE join={join}\b", args.timeout)
                        if args.movement:
                            verify_movement(log, emulator, server_log, server, join, args.timeout)
                        if join == 2:
                            time.sleep(13)  # Exceed ArcNet's 12-second TCP timeout.
                        server.stdin.write("runwave\n")
                        server.stdin.flush()
                        wait_for(log, emulator, rf"UPSTREAM_LIVE_NET_SYNC join={join}\b", 25)
                    emu_log = wait_for(log, emulator, "UPSTREAM_LIVE_NET_PASS", args.timeout)
                    (output / f"{model}.png").write_bytes(rpc.screenshot())
            finally:
                rpc.close()
                stop(emulator)
                (output / f"{model}-emulator.log").write_bytes(log.read_bytes())
            if not args.controls:
                validate(emu_log)
            disconnected = rf"{PLAYER} has disconnected"
            if not args.controls:
                disconnected += rf"[\s\S]*{PLAYER} has disconnected"
            before_shutdown = wait_for(server_log, server, disconnected, 5)
            if args.desktop_name:
                desktop_join = before_shutdown.find(f"{args.desktop_name} has connected")
                arm_join = before_shutdown.find(f"{PLAYER} has connected")
                if not 0 <= desktop_join < arm_join or f"{args.desktop_name} has disconnected" in before_shutdown:
                    raise RuntimeError(f"{model} PC client did not stay connected through the ARM11 session")
                print(f"{model}: the PC client stayed connected through the ARM11 session", flush=True)
            server.stdin.write("exit\n")
            server.stdin.flush()
            server.wait(timeout=10)
            upstream = server_log.read_text(encoding="utf-8", errors="replace")
            departures = [line for line in upstream.splitlines() if f"{PLAYER} has disconnected" in line]
            # NetServer.sync can observe the closed transport before the disconnect event runs.
            orderly = all(re.search(r"\((closed|disappeared)\)", line) for line in departures)
            expected_joins = 1 if args.controls else 2
            if ((not args.controls and "clientOutdated" not in upstream)
                    or upstream.count(f"{PLAYER} has connected") != expected_joins
                    or len(departures) != expected_joins or not orderly):
                raise RuntimeError(f"{model} server did not report the expected completed sessions")
        finally:
            stop(server)
    for line in emu_log.splitlines():
        if (record := re.search(r"UPSTREAM_LIVE_(?:NET|CONTROLS)_.*", line)):
            print(f"{model}: {record.group(0)}", flush=True)


def validate(log):
    world = r"UPSTREAM_LIVE_NET_WORLD join=(\d+) map=Glacier bytes=(\d+) team=(\d+) fingerprint=([a-f0-9]{16})"
    state = r"UPSTREAM_LIVE_NET_STATE join=(\d+) wave=(\d+) time=(\d+) enemies=(\d+) coreBytes=(\d+)"
    worlds = re.findall(world, log)
    states = re.findall(state, log)
    synced = re.findall(r"UPSTREAM_LIVE_NET_SYNC join=(\d+) fromWave=(\d+) wave=(\d+) unit=(\S+) id=(\d+)", log)
    if ("UPSTREAM_LIVE_NET_REJECT build=159 packet=KickCallPacket2" not in log
            or "UPSTREAM_LIVE_NET_KEEPALIVE join=2 transport=tcp" not in log
            or [event[0] for event in worlds] != ["1", "2"]
            or [event[0] for event in states] != ["1", "2"]
            or [event[0] for event in synced] != ["1", "2"]
            or re.findall(r"UPSTREAM_LIVE_NET_DISCONNECTED join=(\d+)", log) != ["1", "2"]
            or any(int(event[1]) <= 0 or int(event[2]) <= 0 for event in worlds)
            or states[1][1] != synced[0][2]
            or synced[0][4] == synced[1][4]
            or any(int(event[1]) <= 0 or int(event[4]) <= 0 for event in states)
            or any(int(event[2]) != int(event[1]) + 1 or event[3] != "dagger"
                   or int(event[4]) <= 0 for event in synced)):
        raise RuntimeError("Expected live wave, state and entity updates on both joins")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--azahar", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", default=16568, type=int)
    parser.add_argument("--timeout", default=120, type=float)
    parser.add_argument("--model", choices=("old3ds", "new3ds"),
                        help="Check one Azahar profile (default: both)")
    parser.add_argument("--desktop-name",
                        help="Wait for a separately launched PC client and verify its connection overlaps both joins")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--movement", action="store_true", help="Verify the stock server applies scripted ARM11 movement")
    mode.add_argument("--controls", action="store_true", help="Verify D-pad and Circle Pad input and START disconnect")
    parser.add_argument("--controls-replay", action="store_true",
                        help="Replay the controls test input in Azahar 2126.2")
    parser.add_argument("--terrain-atlas", type=Path,
                        help="Stage the official v160.8 sprites.aatls and verify the rendered viewport")
    build_mode = parser.add_mutually_exclusive_group()
    build_mode.add_argument("--building", action="store_true",
                        help="Verify the ARM11 conveyor plan, copper cost and rendered response")
    build_mode.add_argument("--touch-build", action="store_true",
                            help="Verify touch placement, rotation and removal against the stock server")
    args = parser.parse_args()
    args.server, args.rom, args.azahar = (path.resolve() for path in (args.server, args.rom, args.azahar))
    with args.server.open("rb") as archive:
        if hashlib.file_digest(archive, "sha256").hexdigest() != SERVER_SHA256:
            parser.error("Expected the official v160.8 server-release.jar")
    if not 1 <= args.port <= 65535:
        parser.error("Port must be between 1 and 65535")
    if args.desktop_name is not None and (not args.desktop_name.strip() or args.desktop_name == PLAYER):
        parser.error("The PC client needs a distinct player name")
    if args.controls_replay and not args.controls:
        parser.error("--controls-replay needs --controls")
    if args.building and not args.terrain_atlas:
        parser.error("--building needs --terrain-atlas")
    if args.touch_build and not args.terrain_atlas:
        parser.error("--touch-build needs --terrain-atlas")
    if args.terrain_atlas:
        if not args.controls:
            parser.error("--terrain-atlas needs --controls")
        atlas_bytes = args.terrain_atlas.read_bytes()
        if hashlib.sha256(atlas_bytes).hexdigest() != ATLAS_SHA256:
            parser.error("Expected the official v160.8 sprites.aatls")
    config, log = az.resolve_azahar_paths()
    endpoint = config.parent.parent / "sdmc/3ds/min3dstry/server-endpoint.txt"
    originals = {path: path.read_bytes() if path.exists() else None for path in (config, log, endpoint)}
    atlas_path = endpoint.with_name("sprites.aatls")
    if args.terrain_atlas:
        originals[atlas_path] = atlas_path.read_bytes() if atlas_path.exists() else None
    output = args.rom.parent / "upstream-live-net"
    output.mkdir(exist_ok=True)
    try:
        for path in originals:
            path.parent.mkdir(parents=True, exist_ok=True)
        suffix = " controls" if args.controls else " movement" if args.movement else ""
        endpoint.write_text(f"127.0.0.1 {args.port}{suffix}\n", encoding="ascii")
        if args.terrain_atlas:
            atlas_path.write_bytes(atlas_bytes)
        for model in ((args.model,) if args.model else ("old3ds", "new3ds")):
            run_model(args, model, config, (originals[config] or b"").decode(), log, output)
    finally:
        for path, data in originals.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)


if __name__ == "__main__":
    main()
