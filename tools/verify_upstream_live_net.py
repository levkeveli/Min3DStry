#!/usr/bin/env python3
"""Join an official v160.8 loopback server from both Azahar console profiles."""

import argparse
import hashlib
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import time

import verify_azahar as az


SERVER_SHA256 = "faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c"
PLAYER = "Min3DStry ARM11"
NUMBER = r"-?\d+(?:\.\d+)?"


def write_controls_replay(path):
    """Write the Azahar 2126.2 CTM input for this probe's pad/touch polling."""
    # The pinned Azahar movie header and HID sample definitions use packed little-endian fields.
    revision = bytes.fromhex("219d743a1e698101fe1ab8bef43e6563126c3c27")
    samples_per_second = 234
    frames = 45 * samples_per_second
    header = struct.pack("<4sQ20sQQ32sIQq156x", b"CTM\x1b", 0, revision, 1780000000,
                         0x4D334453, b"Min3DStry input verification", 0, frames, 0)
    touch_idle = struct.pack("<B6x", 1)
    with path.open("wb") as movie:
        movie.write(header)
        for frame in range(frames):
            seconds = frame // samples_per_second
            buttons = (1 << 4) if seconds == 22 else (1 << 3) if seconds == 38 else 0
            circle_y = 106 if seconds == 28 else 0
            movie.write(struct.pack("<BHhh", 0, buttons, 0, circle_y))
            movie.write(touch_idle)


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


def verify_controls(log, emulator, server_log, server, timeout):
    wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_READY unit=\d+", timeout)
    baseline = server_position(server_log, server, "UPSTREAM_SERVER_CONTROLS_READY").groups()
    for device, direction, axis in (("dpad", "right", 2), ("circle", "up", 3)):
        print(f"Move {direction} with the {device}, then release it.", flush=True)
        stopped = re.compile(rf"UPSTREAM_LIVE_CONTROLS_STOPPED unit=(\d+) input={device} "
                             rf"x=({NUMBER}) y=({NUMBER})")
        client_unit, client_x, client_y = stopped.search(wait_for(log, emulator, stopped, timeout)).groups()
        time.sleep(0.4)  # Allow the stock server to finish interpolating the stopped unit.
        result = server_position(server_log, server, f"UPSTREAM_SERVER_CONTROLS_STOP input={device}")
        settled = result.groups()
        time.sleep(0.3)
        later = server_position(server_log, server, f"UPSTREAM_SERVER_CONTROLS_IDLE input={device}").groups()
        if (client_unit != settled[0] or settled[0] != baseline[0] or settled[0] != later[0]
                or int(later[1]) <= int(settled[1])
                or float(settled[axis]) - float(baseline[axis]) < 0.05
                or abs(float(client_x) - float(settled[2])) > 0.2
                or abs(float(client_y) - float(settled[3])) > 0.2
                or any(abs(float(settled[i]) - float(later[i])) > 0.05 for i in (2, 3))):
            raise RuntimeError(f"{device} did not settle: client=({client_unit}, {client_x}, {client_y}), "
                               f"server={settled}, idle={later}")
        baseline = later
        print(result.group(0), flush=True)
    server.stdin.write("runwave\n")
    server.stdin.flush()
    wait_for(log, emulator, r"UPSTREAM_LIVE_CONTROLS_STATE wave=2\b", timeout)


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
                write_controls_replay(replay)
                command += ["-p", str(replay)]
            command.append(str(args.rom))
            emulator = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            rpc = az.AzaharRpcClient(timeout=2, retries=2)
            try:
                if args.controls:
                    verify_controls(log, emulator, server_log, server, args.timeout)
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
    config, log = az.resolve_azahar_paths()
    endpoint = config.parent.parent / "sdmc/3ds/min3dstry/server-endpoint.txt"
    originals = {path: path.read_bytes() if path.exists() else None for path in (config, log, endpoint)}
    output = args.rom.parent / "upstream-live-net"
    output.mkdir(exist_ok=True)
    try:
        for path in originals:
            path.parent.mkdir(parents=True, exist_ok=True)
        suffix = " controls" if args.controls else " movement" if args.movement else ""
        endpoint.write_text(f"127.0.0.1 {args.port}{suffix}\n", encoding="ascii")
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
