#!/usr/bin/env python3
"""Verify Min3DStry upstream network handshake against an unmodified v160.8 server JAR."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path


def compile_probe(repo_root: Path, out_bin: Path) -> None:
    cc = os.environ.get("CC") or shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
    if not cc:
        raise RuntimeError("No host C compiler found (cc/gcc/clang)")
    out_bin.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            cc,
            "-std=c11",
            "-Wall",
            "-Wextra",
            "-Werror",
            f"-I{repo_root / 'include'}",
            str(repo_root / "src/net/mindustry_net.c"),
            str(repo_root / "tests/net/mindustry_net_probe.c"),
            "-o",
            str(out_bin),
        ],
        check=True,
    )


def run_probe(probe_bin: Path, args: list[str]) -> str:
    proc = subprocess.run(
        [str(probe_bin), *args],
        text=True,
        capture_output=True,
        check=True,
    )
    return proc.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-jar", type=Path, required=True)
    parser.add_argument("--port", type=int, default=16567)
    parser.add_argument("--map", default="Glacier")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[1]
    probe_bin = repo_root / "build/host/mindustry_net_probe"
    compile_probe(repo_root, probe_bin)

    server_jar = args.server_jar.resolve()
    if not server_jar.is_file():
        raise FileNotFoundError(f"Server JAR not found: {server_jar}")

    with tempfile.TemporaryDirectory(prefix="min3dstry-net-") as scratch:
        proc = subprocess.Popen(
            ["java", "-jar", str(server_jar)],
            cwd=scratch,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        try:
            assert proc.stdin is not None
            proc.stdin.write(f"config port {args.port}\nhost {args.map}\n")
            proc.stdin.flush()

            ping_line = ""
            deadline = time.monotonic() + 25.0
            while time.monotonic() < deadline:
                try:
                    ping_line = run_probe(probe_bin, ["ping", "127.0.0.1", str(args.port)])
                    break
                except subprocess.CalledProcessError:
                    time.sleep(0.5)
            if not ping_line:
                raise RuntimeError("Timed out waiting for Mindustry server UDP discovery reply")
            print(ping_line)
            if f"map={args.map}" not in ping_line or "version=160 " not in ping_line:
                raise AssertionError(f"Unexpected discovery response: {ping_line}")

            ok_line = run_probe(
                probe_bin, ["handshake", "127.0.0.1", str(args.port), "160", "Min3DStryOK"]
            )
            print(ok_line)
            if "status=0 " not in ok_line or "udp=1 " not in ok_line or "stream_type=2 " not in ok_line:
                raise AssertionError(f"Expected StreamBegin handshake acceptance, got: {ok_line}")

            bad_line = run_probe(
                probe_bin, ["handshake", "127.0.0.1", str(args.port), "159", "Min3DStryOld"]
            )
            print(bad_line)
            if "status=0 " in bad_line or "udp=1 " not in bad_line:
                raise AssertionError(f"Expected outdated version rejection, got: {bad_line}")

            proc.stdin.write("exit\n")
            proc.stdin.flush()
            proc.wait(timeout=10)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait(timeout=5)
        if proc.stdout is not None:
            server_out = proc.stdout.read()
            print("--- server tail ---")
            print("\n".join(server_out.splitlines()[-15:]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
