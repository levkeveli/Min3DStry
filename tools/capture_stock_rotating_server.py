#!/usr/bin/env python3
"""Capture two WorldStreams over one connection to the unmodified v160.8 server."""

import argparse
from os import pathsep
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile

from verify_upstream_live_net import stop, wait_for


CLIENT = Path(__file__).resolve().parents[1] / "research/upstream-runtime/host/UpstreamStockRotationCapture.java"
ASSETS = CLIENT.parents[1] / "src/network-live/java"
LOAD_NEXT = (
    "Vars.world.loadMap(Packages.mindustry.io.MapIO.createMap(Packages.arc.files.Fi.get('next.msav'), false));"
)
PLAY = "js Packages.mindustry.server.ServerControl.instance.play(false, () => { "
ROTATE = PLAY + LOAD_NEXT + " })\n"


def stage_assets(root):
    (root / "base.properties").write_text("fixture.base=Before rotation\n", encoding="ascii")
    (root / "rotated.properties").write_text("fixture.rotation=After rotation\n", encoding="ascii")
    factory = (
        "js var assetFixture = name => { const a = new Packages.mindustry.mod.data.BundleAsset(); "
        "a.readOverride(name, Packages.arc.files.Fi.get(name)); return a; }; "
        "Vars.state.data.load(Packages.arc.struct.Seq.with(assetFixture('base.properties'))); "
        "'STOCK_ASSETS_STAGED';\n"
    )
    rotate = (
        PLAY + LOAD_NEXT + " Vars.state.data.load(Packages.arc.struct.Seq.with("
        "assetFixture('base.properties'), assetFixture('rotated.properties'))); })\n"
    )
    return factory, rotate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--next-map", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--asset-cache", type=Path,
                        help="Serve two bundle assets and keep a disk cache between runs")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    output = args.output.resolve()
    with socket.socket() as reserve:
        reserve.bind(("127.0.0.1", 0))
        port = reserve.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="m3ds-stock-rotation-capture-") as temp:
        root = Path(temp)
        shutil.copyfile(args.next_map, root / "next.msav")
        classes = root / "classes"
        jar = args.server.resolve()
        subprocess.run([args.javac, "-cp", str(jar), "-d", str(classes), str(CLIENT),
                        str(ASSETS / "UpstreamServerAssets.java"), str(ASSETS / "UpstreamAssetSha256.java")],
                       cwd=root, check=True)
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
                rotate = ROTATE
                if args.asset_cache:
                    setup, rotate = stage_assets(root)
                    server.stdin.write(setup)
                    server.stdin.flush()
                    wait_for(server_log, server, r"STOCK_ASSETS_STAGED", 10)
                client_log = output / "capture.log"
                with client_log.open("w", encoding="utf8") as client_output:
                    command = [args.java, "-cp", pathsep.join((str(jar), str(classes))),
                               "UpstreamStockRotationCapture", str(port), str(output)]
                    if args.asset_cache:
                        command.append(str(args.asset_cache.resolve()))
                    client = subprocess.Popen(command, cwd=root, stdout=client_output,
                                              stderr=subprocess.STDOUT)
                    try:
                        wait_for(client_log, client, r"STOCK_ROTATION_CAPTURE world=1 ", 20)
                        wait_for(server_log, server, r"Rotation Fixture.*has connected", 10)
                        server.stdin.write(rotate)
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
