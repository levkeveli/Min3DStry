#!/usr/bin/env python3
"""Verify the live singleplayer HUD minimap on Azahar and capture its frame."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_azahar import (  # noqa: E402
    AzaharRpcClient,
    inspect_png,
    patch_qt_config_text,
    resolve_azahar_bin,
    resolve_azahar_paths,
)

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ROM = ROOT / "research/upstream-runtime/build/singleplayer-minimap/upstream-probe.3dsx"
MINIMAP_LOG = "UPSTREAM_MINIMAP size=64"
# The app draws the minimap at (336, 8) with a 56 px edge on the top screen.
MINIMAP_BOX = (336, 8, 56, 56)
MINIMAP_SAMPLES = tuple(
    (x, y)
    for y in range(10, 64, 2)
    for x in range(338, 392, 2)
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, default=DEFAULT_ROM)
    parser.add_argument("--model", choices=("new3ds", "old3ds"), default="new3ds")
    parser.add_argument("--slot", type=int, default=0)
    parser.add_argument("--out", type=Path, default=None)
    parser.add_argument("--timeout", type=float, default=240.0)
    parser.add_argument("--azahar", default=None)
    args = parser.parse_args()

    rom = args.rom if args.rom.is_absolute() else (Path.cwd() / args.rom)
    if not rom.is_file():
        print(f"verify_minimap_azahar: ROM not found: {rom}", file=sys.stderr)
        return 2
    out = args.out or (ROOT / f"build/emu-verify/minimap-{args.model}.png")

    azahar_bin = resolve_azahar_bin(args.azahar)
    config_path, log_path = resolve_azahar_paths(azahar_bin)
    had_config = config_path.exists()
    original_config = config_path.read_text(encoding="utf-8") if had_config else ""
    overrides = {
        "Miscellaneous": {
            "log_filter": "*:Info Debug.Emulated:Debug",
            "check_for_update_on_start": "false",
        },
        "Debugging": {
            "enable_rpc_server": "true",
            "instant_debug_log": "true",
        },
        "UI": {
            "firstStart": "false",
            "confirmClose": "false",
        },
        "System": {
            "is_new_3ds": "true" if args.model == "new3ds" else "false",
        },
    }
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(patch_qt_config_text(original_config, overrides), encoding="utf-8")
    if log_path.exists():
        log_path.write_text("", encoding="utf-8")

    sdmc = Path(os.environ["APPDATA"]) / "Azahar/sdmc"
    sd_min3dstry = sdmc / "3ds/min3dstry"
    select = sd_min3dstry / "campaign-select.txt"
    select.parent.mkdir(parents=True, exist_ok=True)
    select.write_text(f"{args.slot}\n", encoding="utf-8")

    proc = subprocess.Popen([azahar_bin, str(rom)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        rpc = AzaharRpcClient(timeout=2.0, retries=3)
        deadline = time.monotonic() + args.timeout
        loaded = False
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError(f"Azahar exited early with status {proc.returncode}")
            text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
            if "UPSTREAM_SP_LOADED" in text:
                loaded = True
            if MINIMAP_LOG in text:
                print(next(line for line in text.splitlines() if MINIMAP_LOG in line))
                break
            time.sleep(0.5)
        if not loaded:
            raise RuntimeError("Singleplayer session did not load")
        if MINIMAP_LOG not in log_path.read_text(encoding="utf-8", errors="replace"):
            raise RuntimeError("Minimap upload trace did not appear")

        # Let the game render several frames after the upload.
        time.sleep(5.0)
        png = b""
        last_error: Exception | None = None
        for _ in range(3):
            try:
                png = rpc.screenshot()
                break
            except Exception as failure:  # noqa: BLE001 - retry transient emulator timeouts
                last_error = failure
                time.sleep(2.0)
        if not png:
            raise RuntimeError(f"Azahar screenshot failed: {last_error}")

        info = inspect_png(png, MINIMAP_SAMPLES)
        colors = set(info.get("samples", {}).values())
        if len(colors) < 4:
            raise AssertionError(
                f"Minimap region is flat: {len(colors)} distinct sample colors "
                f"(expected >= 4) at {MINIMAP_BOX}"
            )
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(png)
        print(
            f"[PASS] minimap model={args.model} distinct={len(colors)} "
            f"size={info['width']}x{info['height']} PNG={out}"
        )
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
        if select.exists():
            select.unlink()
        if had_config:
            config_path.write_text(original_config, encoding="utf-8")
        elif config_path.exists():
            config_path.unlink()


if __name__ == "__main__":
    raise SystemExit(main())
