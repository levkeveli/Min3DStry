#!/usr/bin/env python3
"""Capture both WorldStreams from one unmodified v160.8 server connection."""

import argparse
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile

from verify_upstream_live_net import stop, wait_for


CLIENT = Path(__file__).resolve().parents[1] / "research/upstream-runtime/host/UpstreamStockRotationCapture.java"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--next-map", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    output = args.output.resolve()
    with socket.socket() as reserve:
        reserve.bind(("127.0.0.1", 0))
        port = reserve.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-rotation-capture-") as temp:
        root = Path(temp)
        shutil.copyfile(args.next_map, root / "next.msav")
        server_log = root / "server.log"
        with server_log.open("w", encoding="utf8") as log:
            server = subprocess.Popen([args.java, "-jar", str(args.server.resolve())], cwd=root,
                                      text=True, encoding="utf8", stdin=subprocess.PIPE,
                                      stdout=log, stderr=subprocess.STDOUT)
            try:
                wait_for(server_log, server, r"Server loaded\.", 30)
                server.stdin.write(f"config port {port}\nhost Glacier survival\n")
                server.stdin.flush()
                wait_for(server_log, server, r"Opened a server on port", 20)
                client_log = output / "capture.log"
                with client_log.open("w", encoding="utf8") as client_output:
                    client = subprocess.Popen([args.java, "-cp", str(args.server.resolve()), str(CLIENT),
                                               str(port), str(output)], cwd=root, stdout=client_output,
                                              stderr=subprocess.STDOUT)
                    try:
                        wait_for(client_log, client, r"STOCK_ROTATION_CAPTURE world=1 ", 20)
                        wait_for(server_log, server, r"Rotation Fixture.*has connected", 10)
                        server.stdin.write("js Packages.mindustry.server.ServerControl.instance.play(false, () => "
                                           "Vars.world.loadMap(Packages.mindustry.io.MapIO.createMap("
                                           "Packages.arc.files.Fi.get('next.msav'), false)))\n")
                        server.stdin.flush()
                        if client.wait(timeout=30) != 0:
                            raise RuntimeError(client_log.read_text(encoding="utf8"))
                    finally:
                        stop(client)
                print(client_log.read_text(encoding="utf8"), end="")
            finally:
                stop(server)
                shutil.copyfile(server_log, output / "server.log")


if __name__ == "__main__":
    main()
