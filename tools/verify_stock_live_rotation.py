#!/usr/bin/env python3
"""Check stock server map rotation on both ARM11 emulator profiles."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import zipfile

from capture_stock_rotating_server import ROTATE, ROTATE_WITH_PLANET_PATCH
from verify_stock_player_profiles import profile
from verify_upstream_live_net import SERVER_SHA256, stop, wait_for, write_controls_replay


INITIAL = r"UPSTREAM_LIVE_NET_WORLD join=1 map=Glacier bytes=\d+"


def server_command(server, command):
    server.stdin.write(command + "\n")
    server.stdin.flush()


def verify_rotation(args, model, emulator, output):
    if args.planet_patch:
        map_name = "Caldera"
        archive_path = "maps/erekir/caldera-erekir.msav"
        rotate = ROTATE_WITH_PLANET_PATCH
    else:
        map_name = "Ancient Caldera"
        archive_path = "maps/default/caldera.msav"
        rotate = ROTATE
    replacement = rf"UPSTREAM_LIVE_WORLD_RELOAD map={re.escape(map_name)} player=(\d+) bytes=\d+ wave=1\b"
    output.mkdir(parents=True, exist_ok=True)
    with socket.socket() as reserve:
        reserve.bind(("127.0.0.1", 0))
        port = reserve.getsockname()[1]
    server_log = output / "server.log"
    client_log = output / "client.log"
    with tempfile.TemporaryDirectory(prefix="m3ds-live-rotation-") as work, server_log.open("wb") as terminal:
        root = Path(work)
        with zipfile.ZipFile(args.server) as release:
            (root / "next.msav").write_bytes(release.read(archive_path))
        movie = root / "join.ctm"
        write_controls_replay(movie, model=model, unified_boot=True, stationary=True)
        server = subprocess.Popen([args.java, "-jar", str(args.server)], cwd=root, stdin=subprocess.PIPE,
                                  stdout=terminal, stderr=subprocess.STDOUT, text=True)
        try:
            wait_for(server_log, server, r"Server loaded\.", 30)
            server_command(server, f"config port {port}\nhost Glacier survival")
            wait_for(server_log, server, rf"Opened a server on port {port}\.", 25)
            with profile(model, emulator, root, "Rotation ARM11", port, args.terrain_atlas) as (log, _):
                log.write_bytes(b"")
                client = subprocess.Popen([str(emulator), "-p", str(movie), str(args.rom)],
                                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                try:
                    wait_for(log, client, INITIAL, args.timeout)
                    wait_for(log, client, r"UPSTREAM_NETWORK_FRAME unit=\d+", args.timeout)
                    wait_for(server_log, server, r"Rotation ARM11.*has connected", 20)
                    server_command(server,
                        'js var rotationPlayer = Groups.player.first(), rotationConnection = rotationPlayer.con; '
                        'var rotationSnapshot = rotationConnection.lastReceivedClientSnapshot; '
                        '"ROTATION_ORIGINAL player=" + rotationPlayer.id;')
                    before = wait_for(server_log, server, r"ROTATION_ORIGINAL player=\d+", 10)
                    player = int(re.search(r"ROTATION_ORIGINAL player=(\d+)", before)[1])
                    server.stdin.write(rotate)
                    server.stdin.flush()
                    after = wait_for(log, client, replacement, args.timeout)
                    if int(re.search(replacement, after)[1]) != player:
                        raise RuntimeError(f"{model}: replacement map used a different server player ID")
                    server_command(server, "runwave")
                    # Ignore any wave 2 event recorded before the map changed.
                    wait_for(log, client, replacement + r"[\s\S]*UPSTREAM_LIVE_CONTROLS_STATE wave=2\b", args.timeout)
                    server_command(server,
                        'js var rotated = Groups.player.first(); '
                        'if (rotated == null || rotated !== rotationPlayer || rotated.con !== rotationConnection '
                        '|| rotated.con.lastReceivedClientSnapshot <= rotationSnapshot '
                        f'|| String(Vars.state.map.name()) !== {json.dumps(map_name)}) '
                        'throw "The player, transport, or continuing input snapshots were lost"; '
                        '"ROTATION_CONTINUED player=" + rotated.id;')
                    wait_for(server_log, server, rf"ROTATION_CONTINUED player={player}\b", 10)
                finally:
                    stop(client, str(emulator))
                    shutil.copyfile(log, client_log)
        finally:
            stop(server)
    print(f"{model}: Glacier to {map_name}, same player {player}, wave 2 and continuing input confirmed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("rom", "server", "terrain-atlas", "original-azahar", "new-azahar", "output"):
        parser.add_argument("--" + name, type=lambda value: Path(value).resolve(), required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--timeout", type=float, default=160)
    parser.add_argument("--planet-patch", action="store_true",
                        help="rotate to original Erekir Caldera with the stock server's duct patch")
    args = parser.parse_args()
    with args.server.open("rb") as archive:
        if hashlib.file_digest(archive, "sha256").hexdigest() != SERVER_SHA256:
            parser.error("Expected the official v160.8 server-release.jar")
    for model, emulator in (("old3ds", args.original_azahar), ("new3ds", args.new_azahar)):
        verify_rotation(args, model, emulator, args.output / model)


if __name__ == "__main__":
    main()
