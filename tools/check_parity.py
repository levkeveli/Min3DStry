#!/usr/bin/env python3
"""Compare compiled Min3DStry parity harness runs against a deterministic reference fixture."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

PARITY_LOG_RE = re.compile(
    r"\[MIN3DSTRY\] PARITY "
    r"scenario=(?P<scenario>\S+) "
    r"ticks=(?P<ticks>\d+) "
    r"hash=(?P<trace_hash>0x[0-9a-fA-F]+) "
    r"copper=(?P<core_copper>\d+) "
    r"delivered=(?P<items_delivered>\d+) "
    r"killed=(?P<enemies_killed>\d+) "
    r"hp=(?P<core_hp>\d+)"
)


def compare_parity_runs(reference: dict[str, Any], actual: dict[str, Any], label: str = "actual") -> list[str]:
    """Return human-readable mismatch descriptions, or [] if reference and actual match bit-for-bit."""
    errors: list[str] = []
    for key in ("scenario_id", "total_ticks", "trace_hash"):
        if reference.get(key) != actual.get(key):
            errors.append(f"{label}: {key} mismatch (expected {reference.get(key)!r}, got {actual.get(key)!r})")

    ref_ckpts = reference.get("checkpoints") or []
    act_ckpts = actual.get("checkpoints") or []
    if len(ref_ckpts) != len(act_ckpts):
        errors.append(f"{label}: checkpoint_count mismatch ({len(ref_ckpts)} != {len(act_ckpts)})")
        return errors

    for ref_c, act_c in zip(ref_ckpts, act_ckpts):
        tick = ref_c.get("tick")
        for field, expected_val in ref_c.items():
            actual_val = act_c.get(field)
            if actual_val != expected_val:
                errors.append(
                    f"{label} @ tick {tick}: field '{field}' mismatch "
                    f"(expected {expected_val!r}, got {actual_val!r})"
                )
    return errors


def parse_parity_debug_line(text: str) -> dict[str, Any] | None:
    """Parse a [MIN3DSTRY] PARITY debug telemetry line emitted by main_3ds.c."""
    match = PARITY_LOG_RE.search(text)
    if not match:
        return None
    g = match.groupdict()
    return {
        "scenario_id": g["scenario"],
        "total_ticks": int(g["ticks"]),
        "trace_hash": g["trace_hash"].lower(),
        "core_copper": int(g["core_copper"]),
        "items_delivered": int(g["items_delivered"]),
        "enemies_killed": int(g["enemies_killed"]),
        "core_hp": int(g["core_hp"]),
    }


def resolve_binary_path(raw: str) -> str:
    candidate = Path(raw)
    if candidate.is_file():
        return str(candidate)
    if os.name == "nt" or sys.platform in ("cygwin", "msys"):
        exe_candidate = Path(f"{raw}.exe")
        if exe_candidate.is_file():
            return str(exe_candidate)
    raise FileNotFoundError(f"Parity binary not found: {raw}")


def run_binary_dump(binary_path: str) -> dict[str, Any]:
    exe = resolve_binary_path(binary_path)
    proc = subprocess.run([exe, "--dump-json"], check=True, text=True, capture_output=True)
    return json.loads(proc.stdout)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", default="tests/parity/starter_copper_defense_v1.json")
    parser.add_argument("--binary", action="append", required=True, help="Path to compiled test_parity_harness")
    args = parser.parse_args()

    fixture_path = Path(args.fixture)
    reference = json.loads(fixture_path.read_text(encoding="utf-8"))

    all_errors: list[str] = []
    for bin_path in args.binary:
        actual = run_binary_dump(bin_path)
        errs = compare_parity_runs(reference, actual, label=bin_path)
        if errs:
            all_errors.extend(errs)
        else:
            print(
                f"[PASS] parity {bin_path}: {actual['scenario_id']} "
                f"ticks={actual['total_ticks']} trace_hash={actual['trace_hash']}"
            )

    if all_errors:
        for err in all_errors:
            print(f"[FAIL] {err}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
