#!/usr/bin/env python3
"""Deploy a .3dsx with 3dslink while capturing the app's log stream directly.

The 3DS side (`link3dsConnectToHost`) connects to the PC on TCP 17491 and sends
raw log lines. `3dslink -s` also listens there, but buffers its stdout, so long
sessions are lost when the server is stopped. This tool runs its own listener
instead:

1. bind 0.0.0.0:<port> and write every received chunk to the log immediately,
2. run `3dslink` WITHOUT `-s` (upload only, trailing `m3ds-stream-logs` arg),
3. keep reading for --seconds after launch, then close.

It never probes NetLoader with a connect-and-close; the only connection to the
console is the actual upload.
"""

from __future__ import annotations

import argparse
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import device_deploy  # noqa: E402


def capture_stream(server: socket.socket, log_path: Path, seconds: float,
                   stop: threading.Event) -> threading.Thread:
    """Accept one app connection and append its bytes to log_path until stopped."""

    def run() -> None:
        with log_path.open("w", encoding="utf-8", newline="") as handle:
            handle.write("")
            handle.flush()
            deadline = time.monotonic() + seconds
            while not stop.is_set() and time.monotonic() < deadline:
                try:
                    conn, _ = server.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                conn.settimeout(0.5)
                with conn:
                    while not stop.is_set() and time.monotonic() < deadline:
                        try:
                            data = conn.recv(4096)
                        except socket.timeout:
                            continue
                        except OSError:
                            break
                        if not data:
                            break
                        handle.write(data.decode("utf-8", "replace"))
                        handle.flush()

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    return thread


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", required=True)
    parser.add_argument("--ip", required=True)
    parser.add_argument("--seconds", type=float, default=330.0)
    parser.add_argument("--log-out", required=True)
    parser.add_argument("--port", type=int, default=17491)
    parser.add_argument("--retries", type=int, default=3)
    parser.add_argument("--3dslink", dest="threed_slink", default=None)
    args = parser.parse_args(argv)

    rom = Path(args.rom)
    if not rom.is_file():
        print(f"hw_capture: ROM not found: {rom}", file=sys.stderr)
        return 2
    exe = device_deploy.resolve_3dslink(args.threed_slink)
    log_path = Path(args.log_out)
    log_path.parent.mkdir(parents=True, exist_ok=True)

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(("0.0.0.0", args.port))
    except OSError as exc:
        print(f"hw_capture: cannot bind port {args.port}: {exc}", file=sys.stderr)
        return 2
    server.listen(1)
    server.settimeout(0.5)

    stop = threading.Event()
    thread = capture_stream(server, log_path, args.seconds, stop)

    cmd = [exe, "-a", args.ip, "-r", str(max(0, args.retries)),
           str(rom.resolve()), "m3ds-stream-logs"]
    print("hw_capture: " + " ".join(cmd), flush=True)
    started = time.monotonic()
    proc = subprocess.run(cmd, text=True)
    if proc.returncode != 0:
        print(f"hw_capture: 3dslink exited {proc.returncode}", file=sys.stderr)
        stop.set()
        thread.join(timeout=1.0)
        server.close()
        return 1
    remaining = args.seconds - (time.monotonic() - started)
    if remaining > 0:
        time.sleep(remaining)
    stop.set()
    thread.join(timeout=3.0)
    server.close()
    size = log_path.stat().st_size if log_path.exists() else 0
    print(f"hw_capture: {size} bytes -> {log_path}")
    return 0 if proc.returncode == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
