#!/usr/bin/env python3
"""Autonomous Azahar emulator verification harness for Min3DStry."""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import shutil
import socket
import struct
import subprocess
import sys
import time
import zlib
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_parity import parse_parity_debug_line  # noqa: E402

RPC_VERSION = 2
RPC_HEADER_SIZE = 16
MAX_PACKET_SIZE = 32 * 1024 + RPC_HEADER_SIZE
AZAHAR_RPC_PORT = 45987

REQ_PROCESS_LIST = 3
REQ_TAKE_SCREENSHOT = 5
REQ_READ_SCREENSHOT = 6
REQ_GET_PERF_STATS = 7

PERF_STATS_FIELDS = (
    "system_fps",
    "game_fps",
    "time_vblank_interval",
    "time_hle_svc",
    "time_hle_ipc",
    "time_gpu",
    "time_swap",
    "time_remaining",
    "emulation_speed",
    "artic_transmitted",
    "artic_events",
)

FRAME_RE = re.compile(
    r"\[MIN3DSTRY\] FRAME "
    r"tick=(?P<tick>\d+) paused=(?P<paused>\d+) "
    r"cam=\((?P<cam_x>-?\d+(?:\.\d+)?),(?P<cam_y>-?\d+(?:\.\d+)?)\) "
    r"draw=R:(?P<rects>\d+),L:(?P<lines>\d+),T:(?P<triangles>\d+),C:(?P<circles>\d+) "
    r"cull=(?P<cull>\d+) sw=(?P<sw>\d+) drop=(?P<drop>\d+) "
    r"cpu=(?P<cpu>-?\d+(?:\.\d+)?)% gpu=(?P<gpu>-?\d+(?:\.\d+)?)% "
    r"cmdbuf=(?P<cmdbuf>-?\d+(?:\.\d+)?)%"
)

AUDIO_RE = re.compile(
    r"\[MIN3DSTRY\] AUDIO "
    r"backend=(?P<backend>[a-z0-9_]+) "
    r"state=(?P<state>[a-z0-9_]+) "
    r"rc=(?P<rc>0x[0-9a-fA-F]+) "
    r"rate=(?P<rate>\d+) "
    r"voices=(?P<voices>\d+)/(?P<max_voices>\d+) "
    r"clips=(?P<clips>\d+)"
)

AUDIO_ROMFS_RE = re.compile(
    r"\[MIN3DSTRY\] AUDIO_ROMFS "
    r"status=(?P<status>[a-z0-9_]+) "
    r"clips=(?P<clips>\d+) "
    r"bytes=(?P<bytes>\d+) "
    r"ui=(?P<ui>-?\d+)"
)

MEM_RE = re.compile(
    r"\[MIN3DSTRY\] MEM "
    r"app=(?P<app_mib>\d+)MiB "
    r"reserve=(?P<reserve_mib>\d+)MiB "
    r"budget=(?P<budget>\d+) "
    r"used=(?P<used>\d+) "
    r"peak=(?P<peak>\d+) "
    r"remaining=(?P<remaining>\d+) "
    r"world=(?P<world_used>\d+)/(?P<world_limit>\d+) "
    r"textures=(?P<texture_used>\d+)/(?P<texture_limit>\d+) "
    r"transient=(?P<transient_used>\d+)/(?P<transient_limit>\d+) "
    r"failures=(?P<failures>\d+) "
    r"status=(?P<status>[a-z]+)"
)


def _build_header(request_type: int, data_size: int, request_id: int | None = None) -> tuple[bytes, int]:
    req_id = random.getrandbits(32) if request_id is None else request_id
    return struct.pack("<IIII", RPC_VERSION, req_id, request_type, data_size), req_id


def _parse_header(raw_reply: bytes, expected_id: int, expected_type: int) -> bytes | None:
    if len(raw_reply) < RPC_HEADER_SIZE:
        return None
    reply_version, reply_id, reply_type, reply_size = struct.unpack("<IIII", raw_reply[:RPC_HEADER_SIZE])
    payload = raw_reply[RPC_HEADER_SIZE:]
    if (
        reply_version == RPC_VERSION
        and reply_id == expected_id
        and reply_type == expected_type
        and reply_size == len(payload)
    ):
        return payload
    return None


def _parse_process_list_chunk(reply_data: bytes) -> list[tuple[int, int, str]]:
    if len(reply_data) < 4:
        return []
    count = struct.unpack("<I", reply_data[:4])[0]
    body = reply_data[4:]
    entries: list[tuple[int, int, str]] = []
    for idx in range(count):
        chunk = body[idx * 0x14 : (idx + 1) * 0x14]
        if len(chunk) < 0x14:
            break
        proc_id, title_id, raw_name = struct.unpack("<IQ8s", chunk)
        name = raw_name.rstrip(b"\x00").decode("ascii", errors="replace")
        entries.append((proc_id, title_id, name))
    return entries


def _parse_perf_stats(reply_data: bytes) -> dict[str, float | int] | None:
    if len(reply_data) < 0x54:
        return None
    values = struct.unpack("<10dI", reply_data[:0x54])
    return dict(zip(PERF_STATS_FIELDS, values))


class AzaharRpcClient:
    """Minimal UDP client for Azahar's built-in scripting RPC server."""

    def __init__(self, host: str = "127.0.0.1", port: int = AZAHAR_RPC_PORT, timeout: float = 1.5, retries: int = 2) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout
        self.retries = retries
        self.max_data_size = 32 * 1024
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        for opt in (socket.SO_SNDBUF, socket.SO_RCVBUF):
            if self.sock.getsockopt(socket.SOL_SOCKET, opt) < MAX_PACKET_SIZE * 2:
                self.sock.setsockopt(socket.SOL_SOCKET, opt, MAX_PACKET_SIZE * 2)

    def close(self) -> None:
        self.sock.close()

    def _request(self, req_type: int, data: bytes, timeout: float | None = None, resend: bool = True) -> bytes:
        wait = self.timeout if timeout is None else timeout
        attempts = (self.retries + 1) if resend else 1
        header, req_id = _build_header(req_type, len(data))
        self.sock.settimeout(wait)
        for _ in range(attempts):
            try:
                self.sock.sendto(header + data, (self.host, self.port))
            except (ConnectionResetError, ConnectionRefusedError):
                # Windows can report the previous datagram's ICMP port-unreachable
                # on the next send while Azahar is still binding its RPC socket.
                continue
            try:
                while True:
                    raw_reply = self.sock.recv(MAX_PACKET_SIZE)
                    payload = _parse_header(raw_reply, req_id, req_type)
                    if payload is not None:
                        return payload
            except (TimeoutError, ConnectionResetError, ConnectionRefusedError):
                # A cold Azahar start can reset the first UDP request before the
                # RPC server is listening; retry within the caller's timeout.
                continue
        raise TimeoutError(f"Azahar RPC timeout on request type {req_type}")

    def process_list(self) -> dict[int, tuple[int, str]]:
        processes: dict[int, tuple[int, str]] = {}
        offset = 0
        while True:
            reply = self._request(REQ_PROCESS_LIST, struct.pack("<II", offset, 0x7FFFFFFF))
            entries = _parse_process_list_chunk(reply)
            if not entries:
                break
            offset += len(entries)
            for pid, tid, name in entries:
                processes[pid] = (tid, name)
        return processes

    def perf_stats(self, reset: bool = True) -> dict[str, float | int] | None:
        reply = self._request(REQ_GET_PERF_STATS, struct.pack("<II", 1 if reset else 0, 0))
        return _parse_perf_stats(reply)

    def screenshot(self, res_scale: int = 0, secondary_window: bool = False) -> bytes:
        flags = 1 if secondary_window else 0
        reply = self._request(REQ_TAKE_SCREENSHOT, struct.pack("<II", res_scale, flags), timeout=15.0, resend=False)
        if len(reply) < 16:
            raise RuntimeError("Empty screenshot reply from Azahar RPC.")
        result, _width, _height, total_size = struct.unpack("<IIII", reply[:16])
        if result != 0:
            raise RuntimeError(f"Azahar screenshot RPC returned error code {result}.")
        chunks: list[bytes] = []
        received = 0
        while received < total_size:
            chunk_size = min(total_size - received, self.max_data_size)
            part = self._request(REQ_READ_SCREENSHOT, struct.pack("<II", received, chunk_size))
            if not part:
                raise RuntimeError("Truncated screenshot chunk from Azahar RPC.")
            chunks.append(part)
            received += len(part)
        png_bytes = b"".join(chunks)
        if not png_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError("Screenshot payload is not a valid PNG image.")
        return png_bytes


def patch_qt_config_text(text: str, section_overrides: dict[str, dict[str, str]]) -> str:
    """Update Qt INI values and their \\default=false flags while preserving @ByteArray/@Variant lines."""
    newline = "\r\n" if "\r\n" in text else "\n"
    lines = text.splitlines()
    out_lines: list[str] = []
    current_section: str | None = None
    seen_sections: set[str] = set()
    written_keys: dict[str, set[str]] = {}

    def flush_missing(section: str | None) -> None:
        if section is None or section not in section_overrides:
            return
        done = written_keys.setdefault(section, set())
        for key, value in section_overrides[section].items():
            if f"{key}\\default" not in done:
                out_lines.append(f"{key}\\default=false")
                done.add(f"{key}\\default")
            if key not in done:
                out_lines.append(f"{key}={value}")
                done.add(key)

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            flush_missing(current_section)
            current_section = stripped[1:-1]
            seen_sections.add(current_section)
            out_lines.append(line)
            continue

        if current_section in section_overrides and "=" in line:
            lhs, _rhs = line.split("=", 1)
            overrides = section_overrides[current_section]
            if lhs.endswith("\\default"):
                base_key = lhs[: -len("\\default")]
                if base_key in overrides:
                    out_lines.append(f"{base_key}\\default=false")
                    written_keys.setdefault(current_section, set()).add(lhs)
                    continue
            elif lhs in overrides:
                out_lines.append(f"{lhs}={overrides[lhs]}")
                written_keys.setdefault(current_section, set()).add(lhs)
                continue

        out_lines.append(line)

    flush_missing(current_section)

    for section, overrides in section_overrides.items():
        if section in seen_sections:
            continue
        if out_lines and out_lines[-1] != "":
            out_lines.append("")
        out_lines.append(f"[{section}]")
        for key, value in overrides.items():
            out_lines.append(f"{key}\\default=false")
            out_lines.append(f"{key}={value}")

    return newline.join(out_lines) + newline


def _paeth_predictor(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa = abs(p - a)
    pb = abs(p - b)
    pc = abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def inspect_png(png_bytes: bytes) -> dict[str, Any]:
    """Decode PNG scanlines with stdlib zlib and confirm top/bottom halves contain non-blank content."""
    if not png_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("Invalid PNG signature.")
    pos = 8
    width = height = bit_depth = color_type = 0
    idat_parts: list[bytes] = []
    palette: list[tuple[int, int, int]] = []
    while pos + 8 <= len(png_bytes):
        length = struct.unpack(">I", png_bytes[pos : pos + 4])[0]
        ctype = png_bytes[pos + 4 : pos + 8]
        chunk_data = png_bytes[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            width, height, bit_depth, color_type, comp, filt, interlace = struct.unpack(">IIBBBBB", chunk_data)
            if comp != 0 or filt != 0 or interlace != 0:
                raise ValueError(f"Unsupported PNG compression/filter/interlace: {comp}/{filt}/{interlace}")
            if color_type in (2, 6) and bit_depth != 8:
                raise ValueError(f"Unsupported RGB/RGBA bit_depth={bit_depth}")
            if color_type == 3 and bit_depth not in (1, 2, 4, 8):
                raise ValueError(f"Unsupported indexed PNG bit_depth={bit_depth}")
            if color_type not in (2, 3, 6):
                raise ValueError(f"Unsupported PNG color_type={color_type}")
        elif ctype == b"PLTE":
            palette = [(chunk_data[i], chunk_data[i + 1], chunk_data[i + 2]) for i in range(0, len(chunk_data) - 2, 3)]
        elif ctype == b"IDAT":
            idat_parts.append(chunk_data)
        elif ctype == b"IEND":
            break

    if width <= 0 or height <= 0 or not idat_parts:
        raise ValueError("PNG missing valid IHDR or IDAT data.")
    if color_type == 3 and not palette:
        raise ValueError("Indexed PNG missing PLTE palette chunk.")

    if color_type == 3:
        bpp = 1
        stride = (width * bit_depth + 7) // 8
    else:
        bpp = 3 if color_type == 2 else 4
        stride = width * bpp

    raw = zlib.decompress(b"".join(idat_parts))
    expected_len = height * (1 + stride)
    if len(raw) != expected_len:
        raise ValueError(f"Unexpected decompressed PNG size: {len(raw)} != {expected_len}")

    prev = bytearray(stride)
    top_colors: set[tuple[int, int, int]] = set()
    bottom_colors: set[tuple[int, int, int]] = set()
    mid_y = height // 2

    for y in range(height):
        row_start = y * (1 + stride)
        ftype = raw[row_start]
        src = raw[row_start + 1 : row_start + 1 + stride]
        curr = bytearray(stride)
        for i in range(stride):
            a = curr[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            x = src[i]
            if ftype == 0:
                curr[i] = x
            elif ftype == 1:
                curr[i] = (x + a) & 0xFF
            elif ftype == 2:
                curr[i] = (x + b) & 0xFF
            elif ftype == 3:
                curr[i] = (x + ((a + b) >> 1)) & 0xFF
            elif ftype == 4:
                curr[i] = (x + _paeth_predictor(a, b, c)) & 0xFF
            else:
                raise ValueError(f"Unsupported PNG scanline filter type: {ftype}")
        prev = curr
        if y % 2 == 0:
            target = top_colors if y < mid_y else bottom_colors
            if len(target) < 64:
                if color_type == 3:
                    px_per_byte = 8 // bit_depth
                    mask = (1 << bit_depth) - 1
                    for x_px in range(0, width, 4):
                        byte_val = curr[x_px // px_per_byte]
                        shift = 8 - bit_depth * ((x_px % px_per_byte) + 1)
                        idx = (byte_val >> shift) & mask
                        if idx < len(palette):
                            target.add(palette[idx])
                else:
                    for x_px in range(0, width, 4):
                        off = x_px * bpp
                        target.add((curr[off], curr[off + 1], curr[off + 2]))

    return {
        "width": width,
        "height": height,
        "top_distinct_colors": len(top_colors),
        "bottom_distinct_colors": len(bottom_colors),
    }


def parse_azahar_log(log_text: str) -> dict[str, Any]:
    """Extract renderer, hardware profile line, parity hash, audio state, and frame telemetry from azahar_log.txt."""
    renderer: str | None = None
    gl_version: str | None = None
    hw_line: str | None = None
    parity: dict[str, Any] | None = None
    audio: dict[str, Any] | None = None
    audio_romfs: dict[str, Any] | None = None
    mem: dict[str, Any] | None = None
    frame: dict[str, Any] | None = None

    for line in log_text.splitlines():
        if "GL_RENDERER:" in line:
            renderer = line.split("GL_RENDERER:", 1)[1].strip()
        elif "GL_VERSION:" in line:
            gl_version = line.split("GL_VERSION:", 1)[1].strip()
        elif "[MIN3DSTRY] HW:" in line:
            hw_line = line.split("[MIN3DSTRY] HW:", 1)[1].strip()
        elif "[MIN3DSTRY] PARITY " in line:
            parity = parse_parity_debug_line(line)
        elif "[MIN3DSTRY] MEM " in line:
            mem_match = MEM_RE.search(line)
            if mem_match:
                mg = mem_match.groupdict()
                mem = {
                    "app_mib": int(mg["app_mib"]),
                    "reserve_mib": int(mg["reserve_mib"]),
                    "budget": int(mg["budget"]),
                    "used": int(mg["used"]),
                    "peak": int(mg["peak"]),
                    "remaining": int(mg["remaining"]),
                    "world_used": int(mg["world_used"]),
                    "world_limit": int(mg["world_limit"]),
                    "texture_used": int(mg["texture_used"]),
                    "texture_limit": int(mg["texture_limit"]),
                    "transient_used": int(mg["transient_used"]),
                    "transient_limit": int(mg["transient_limit"]),
                    "failures": int(mg["failures"]),
                    "status": mg["status"],
                }
        elif "[MIN3DSTRY] AUDIO_ROMFS " in line:
            romfs_match = AUDIO_ROMFS_RE.search(line)
            if romfs_match:
                rg = romfs_match.groupdict()
                audio_romfs = {
                    "status": rg["status"],
                    "clips": int(rg["clips"]),
                    "bytes": int(rg["bytes"]),
                    "ui_clip": int(rg["ui"]),
                }
        else:
            audio_match = AUDIO_RE.search(line)
            if audio_match:
                ag = audio_match.groupdict()
                audio = {
                    "backend": ag["backend"],
                    "state": ag["state"],
                    "rc": ag["rc"].lower(),
                    "rate": int(ag["rate"]),
                    "voices": int(ag["voices"]),
                    "max_voices": int(ag["max_voices"]),
                    "clips": int(ag["clips"]),
                }
                continue
            match = FRAME_RE.search(line)
            if match:
                g = match.groupdict()
                frame = {
                    "tick": int(g["tick"]),
                    "paused": int(g["paused"]),
                    "cam_x": float(g["cam_x"]),
                    "cam_y": float(g["cam_y"]),
                    "rects": int(g["rects"]),
                    "lines": int(g["lines"]),
                    "triangles": int(g["triangles"]),
                    "circles": int(g["circles"]),
                    "culled": int(g["cull"]),
                    "state_switches": int(g["sw"]),
                    "budget_dropped": int(g["drop"]),
                    "cpu_pct": float(g["cpu"]),
                    "gpu_pct": float(g["gpu"]),
                    "cmdbuf_pct": float(g["cmdbuf"]),
                }

    return {
        "renderer": renderer,
        "gl_version": gl_version,
        "hw_line": hw_line,
        "parity": parity,
        "audio": audio,
        "audio_romfs": audio_romfs,
        "mem": mem,
        "frame": frame,
    }


def _cygpath(flag: str, path_str: str) -> str:
    proc = subprocess.run(["cygpath", flag, path_str], check=True, text=True, capture_output=True)
    return proc.stdout.strip()


def resolve_azahar_bin(explicit: str | None) -> str:
    for candidate in (explicit, os.environ.get("AZAHAR_BIN"), shutil.which("azahar"), shutil.which("azahar.exe")):
        if candidate:
            return candidate
    raise FileNotFoundError("Azahar executable not found on PATH or via --azahar / AZAHAR_BIN.")


def to_azahar_cli_path(path: Path, azahar_bin: str) -> str:
    resolved = str(path.resolve())
    if sys.platform in ("cygwin", "msys") or (os.name == "posix" and azahar_bin.lower().endswith(".exe")):
        return _cygpath("-w", resolved)
    return resolved


def resolve_azahar_paths() -> tuple[Path, Path]:
    appdata = os.environ.get("APPDATA")
    if appdata:
        base = Path(_cygpath("-u", appdata) if sys.platform in ("cygwin", "msys") else appdata) / "Azahar"
        return base / "config" / "qt-config.ini", base / "log" / "azahar_log.txt"

    cfg_root = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "azahar-emu"
    data_root = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share")) / "azahar-emu"
    return cfg_root / "qt-config.ini", data_root / "log" / "azahar_log.txt"


def run_model_check(
    model: str,
    rom_path: Path,
    azahar_bin: str,
    config_path: Path,
    log_path: Path,
    out_dir: Path,
    timeout: float,
) -> dict[str, Any]:
    is_new_3ds = "true" if model == "new3ds" else "false"
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
            "is_new_3ds": is_new_3ds,
        },
    }

    config_path.parent.mkdir(parents=True, exist_ok=True)
    had_config = config_path.exists()
    original_config = config_path.read_text(encoding="utf-8") if had_config else ""
    config_path.write_text(patch_qt_config_text(original_config, overrides), encoding="utf-8")

    if log_path.exists():
        log_path.write_text("", encoding="utf-8")

    cli_rom = to_azahar_cli_path(rom_path, azahar_bin)
    proc = subprocess.Popen([azahar_bin, cli_rom], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    rpc = AzaharRpcClient(timeout=1.5, retries=2)
    try:
        deadline = time.monotonic() + timeout
        processes: dict[int, tuple[int, str]] = {}
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError(f"Azahar exited early with status {proc.returncode} during {model} check.")
            try:
                processes = rpc.process_list()
                if processes:
                    break
            except TimeoutError:
                time.sleep(0.3)

        if not processes:
            raise TimeoutError(f"Timed out waiting for Azahar RPC process_list() in {model} mode.")

        log_text = ""
        parsed: dict[str, Any] = {}
        while time.monotonic() < deadline:
            if log_path.exists():
                log_text = log_path.read_text(encoding="utf-8", errors="replace")
                parsed = parse_azahar_log(log_text)
                if parsed.get("hw_line") and parsed.get("parity") and parsed.get("audio") and parsed.get("mem") and parsed.get("frame"):
                    break
            time.sleep(0.25)

        if not parsed.get("hw_line") or not parsed.get("parity") or not parsed.get("audio") or not parsed.get("mem") or not parsed.get("frame"):
            raise RuntimeError(f"Missing [MIN3DSTRY] HW/PARITY/AUDIO/MEM/FRAME telemetry in {log_path} for {model}.")

        rpc.perf_stats(reset=True)
        time.sleep(1.5)
        perf = rpc.perf_stats(reset=True)
        if not perf:
            raise RuntimeError(f"Failed to read Azahar perf_stats for {model}.")

        png_bytes = rpc.screenshot()
        out_dir.mkdir(parents=True, exist_ok=True)
        png_path = out_dir / f"{model}.png"
        png_path.write_bytes(png_bytes)
        png_info = inspect_png(png_bytes)

        hw_line = str(parsed["hw_line"])
        parity = parsed["parity"]
        audio = parsed["audio"]
        mem = parsed["mem"]
        frame = parsed["frame"]
        fixture_path = Path("tests/parity/starter_copper_defense_v1.json")
        if fixture_path.is_file():
            ref = json.loads(fixture_path.read_text(encoding="utf-8"))
            ref_final = ref["checkpoints"][-1]
            for key in ("scenario_id", "total_ticks", "trace_hash"):
                if parity.get(key) != ref.get(key):
                    raise AssertionError(
                        f"Parity mismatch on {model} for {key}: expected {ref.get(key)!r}, got {parity.get(key)!r}"
                    )
            for key in ("core_copper", "items_delivered", "enemies_killed", "core_hp"):
                if parity.get(key) != ref_final.get(key):
                    raise AssertionError(
                        f"Parity final snapshot mismatch on {model} for {key}: "
                        f"expected {ref_final.get(key)!r}, got {parity.get(key)!r}"
                    )

        if model == "new3ds" and ("N3DS" not in hw_line or "804 MHz" not in hw_line):
            raise AssertionError(f"Unexpected new3ds hw_line: {hw_line}")
        if model == "old3ds" and ("O3DS" not in hw_line or "268 MHz" not in hw_line):
            raise AssertionError(f"Unexpected old3ds hw_line: {hw_line}")
        if (
            audio["backend"] != "ndsp"
            or audio["state"] not in ("ready", "fallback_no_dsp")
            or audio["clips"] < 1
            or audio["max_voices"] <= 0
        ):
            raise AssertionError(f"Unexpected audio telemetry for {model}: {audio}")
        audio_romfs = parsed.get("audio_romfs")
        if audio_romfs is not None and (
            audio_romfs["status"] != "ok"
            or audio_romfs["clips"] < 1
            or audio_romfs["bytes"] <= 0
            or audio_romfs["ui_clip"] < 0
        ):
            raise AssertionError(f"Unexpected RomFS audio telemetry for {model}: {audio_romfs}")
        if (
            mem["status"] != "ok"
            or mem["failures"] != 0
            or mem["used"] > mem["budget"]
            or mem["peak"] > mem["budget"]
            or mem["remaining"] != mem["budget"] - mem["used"]
            or mem["world_used"] > mem["world_limit"]
            or mem["texture_used"] > mem["texture_limit"]
            or mem["transient_used"] > mem["transient_limit"]
        ):
            raise AssertionError(f"Unexpected memory telemetry for {model}: {mem}")
        if frame["state_switches"] > 1 or frame["budget_dropped"] != 0 or frame["rects"] <= 0 or frame["circles"] <= 0:
            raise AssertionError(f"Unexpected frame telemetry for {model}: {frame}")
        if png_info["top_distinct_colors"] < 2 or png_info["bottom_distinct_colors"] < 2:
            raise AssertionError(f"Screenshot appears blank for {model}: {png_info}")
        if perf["game_fps"] <= 0 and perf["system_fps"] <= 0:
            raise AssertionError(f"Non-positive FPS for {model}: {perf}")

        return {
            "model": model,
            "is_new_3ds_setting": is_new_3ds == "true",
            "processes": {str(pid): {"title_id": tid, "name": name} for pid, (tid, name) in processes.items()},
            "renderer": parsed.get("renderer"),
            "gl_version": parsed.get("gl_version"),
            "hw_line": hw_line,
            "parity": parity,
            "audio": audio,
            "audio_romfs": audio_romfs,
            "mem": mem,
            "frame": frame,
            "perf": {
                "system_fps": round(float(perf["system_fps"]), 2),
                "game_fps": round(float(perf["game_fps"]), 2),
                "emulation_speed_pct": round(float(perf["emulation_speed"]) * 100.0, 2),
                "time_gpu_ms": round(float(perf["time_gpu"]) * 1000.0, 3),
            },
            "screenshot": {
                "path": str(png_path),
                **png_info,
            },
        }
    finally:
        rpc.close()
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        time.sleep(0.5)
        if had_config:
            config_path.write_text(original_config, encoding="utf-8")
        elif config_path.exists():
            config_path.unlink()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", default="build/min3dstry.3dsx")
    parser.add_argument("--models", default="new3ds,old3ds")
    parser.add_argument("--out-dir", default="build/emu-verify")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--azahar", default=None)
    args = parser.parse_args()

    rom_path = Path(args.rom)
    if not rom_path.is_file():
        print(f"verify_azahar: ROM not found: {rom_path}", file=sys.stderr)
        return 2

    azahar_bin = resolve_azahar_bin(args.azahar)
    config_path, log_path = resolve_azahar_paths()
    out_dir = Path(args.out_dir)

    models = [m.strip().lower() for m in args.models.split(",") if m.strip()]
    for m in models:
        if m not in ("new3ds", "old3ds"):
            print(f"verify_azahar: unsupported model '{m}' (use new3ds,old3ds)", file=sys.stderr)
            return 2

    results = []
    for model in models:
        res = run_model_check(model, rom_path, azahar_bin, config_path, log_path, out_dir, args.timeout)
        results.append(res)
        print(
            f"[PASS] {model}: {res['hw_line']} | "
            f"Parity={res['parity']['trace_hash']} | "
            f"Audio={res['audio']['state']} | "
            f"R:{res['frame']['rects']} C:{res['frame']['circles']} Sw:{res['frame']['state_switches']} | "
            f"FPS={res['perf']['game_fps']} ({res['perf']['emulation_speed_pct']}%) | "
            f"PNG={res['screenshot']['path']}"
        )

    report_path = out_dir / "report.json"
    report_path.write_text(json.dumps({"rom": str(rom_path), "results": results}, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote verification report to {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
