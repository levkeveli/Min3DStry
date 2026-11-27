#!/usr/bin/env python3
"""Join a New 3DS hosted world using the pinned v160.8 Java transport and packet classes."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess

import verify_upstream_singleplayer as sp
from verify_upstream_campaign_launch import write_input_replay
from verify_upstream_live_net import SERVER_SHA256


DESKTOP_SHA256 = "6dd6da921d5021a84823456e05b477ee8de8511990d40de850b37dd0905152f2"
HOST_PEER_LEFT = r"UPSTREAM_HOST_PEER_LEFT slot=\d+\b"


def join_stock_world(java: str, classpath: str, output: Path, name: str) -> int:
    payload = output / f"{name}-world-stream.zlib"
    client_log = output / f"{name}-pc.log"
    mode = "--rejoin" if name == "rejoin" else "--confirm"
    with client_log.open("wb") as pc_log:
        subprocess.run(
            [java, "-cp", classpath, "UpstreamWorldStreamCapture", "6567", str(payload), mode],
            stdout=pc_log, stderr=subprocess.STDOUT, timeout=50, check=True,
        )
    if not payload.is_file() or payload.stat().st_size == 0:
        raise AssertionError("The stock client did not receive the compressed world")
    transcript = client_log.read_text(encoding="utf-8")
    if not re.search(r"^UPSTREAM_HOSTED_WORLD_STOCK_LOAD tiles=256x256 core=core-shard "
                     r"map=(?:Ground[ _]Zero|groundZero)$", transcript, re.MULTILINE):
        raise AssertionError("Stock Java did not load the expected Ground Zero world and core")
    if not re.search(r"^UPSTREAM_HOSTED_WORLD_CORE_SYNC pos=\d+ block=core-shard$", transcript, re.MULTILINE):
        raise AssertionError("Stock Java did not receive the live snapshot of the requested core")
    return payload.stat().st_size


def play_stock_desktop(java, classpath, output, log, emulator_process, rpc):
    desktop_log = output / "desktop.log"
    screenshot = output / "desktop.png"
    with desktop_log.open("wb") as stream:
        subprocess.run(
            [java, f"-Dmindustry.data.dir={output / 'pc-data'}", "-cp", classpath,
             "VerifyHostedDesktop", str(screenshot)],
            stdout=stream, stderr=subprocess.STDOUT, timeout=70, check=True,
        )
    placed = re.search(r"^STOCK_DESKTOP_HOST_BUILD block=conveyor x=(\d+) y=(\d+)$",
                       desktop_log.read_text(encoding="utf-8"), re.MULTILINE)
    if placed is None or not screenshot.is_file():
        raise AssertionError("Stock desktop placement or framebuffer capture was missing")
    x, y = placed.groups()
    sp._wait_for_log(log, emulator_process, rf"UPSTREAM_SP_BUILT block=conveyor x={x} y={y} rotation=0\b", 15)
    sp._wait_for_log(log, emulator_process, rf"(?s)(?:{HOST_PEER_LEFT}.*?){{2}}{HOST_PEER_LEFT}", 15)
    (output / "new3ds-desktop-host.png").write_bytes(rpc.screenshot())
    print(f"STOCK_DESKTOP_HOST_REPLAY model=new3ds conveyor={x},{y} disconnect=ok")


def verify(args):
    with args.stock_jar.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != SERVER_SHA256:
            raise ValueError("Expected the unmodified v160.8 Java release")
    args.output.mkdir(parents=True)
    output = args.output.resolve()
    classes = output / "classes"
    classes.mkdir()
    capture = Path(__file__).resolve().parents[1] / "research/upstream-runtime/host/UpstreamWorldStreamCapture.java"
    subprocess.run([args.javac, "-cp", str(args.stock_jar), "-d", str(classes), str(capture)], check=True)
    if args.desktop_jar:
        with args.desktop_jar.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != DESKTOP_SHA256:
                raise ValueError("Expected the unmodified v160.8 graphical desktop release")
        subprocess.run([args.javac, "-cp", str(args.desktop_jar), "-d", str(classes),
                        str(capture.with_name("VerifyHostedDesktop.java"))], check=True)
    sd = output / "virtual-sd"
    root = sd / "3ds/min3dstry"
    root.mkdir(parents=True)
    (root / "startup.txt").write_text("direct\n", encoding="ascii")
    movie = output / "new3ds-host.ctm"
    # Open the local world's pause menu and choose its Host Game shortcut.
    write_input_replay(movie, "new3ds", [(42, 45, sp.KEY_X), (58, 61, 1 << 8)], 220, b"Min3DStry Host")

    emulator = sp.az.resolve_azahar_bin(args.azahar)
    config, log = sp.az.resolve_azahar_paths(emulator)
    originals = {file: file.read_bytes() if file.is_file() else None for file in (config, log)}
    try:
        config.parent.mkdir(parents=True, exist_ok=True)
        storage = {"Data Storage": {"use_custom_storage": "true", "sdmc_directory": sd.as_posix() + "/"}}
        config.write_text(sp.az.patch_qt_config_text((originals[config] or b"").decode(), storage), encoding="utf-8")
        proc, rpc = sp._launch_azahar("new3ds", args.rom, emulator, config, log, movie)
        try:
            sp._wait_for_log(log, proc, r"UPSTREAM_PAUSE_MENU open=true\b", 120)
            sp._wait_for_log(log, proc, r"UPSTREAM_HOST_READY version=160\.8 port=6567 .*tiles=256x256\b", 150)
            classpath = f"{classes}{os.pathsep}{args.stock_jar.resolve()}"
            joined = join_stock_world(args.java, classpath, output, "join")
            sp._wait_for_log(log, proc, HOST_PEER_LEFT, 30)
            rejoined = join_stock_world(args.java, classpath, output, "rejoin")
            sp._wait_for_log(log, proc, rf"(?s){HOST_PEER_LEFT}.*?{HOST_PEER_LEFT}", 30)
            rom_frame = rpc.screenshot()
            (output / "new3ds-host.png").write_bytes(rom_frame)
            if sp.az.inspect_png(rom_frame)["top_distinct_colors"] < 8:
                raise AssertionError("The handheld did not draw the hosted world")
            print(f"UPSTREAM_HOSTED_WORLD_REPLAY model=new3ds stock-bytes={joined} "
                  f"rejoin-bytes={rejoined} snapshot=ok chat=ok core-sync=ok")
            if args.desktop_jar:
                desktop_classpath = f"{classes}{os.pathsep}{args.desktop_jar.resolve()}"
                play_stock_desktop(args.java, desktop_classpath, output, log, proc, rpc)
        finally:
            rpc.close()
            sp._stop_proc(proc, emulator)
            if log.is_file():
                shutil.copyfile(log, output / "new3ds-host.log")
    finally:
        for file, data in originals.items():
            if data is None:
                file.unlink(missing_ok=True)
            else:
                file.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--stock-jar", type=Path, required=True)
    parser.add_argument("--desktop-jar", type=Path,
                        help="Also mine and build with the stock graphical v160.8 client")
    parser.add_argument("--azahar")
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--output", type=Path, required=True)
    verify(parser.parse_args())


if __name__ == "__main__":
    main()
