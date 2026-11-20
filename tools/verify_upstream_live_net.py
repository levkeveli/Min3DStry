#!/usr/bin/env python3
"""Join an official v160.8 loopback server from both Azahar console profiles."""

import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile
import time

import verify_azahar as az


SERVER_SHA256 = "faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c"
PLAYER = "Min3DStry ARM11"


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
            server.stdin.write(f"config port {args.port}\nhost Glacier\n")
            server.stdin.flush()
            wait_for(server_log, server, f"Opened a server on port {args.port}\\.", 25)
            emulator = subprocess.Popen([str(args.azahar), str(args.rom)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            rpc = az.AzaharRpcClient(timeout=2, retries=2)
            try:
                emu_log = wait_for(log, emulator, "UPSTREAM_LIVE_NET_PASS", args.timeout)
                (output / f"{model}.png").write_bytes(rpc.screenshot())
            finally:
                rpc.close()
                stop(emulator)
                (output / f"{model}-emulator.log").write_bytes(log.read_bytes())
            validate(emu_log)
            wait_for(server_log, server, rf"{PLAYER} has disconnected", 5)
            server.stdin.write("exit\n")
            server.stdin.flush()
            server.wait(timeout=10)
            upstream = server_log.read_text(encoding="utf-8", errors="replace")
            if "clientOutdated" not in upstream or upstream.count(f"{PLAYER} has disconnected") != 2:
                raise RuntimeError(f"{model} server did not report version rejection and two disconnects")
        finally:
            stop(server)
    for line in emu_log.splitlines():
        if "UPSTREAM_LIVE_NET_" in line:
            print(f"{model}: {line[line.index('UPSTREAM_LIVE_NET_'):]}", flush=True)


def validate(log):
    world = r"UPSTREAM_LIVE_NET_WORLD join=(\d+) map=Glacier bytes=(\d+) team=(\d+) fingerprint=([a-f0-9]{16})"
    state = r"UPSTREAM_LIVE_NET_STATE join=(\d+) wave=(\d+) time=(\d+) enemies=(\d+) coreBytes=(\d+)"
    worlds = re.findall(world, log)
    states = re.findall(state, log)
    if ("UPSTREAM_LIVE_NET_REJECT build=159 packet=KickCallPacket2" not in log
            or [event[0] for event in worlds] != ["1", "2"]
            or [event[0] for event in states] != ["1", "2"]
            or re.findall(r"UPSTREAM_LIVE_NET_DISCONNECTED join=(\d+)", log) != ["1", "2"]
            or any(int(event[1]) <= 0 or int(event[2]) <= 0 for event in worlds)
            or any(int(event[1]) <= 0 or int(event[4]) <= 0 for event in states)):
        raise RuntimeError("Expected a version rejection, two live worlds, state snapshots and disconnects")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, type=Path)
    parser.add_argument("--rom", required=True, type=Path)
    parser.add_argument("--azahar", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", default=16568, type=int)
    parser.add_argument("--timeout", default=120, type=float)
    args = parser.parse_args()
    args.server, args.rom, args.azahar = (path.resolve() for path in (args.server, args.rom, args.azahar))
    with args.server.open("rb") as archive:
        if hashlib.file_digest(archive, "sha256").hexdigest() != SERVER_SHA256:
            parser.error("Expected the official v160.8 server-release.jar")
    if not 1 <= args.port <= 65535:
        parser.error("Port must be between 1 and 65535")
    config, log = az.resolve_azahar_paths()
    endpoint = config.parent.parent / "sdmc/3ds/min3dstry/server-endpoint.txt"
    originals = {path: path.read_bytes() if path.exists() else None for path in (config, log, endpoint)}
    output = args.rom.parent / "upstream-live-net"
    output.mkdir(exist_ok=True)
    try:
        for path in originals:
            path.parent.mkdir(parents=True, exist_ok=True)
        endpoint.write_text(f"127.0.0.1 {args.port}\n", encoding="ascii")
        for model in ("old3ds", "new3ds"):
            run_model(args, model, config, (originals[config] or b"").decode(), log, output)
    finally:
        for path, data in originals.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)


if __name__ == "__main__":
    main()
