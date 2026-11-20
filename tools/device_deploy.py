#!/usr/bin/env python3
"""Repeatable 3DS hardware deployment (3dslink / SD staging) and log capture tool."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_azahar import parse_azahar_log  # noqa: E402


def _cygpath_w(path_str: str) -> str:
    proc = subprocess.run(["cygpath", "-w", path_str], check=True, text=True, capture_output=True)
    return proc.stdout.strip()


def resolve_3dslink(explicit: str | None = None) -> str:
    """Locate 3dslink executable from argument, env var, PATH, or devkitPro default."""
    candidates = [
        explicit,
        os.environ.get("THREEDSLINK_BIN"),
        shutil.which("3dslink"),
        shutil.which("3dslink.exe"),
        "/opt/devkitpro/tools/bin/3dslink",
        "/opt/devkitpro/tools/bin/3dslink.exe",
    ]
    for cand in candidates:
        if not cand:
            continue
        p = Path(cand)
        if p.is_file() or shutil.which(cand):
            return cand
    raise FileNotFoundError(
        "3dslink executable not found (install 3dslink via devkitPro or set THREEDSLINK_BIN)."
    )


def build_3dslink_command(
    rom_path: Path,
    ip: str | None,
    *,
    retries: int = 3,
    stream_logs: bool = False,
    tool_bin: str = "3dslink",
) -> list[str]:
    """Build deterministic 3dslink command line for Wi-Fi Homebrew Launcher transfer."""
    target_ip = (ip or os.environ.get("M3DS_DEVICE_IP") or "").strip()
    if not target_ip:
        raise ValueError("Target 3DS IP missing: pass --ip <addr> or set M3DS_DEVICE_IP.")

    resolved_rom = str(rom_path.resolve())
    if sys.platform in ("cygwin", "msys") and tool_bin.lower().endswith(".exe"):
        resolved_rom = _cygpath_w(resolved_rom)

    cmd = [tool_bin, "-a", target_ip, "-r", str(max(0, retries))]
    if stream_logs:
        cmd.append("-s")
    cmd.append(resolved_rom)
    if stream_logs:
        cmd.append("m3ds-stream-logs")
    return cmd


def _sha256_file(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as fh:
        while True:
            chunk = fh.read(65536)
            if not chunk:
                break
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def stage_to_sd(
    rom_path: Path,
    sd_root: Path,
    *,
    smdh_path: Path | None = None,
    app_dir: str = "3ds/min3dstry",
    commit_sha: str | None = None,
) -> dict[str, Any]:
    """Copy .3dsx (and optional .smdh) to SD staging root and emit SHA-256 manifest."""
    if not rom_path.is_file():
        raise FileNotFoundError(f"ROM not found: {rom_path}")

    target_dir = sd_root / app_dir
    target_dir.mkdir(parents=True, exist_ok=True)

    staged_files: list[dict[str, Any]] = []
    dest_rom = target_dir / rom_path.name
    shutil.copy2(rom_path, dest_rom)
    rom_sha, rom_bytes = _sha256_file(dest_rom)
    staged_files.append({"name": dest_rom.name, "bytes": rom_bytes, "sha256": rom_sha})

    if smdh_path is not None and smdh_path.is_file():
        dest_smdh = target_dir / smdh_path.name
        shutil.copy2(smdh_path, dest_smdh)
        smdh_sha, smdh_bytes = _sha256_file(dest_smdh)
        staged_files.append({"name": dest_smdh.name, "bytes": smdh_bytes, "sha256": smdh_sha})

    manifest = {
        "app_dir": app_dir,
        "commit_sha": commit_sha or "unknown",
        "files": staged_files,
    }
    manifest_path = target_dir / "min3dstry.sha256.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return {"manifest_path": str(manifest_path), **manifest}


def parse_device_log(log_text: str) -> dict[str, Any]:
    """Extract [MIN3DSTRY] HW, PARITY, AUDIO, UI, and FRAME records from streamed device output."""
    parsed = parse_azahar_log(log_text)
    return {
        "hw_line": parsed.get("hw_line"),
        "parity": parsed.get("parity"),
        "audio": parsed.get("audio"),
        "ui": parsed.get("ui"),
        "frame": parsed.get("frame"),
    }


def format_provenance_markdown(
    parsed: dict[str, Any],
    *,
    commit_sha: str = "unknown",
    target_label: str = "3DS Hardware",
) -> str:
    """Format parsed hardware/parity/audio/ui/frame telemetry as a concise Markdown verification record."""
    lines = [
        f"- **Target:** {target_label} (`commit {commit_sha}`)",
        f"- **Hardware probe:** `{parsed.get('hw_line') or 'missing'}`",
    ]
    parity = parsed.get("parity")
    if isinstance(parity, dict):
        lines.append(
            "- **Parity trace:** "
            f"`{parity.get('scenario_id')}` `hash={parity.get('trace_hash')}` "
            f"(copper={parity.get('core_copper')}, delivered={parity.get('items_delivered')}, "
            f"killed={parity.get('enemies_killed')}, hp={parity.get('core_hp')})"
        )
    audio = parsed.get("audio")
    if isinstance(audio, dict):
        lines.append(
            "- **Audio:** "
            f"backend={audio.get('backend')} state={audio.get('state')} "
            f"rc={audio.get('rc')} rate={audio.get('rate')} "
            f"voices={audio.get('voices')}/{audio.get('max_voices')} clips={audio.get('clips')}"
        )
    ui = parsed.get("ui")
    if isinstance(ui, dict):
        lines.append(
            "- **Touch UI:** "
            f"block={ui.get('block')} rot={ui.get('rot')} mode={ui.get('mode')} "
            f"cursor=({ui.get('cursor_x')},{ui.get('cursor_y')}) buttons={ui.get('buttons')}"
        )
    frame = parsed.get("frame")
    if isinstance(frame, dict):
        lines.append(
            "- **Frame telemetry:** "
            f"tick={frame.get('tick')} R:{frame.get('rects')} L:{frame.get('lines')} "
            f"T:{frame.get('triangles')} C:{frame.get('circles')} Cull:{frame.get('culled')} "
            f"Sw:{frame.get('state_switches')} Drop:{frame.get('budget_dropped')} "
            f"CPU={frame.get('cpu_pct')}% GPU={frame.get('gpu_pct')}% CmdBuf={frame.get('cmdbuf_pct')}%"
        )
    return "\n".join(lines) + "\n"


def capture_log_tcp(
    host: str,
    port: int,
    out_path: Path,
    *,
    timeout: float = 30.0,
    max_lines: int = 200,
    ready_event: threading.Event | None = None,
    port_holder: list[int] | None = None,
) -> tuple[int, list[str]]:
    """Listen on TCP (host, port) for one streamed session and write captured lines to out_path."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    captured: list[str] = []
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.settimeout(timeout)
        srv.bind((host, port))
        srv.listen(1)
        bound_port = int(srv.getsockname()[1])
        if port_holder is not None:
            port_holder.append(bound_port)
        if ready_event is not None:
            ready_event.set()

        conn, _addr = srv.accept()
        with conn:
            conn.settimeout(timeout)
            with conn.makefile("r", encoding="utf-8", errors="replace") as reader:
                for line in reader:
                    captured.append(line.rstrip("\r\n"))
                    if len(captured) >= max_lines:
                        break
    finally:
        srv.close()

    out_path.write_text("\n".join(captured) + ("\n" if captured else ""), encoding="utf-8")
    return bound_port, captured


def cmd_deploy(args: argparse.Namespace) -> int:
    rom_path = Path(args.rom)
    if not rom_path.is_file():
        print(f"device deploy: ROM not found: {rom_path} (run ./dev.sh build first).", file=sys.stderr)
        return 2
    tool_bin = "3dslink" if args.dry_run and not args.threed_slink else resolve_3dslink(args.threed_slink)
    try:
        cmd = build_3dslink_command(
            rom_path,
            args.ip,
            retries=args.retries,
            stream_logs=args.stream_logs,
            tool_bin=tool_bin,
        )
    except ValueError as exc:
        print(f"device deploy: {exc}", file=sys.stderr)
        return 2

    if args.dry_run:
        print(json.dumps({"dry_run": True, "command": cmd}, indent=2))
        return 0

    proc = subprocess.run(cmd, text=True, capture_output=bool(args.log_out))
    if args.log_out:
        combined = (proc.stdout or "") + (proc.stderr or "")
        out_path = Path(args.log_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(combined, encoding="utf-8")
        if combined:
            print(combined, end="")
    return proc.returncode


def cmd_sd_stage(args: argparse.Namespace) -> int:
    rom_path = Path(args.rom)
    smdh_path = Path(args.smdh) if args.smdh else None
    try:
        manifest = stage_to_sd(
            rom_path,
            Path(args.sd_root),
            smdh_path=smdh_path,
            app_dir=args.app_dir,
            commit_sha=args.commit,
        )
    except FileNotFoundError as exc:
        print(f"device sd-stage: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(manifest, indent=2))
    return 0


def cmd_capture_log(args: argparse.Namespace) -> int:
    port, lines = capture_log_tcp(
        args.host,
        args.port,
        Path(args.out),
        timeout=args.timeout,
        max_lines=args.max_lines,
    )
    print(f"Captured {len(lines)} line(s) on port {port} -> {args.out}")
    return 0


def cmd_parse_log(args: argparse.Namespace) -> int:
    log_text = Path(args.input).read_text(encoding="utf-8", errors="replace")
    parsed = parse_device_log(log_text)
    if args.format == "json":
        print(json.dumps(parsed, indent=2))
    else:
        print(format_provenance_markdown(parsed, commit_sha=args.commit, target_label=args.target), end="")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="subcmd", required=True)

    p_dep = sub.add_parser("deploy", help="Send .3dsx to 3DS over Wi-Fi via 3dslink.")
    p_dep.add_argument("--rom", default="build/min3dstry.3dsx")
    p_dep.add_argument("--ip", default=None, help="3DS IP address (or set M3DS_DEVICE_IP).")
    p_dep.add_argument("--retries", type=int, default=3)
    p_dep.add_argument("--stream-logs", action="store_true")
    p_dep.add_argument("--log-out", default=None)
    p_dep.add_argument("--3dslink", dest="threed_slink", default=None)
    p_dep.add_argument("--dry-run", action="store_true")
    p_dep.set_defaults(func=cmd_deploy)

    p_sd = sub.add_parser("sd-stage", help="Stage .3dsx/.smdh and SHA-256 manifest to SD directory.")
    p_sd.add_argument("--rom", default="build/min3dstry.3dsx")
    p_sd.add_argument("--smdh", default="build/min3dstry.smdh")
    p_sd.add_argument("--sd-root", required=True)
    p_sd.add_argument("--app-dir", default="3ds/min3dstry")
    p_sd.add_argument("--commit", default=None)
    p_sd.set_defaults(func=cmd_sd_stage)

    p_cap = sub.add_parser("capture-log", help="Listen on TCP port to capture streamed device logs.")
    p_cap.add_argument("--host", default="0.0.0.0")
    p_cap.add_argument("--port", type=int, default=4000)
    p_cap.add_argument("--out", default="build/device-logs/capture.log")
    p_cap.add_argument("--timeout", type=float, default=30.0)
    p_cap.add_argument("--max-lines", type=int, default=200)
    p_cap.set_defaults(func=cmd_capture_log)

    p_parse = sub.add_parser("parse-log", help="Parse captured device log into JSON or Markdown.")
    p_parse.add_argument("--input", required=True)
    p_parse.add_argument("--commit", default="unknown")
    p_parse.add_argument("--target", default="3DS Hardware")
    p_parse.add_argument("--format", choices=["markdown", "json"], default="markdown")
    p_parse.set_defaults(func=cmd_parse_log)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
