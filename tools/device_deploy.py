#!/usr/bin/env python3
"""Repeatable 3DS hardware deployment (3dslink / SD staging) and log capture tool."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import queue
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_azahar import parse_azahar_log  # noqa: E402
import hw_evidence  # noqa: E402

LEGACY_MANAGED_HEAP_BYTES = 24 * 1024 * 1024

UPSTREAM_MEMORY_RE = re.compile(
    r"UPSTREAM_MEMORY\s+appHeap=(?P<app_heap>\d+)\s+"
    r"linearHeap=(?P<linear_heap>\d+)\s+stack=(?P<stack>\d+)"
)
UPSTREAM_MANAGED_LIMIT_RE = re.compile(r"UPSTREAM_MEMORY\s+managedLimit=(?P<limit>\d+)")
BENCH_LINE_RE = re.compile(r"\[MIN3DSTRY\] BENCH\s+(?P<body>.+)$")
APPMEM_HW_RE = re.compile(r"AppMem:(?P<mib>\d+)\s*MiB")

HARDWARE_BUDGET_PROFILES: dict[str, dict[str, Any]] = {
    "old3ds": {
        "label": "Original 3DS/2DS (APPMEMTYPE 0)",
        "expected_tag": "O3DS",
        "app_mem_limit_mib": 64,
    },
    "new3ds": {
        "label": "New 3DS/2DS (APPMEMTYPE 6)",
        "expected_tag": "N3DS",
        "app_mem_limit_mib": 124,
    },
}


def _cygpath_w(path_str: str) -> str:
    proc = subprocess.run(["cygpath", "-w", path_str], check=True, text=True, capture_output=True)
    return proc.stdout.strip()


def resolve_3dslink(explicit: str | None = None) -> str:
    """Locate 3dslink from argument, env, PATH, or a local devkitPro install."""
    candidates = [
        explicit,
        os.environ.get("THREEDSLINK_BIN"),
        os.environ.get("THREESLINK_BIN"),
        shutil.which("3dslink"),
        shutil.which("3dslink.exe"),
        "/opt/devkitpro/tools/bin/3dslink",
        "/opt/devkitpro/tools/bin/3dslink.exe",
    ]
    roots = []
    if os.environ.get("DEVKITPRO"):
        roots.append(Path(os.environ["DEVKITPRO"]))
    roots.append(Path.home() / "devkitPro")
    roots.append(Path("C:/devkitPro"))
    for root in roots:
        candidates.append(str(root / "tools" / "bin" / "3dslink.exe"))
        candidates.append(str(root / "tools" / "bin" / "3dslink"))
    for cand in candidates:
        if not cand:
            continue
        p = Path(cand)
        if p.is_file() or shutil.which(cand):
            return cand
    raise FileNotFoundError(
        "3dslink executable not found (install 3dslink via devkitPro, pass --3dslink, "
        "or set THREEDSLINK_BIN/THREESLINK_BIN)."
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


def _parse_bench_line(body: str) -> dict[str, Any] | None:
    fields: dict[str, str] = {}
    for token in body.split():
        if "=" not in token:
            continue
        k, v = token.split("=", 1)
        fields[k] = v
    if "fps" not in fields:
        return None
    try:
        bench: dict[str, Any] = {"fps": float(fields["fps"])}
        if "p95_ms" in fields:
            bench["p95_ms"] = float(fields["p95_ms"])
        if "avg_ms" in fields:
            bench["avg_ms"] = float(fields["avg_ms"])
    except ValueError:
        return None
    if "scenario" in fields:
        bench["scenario"] = fields["scenario"]
    return bench


def parse_device_log(log_text: str) -> dict[str, Any]:
    """Extract [MIN3DSTRY] HW, PARITY, AUDIO, FRAME, MEM, BENCH, and UPSTREAM_MEMORY records."""
    parsed = parse_azahar_log(log_text)
    upstream_memory: dict[str, int] | None = None
    bench: dict[str, Any] | None = None

    for line in log_text.splitlines():
        um_match = UPSTREAM_MEMORY_RE.search(line)
        if um_match:
            upstream_memory = {
                "app_heap_bytes": int(um_match.group("app_heap")),
                "linear_heap_bytes": int(um_match.group("linear_heap")),
                "stack_bytes": int(um_match.group("stack")),
            }
            continue
        managed_match = UPSTREAM_MANAGED_LIMIT_RE.search(line)
        if managed_match and upstream_memory is not None:
            upstream_memory["managed_limit_bytes"] = int(managed_match.group("limit"))
            continue
        bench_match = BENCH_LINE_RE.search(line)
        if bench_match:
            parsed_bench = _parse_bench_line(bench_match.group("body"))
            if parsed_bench is not None:
                bench = parsed_bench

    hw_line = parsed.get("hw_line")
    mem = parsed.get("mem")
    app_mem_mib: int | None = None
    if isinstance(hw_line, str):
        app_match = APPMEM_HW_RE.search(hw_line)
        if app_match:
            app_mem_mib = int(app_match.group("mib"))
    if app_mem_mib is None and isinstance(mem, dict) and "app_mib" in mem:
        app_mem_mib = int(mem["app_mib"])

    return {
        "hw_line": hw_line,
        "parity": parsed.get("parity"),
        "audio": parsed.get("audio"),
        "frame": parsed.get("frame"),
        "mem": mem,
        "upstream_memory": upstream_memory,
        "bench": bench,
        "app_mem_mib": app_mem_mib,
    }


def format_provenance_markdown(
    parsed: dict[str, Any],
    *,
    commit_sha: str = "unknown",
    target_label: str = "3DS Hardware",
) -> str:
    """Format parsed hardware/parity/audio/frame/memory/bench telemetry as Markdown."""
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
    frame = parsed.get("frame")
    if isinstance(frame, dict):
        lines.append(
            "- **Frame telemetry:** "
            f"tick={frame.get('tick')} R:{frame.get('rects')} L:{frame.get('lines')} "
            f"T:{frame.get('triangles')} C:{frame.get('circles')} Cull:{frame.get('culled')} "
            f"Sw:{frame.get('state_switches')} Drop:{frame.get('budget_dropped')} "
            f"CPU={frame.get('cpu_pct')}% GPU={frame.get('gpu_pct')}% CmdBuf={frame.get('cmdbuf_pct')}%"
        )
    mem = parsed.get("mem")
    if isinstance(mem, dict):
        lines.append(
            "- **Arena memory:** "
            f"app={mem.get('app_mib')}MiB used={mem.get('used')}/{mem.get('budget')}B "
            f"peak={mem.get('peak')}B failures={mem.get('failures')} status={mem.get('status')}"
        )
    um = parsed.get("upstream_memory")
    if isinstance(um, dict):
        lines.append(
            "- **Upstream runtime heaps:** "
            f"appHeap={um.get('app_heap_bytes')}B linearHeap={um.get('linear_heap_bytes')}B "
            f"stack={um.get('stack_bytes')}B"
        )
    bench = parsed.get("bench")
    if isinstance(bench, dict):
        extras = []
        if "avg_ms" in bench:
            extras.append(f"avg={bench['avg_ms']:.2f}ms")
        if "p95_ms" in bench:
            extras.append(f"p95={bench['p95_ms']:.2f}ms")
        if "scenario" in bench:
            extras.append(f"scenario={bench['scenario']}")
        suffix = f" ({', '.join(extras)})" if extras else ""
        lines.append(f"- **Benchmark:** `fps={bench.get('fps'):.2f}`{suffix}")
    return "\n".join(lines) + "\n"


def evaluate_hardware_budget(
    parsed: dict[str, Any],
    *,
    profile: str = "old3ds",
    min_fps: float = 24.0,
    expected_hash: str | None = None,
    load_bytes: int | None = None,
) -> dict[str, Any]:
    """Evaluate captured hardware telemetry against Old/New 3DS FPS and memory budgets."""
    if profile not in HARDWARE_BUDGET_PROFILES:
        valid = ", ".join(sorted(HARDWARE_BUDGET_PROFILES))
        raise ValueError(f"Unsupported hardware profile '{profile}' (expected one of: {valid}).")
    if min_fps <= 0:
        raise ValueError("min_fps must be > 0.")
    if load_bytes is not None and load_bytes < 0:
        raise ValueError("load_bytes must be >= 0.")

    cfg = HARDWARE_BUDGET_PROFILES[profile]
    limit_mib = int(cfg["app_mem_limit_mib"])
    limit_bytes = limit_mib * 1024 * 1024
    expected_tag = str(cfg["expected_tag"])
    checks: dict[str, dict[str, Any]] = {}

    hw_line = parsed.get("hw_line")
    if isinstance(hw_line, str) and expected_tag in hw_line:
        checks["hw_profile"] = {
            "status": "pass",
            "detail": f"matched {expected_tag} in HW line",
        }
    else:
        checks["hw_profile"] = {
            "status": "fail",
            "detail": f"expected {expected_tag} in HW line, got {hw_line!r}",
        }

    mem = parsed.get("mem")
    if isinstance(mem, dict):
        mem_ok = (
            mem.get("status") == "ok"
            and mem.get("failures", 1) == 0
            and mem.get("used", 0) <= mem.get("budget", 0)
            and mem.get("peak", 0) <= mem.get("budget", 0)
        )
        checks["memory_budget"] = {
            "status": "pass" if mem_ok else "fail",
            "detail": (
                f"used={mem.get('used')}/{mem.get('budget')} peak={mem.get('peak')} "
                f"failures={mem.get('failures')} status={mem.get('status')}"
            ),
        }
    else:
        checks["memory_budget"] = {"status": "skipped", "detail": "no [MIN3DSTRY] MEM record"}

    um = parsed.get("upstream_memory")
    if isinstance(um, dict) or load_bytes is not None:
        app_heap = int(um["app_heap_bytes"]) if isinstance(um, dict) else None
        linear_heap = int(um["linear_heap_bytes"]) if isinstance(um, dict) else 0
        stack = int(um["stack_bytes"]) if isinstance(um, dict) else 0
        reserved = (load_bytes or 0) + linear_heap + stack
        actual_managed_limit = um.get("managed_limit_bytes") if isinstance(um, dict) else None
        managed_limit = actual_managed_limit if actual_managed_limit is not None else LEGACY_MANAGED_HEAP_BYTES
        app_heap_ok = app_heap is None or app_heap > 0
        if actual_managed_limit is not None:
            app_heap_ok = 0 < actual_managed_limit <= app_heap
        if load_bytes is not None:
            total = reserved + managed_limit
            footprint_ok = app_heap_ok and total <= limit_bytes
            managed_source = "reported" if actual_managed_limit is not None else "legacy estimate"
            detail = (
                f"reserved_with_managed_heap={total / (1024 * 1024):.2f} MiB "
                f"vs {limit_mib} MiB (managed={managed_limit} bytes, {managed_source}, available_app_heap={app_heap})"
            )
        else:
            subtotal = reserved + (actual_managed_limit or 0)
            footprint_ok = app_heap is not None and app_heap_ok and subtotal < limit_bytes
            detail = (
                f"known_reserved={subtotal / (1024 * 1024):.2f} MiB vs {limit_mib} MiB, "
                f"available_app_heap={app_heap}"
            )
        checks["upstream_memory_footprint"] = {
            "status": "pass" if footprint_ok else "fail",
            "available_heap_bytes": app_heap,
            "reserved_bytes": reserved,
            "managed_limit_bytes": actual_managed_limit,
            "limit_bytes": limit_bytes,
            "detail": detail,
        }
    else:
        checks["upstream_memory_footprint"] = {
            "status": "skipped",
            "detail": "no UPSTREAM_MEMORY record or --load-bytes",
        }

    bench = parsed.get("bench")
    if isinstance(bench, dict):
        fps = float(bench["fps"])
        max_frame_ms = (1000.0 / min_fps) + 0.05
        p95_ms = bench.get("p95_ms")
        fps_ok = fps >= min_fps and (p95_ms is None or float(p95_ms) <= max_frame_ms)
        detail = f"fps={fps:.2f} (min {min_fps:.2f})"
        if p95_ms is not None:
            detail += f", p95_ms={float(p95_ms):.2f} (limit {1000.0 / min_fps:.2f})"
        checks["frame_rate"] = {
            "status": "pass" if fps_ok else "fail",
            "detail": detail,
        }
    else:
        checks["frame_rate"] = {"status": "skipped", "detail": "no [MIN3DSTRY] BENCH record"}

    parity = parsed.get("parity")
    if expected_hash is not None:
        actual_hash = parity.get("trace_hash") if isinstance(parity, dict) else None
        hash_ok = isinstance(actual_hash, str) and actual_hash.lower() == expected_hash.lower()
        checks["parity_hash"] = {
            "status": "pass" if hash_ok else "fail",
            "detail": f"expected={expected_hash}, actual={actual_hash}",
        }
    elif isinstance(parity, dict):
        checks["parity_hash"] = {
            "status": "pass",
            "detail": f"hash={parity.get('trace_hash')} (ticks={parity.get('total_ticks')})",
        }
    else:
        checks["parity_hash"] = {"status": "skipped", "detail": "no [MIN3DSTRY] PARITY record"}

    statuses = [entry["status"] for entry in checks.values()]
    passed = ("fail" not in statuses) and ("pass" in statuses)
    return {
        "profile": profile,
        "profile_label": cfg["label"],
        "app_mem_limit_mib": limit_mib,
        "min_fps": min_fps,
        "passed": passed,
        "checks": checks,
    }


def format_benchmark_markdown(
    parsed: dict[str, Any],
    evaluation: dict[str, Any],
    *,
    commit_sha: str = "unknown",
    target_label: str = "3DS Hardware",
) -> str:
    """Format parsed hardware telemetry plus budget pass/fail table as Markdown."""
    verdict = "PASS" if evaluation.get("passed") else "FAIL"
    lines = [
        format_provenance_markdown(parsed, commit_sha=commit_sha, target_label=target_label).rstrip("\n"),
        f"- **Budget profile:** `{evaluation['profile']}` ({evaluation['profile_label']})",
        f"- **Overall verdict:** `{verdict}`",
        "",
        "| Check | Status | Detail |",
        "|---|---|---|",
    ]
    for name, check in (evaluation.get("checks") or {}).items():
        lines.append(f"| `{name}` | `{check['status'].upper()}` | {check['detail']} |")
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


def _pump_stream(stream: Any, lines: "queue.Queue[str | None]") -> None:
    try:
        for line in stream:
            lines.put(line)
    finally:
        lines.put(None)


def run_streaming(cmd: list[str], log_out: str | None = None,
                  max_seconds: float | None = None) -> int:
    """Mirror a command's merged output to stdout and log_out line by line.

    Unlike subprocess.run(capture_output=True), every received line is written
    and flushed immediately, so a bounded hardware session survives an
    interrupted server and is never lost to pipe buffering. A deliberate
    ``max_seconds`` termination returns 0; other exits propagate the child code.
    """
    out_path = Path(log_out) if log_out else None
    handle = None
    if out_path:
        out_path.parent.mkdir(parents=True, exist_ok=True)
        handle = out_path.open("w", encoding="utf-8", newline="")

    def emit(line: str) -> None:
        if handle:
            handle.write(line)
            handle.flush()
        sys.stdout.write(line)
        sys.stdout.flush()

    proc = subprocess.Popen(cmd, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, bufsize=1)
    assert proc.stdout is not None
    lines: "queue.Queue[str | None]" = queue.Queue()
    reader = threading.Thread(target=_pump_stream, args=(proc.stdout, lines),
                              daemon=True)
    reader.start()
    started = time.monotonic()
    terminated = False
    try:
        while True:
            remaining = None
            if max_seconds is not None:
                remaining = max_seconds - (time.monotonic() - started)
                if remaining <= 0:
                    proc.terminate()
                    terminated = True
                    break
            wait = 0.2 if remaining is None else min(0.2, max(0.0, remaining))
            try:
                line = lines.get(timeout=wait)
            except queue.Empty:
                if proc.poll() is not None:
                    break
                continue
            if line is None:
                break
            emit(line)
    finally:
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
        while True:
            try:
                pending = lines.get_nowait()
            except queue.Empty:
                break
            if pending:
                emit(pending)
        if proc.stdout is not None:
            proc.stdout.close()
        if handle:
            handle.close()
    if terminated:
        return 0
    return proc.returncode if proc.returncode is not None else 0


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

    if args.stream_logs or args.log_out or args.stream_seconds is not None:
        return run_streaming(cmd, args.log_out, args.stream_seconds)
    proc = subprocess.run(cmd, text=True)
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


def cmd_benchmark_report(args: argparse.Namespace) -> int:
    log_text = Path(args.input).read_text(encoding="utf-8", errors="replace")
    parsed = parse_device_log(log_text)
    try:
        evaluation = evaluate_hardware_budget(
            parsed,
            profile=args.profile,
            min_fps=args.min_fps,
            expected_hash=args.expected_hash,
            load_bytes=args.load_bytes,
        )
    except ValueError as exc:
        print(f"device benchmark-report: {exc}", file=sys.stderr)
        return 2
    if args.format == "json":
        print(json.dumps({"parsed": parsed, "evaluation": evaluation}, indent=2))
    else:
        print(
            format_benchmark_markdown(
                parsed,
                evaluation,
                commit_sha=args.commit,
                target_label=args.target,
            ),
            end="",
        )
    if args.strict and not evaluation["passed"]:
        return 1
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
    p_dep.add_argument(
        "--stream-seconds", type=float, default=None,
        help="Stop the log server after N seconds (bounded hardware runs).",
    )
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

    p_bench = sub.add_parser(
        "benchmark-report",
        help="Evaluate captured device logs against FPS, parity, and Old/New 3DS memory budgets.",
    )
    p_bench.add_argument("--input", required=True)
    p_bench.add_argument("--commit", default="unknown")
    p_bench.add_argument("--target", default="3DS Hardware")
    p_bench.add_argument("--profile", choices=sorted(HARDWARE_BUDGET_PROFILES), default="old3ds")
    p_bench.add_argument("--min-fps", type=float, default=24.0)
    p_bench.add_argument("--expected-hash", default=None)
    p_bench.add_argument("--load-bytes", type=int, default=None)
    p_bench.add_argument("--format", choices=["markdown", "json"], default="markdown")
    p_bench.add_argument("--strict", action="store_true")
    p_bench.set_defaults(func=cmd_benchmark_report)

    p_ev = sub.add_parser(
        "evidence",
        help="Verify a captured hardware log and emit checked Markdown/JSON evidence.",
    )
    p_ev.add_argument("--input", required=True)
    p_ev.add_argument("--rom", default=None)
    p_ev.add_argument("--commit", default="unknown")
    p_ev.add_argument("--target", default="3DS Hardware")
    p_ev.add_argument("--profile", choices=sorted(hw_evidence.PROFILES), default="new3ds")
    p_ev.add_argument("--scenario", choices=hw_evidence.SCENARIOS, default="sector170")
    p_ev.add_argument("--format", choices=["markdown", "json"], default="markdown")
    p_ev.add_argument("--out", default=None)
    p_ev.add_argument("--timestamp", default=None)
    p_ev.add_argument("--strict", action="store_true")
    p_ev.set_defaults(func=cmd_evidence)

    return parser


def cmd_evidence(args: argparse.Namespace) -> int:
    argv = [
        "--input",
        args.input,
        "--commit",
        args.commit,
        "--target",
        args.target,
        "--profile",
        args.profile,
        "--scenario",
        args.scenario,
        "--format",
        args.format,
    ]
    if args.strict:
        argv.append("--strict")
    if args.rom:
        argv.extend(["--rom", args.rom])
    if args.out:
        argv.extend(["--out", args.out])
    if args.timestamp:
        argv.extend(["--timestamp", args.timestamp])
    return hw_evidence.main(argv)


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
