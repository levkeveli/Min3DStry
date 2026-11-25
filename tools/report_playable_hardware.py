#!/usr/bin/env python3
"""Check continuous high-load frame timing from a physical New 3DS SD capture."""

import argparse
import json
import math
from pathlib import Path
import re

from hw_evidence import PROFILES, sha256_file
from verify_upstream_singleplayer import SP_LOADED_RE, verify_playable_sd_capture


def report(log, commit, min_seconds, min_enemies, min_turrets, min_items):
    provenance, capture, windows = verify_playable_sd_capture(log)
    if commit != provenance["commit"]:
        raise AssertionError("The capture commit differs from the requested ROM revision")
    app_capacity = PROFILES["new3ds"]["app_mem_mib"] * (1 << 20)
    if int(provenance["app_mem"]) != app_capacity:
        raise AssertionError(f"Expected the physical New 3DS {app_capacity}-byte application region")

    loads = list(SP_LOADED_RE.finditer(capture))
    if len(loads) != 1 or loads[0]["source"] != "save":
        raise AssertionError("A benchmark run must load a single input save")

    memory = re.findall(r"^UPSTREAM_PLAYABLE_APP_MEM window=(\d+) used=(\d+)$", capture, re.MULTILINE)
    if [int(window) for window, _ in memory] != [row["window"] for row in windows]:
        raise AssertionError("Each frame window needs one application-region reading")
    if any("end_ns" not in row or row["end_ns"] < row["elapsed_ns"] for row in windows):
        raise AssertionError("Each frame window needs a valid end timestamp")

    spans = []
    current = []
    for row, (_, used) in zip(windows, memory, strict=True):
        row["app_used"] = int(used)
        if not 0 < row["app_used"] <= app_capacity:
            raise AssertionError(f"Window {row['window']} did not report in-budget application memory")

        if current and current[-1]["end_ns"] != row["end_ns"] - row["elapsed_ns"]:
            current = []
        under_load = (row["enemies"] >= min_enemies and row["shooting_turrets"] >= min_turrets
                      and row["turret_projectiles"] > 0 and row["belt_items"] >= min_items)
        if not under_load or row["fps"] < 24:
            current = []
            continue
        if not current:
            spans.append(current)
        current.append(row)

    best = max(spans, key=lambda span: sum(row["elapsed_ns"] for row in span), default=[])
    duration = sum(row["elapsed_ns"] for row in best) / 1e9
    return {
        **provenance,
        "map": loads[0]["map"],
        "criteria": {"fps": 24, "seconds": min_seconds, "enemies": min_enemies,
                     "firing_turrets": min_turrets, "belt_items": min_items, "turret_projectiles": 1},
        "windows": windows,
        "continuous_span": {"seconds": duration, "window_ids": [row["window"] for row in best],
                            "min_fps": min((row["fps"] for row in best), default=None),
                            "max_p95_ns": max((row["p95_ns"] for row in best), default=None)},
        "pass": duration >= min_seconds,
    }


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--input", type=Path, required=True, help="Captured benchmark.log from the physical console")
    cli.add_argument("--rom", type=Path, required=True, help="Exact .3dsx used on the console")
    cli.add_argument("--save", type=Path, required=True, help="Copy of the input .msav taken before the run")
    cli.add_argument("--commit", required=True, help="Full git SHA of the clean ROM build")
    cli.add_argument("--model", required=True, choices=("New 3DS", "New 3DS XL", "New 2DS XL"))
    cli.add_argument("--min-seconds", type=float, required=True, help="Minimum continuous seconds under load")
    cli.add_argument("--min-enemies", type=int, required=True)
    cli.add_argument("--min-turrets", type=int, required=True)
    cli.add_argument("--min-items", type=int, required=True)
    cli.add_argument("--output", type=Path, required=True)
    args = cli.parse_args()
    if not math.isfinite(args.min_seconds) or min(args.min_seconds, args.min_enemies, args.min_turrets, args.min_items) <= 0:
        cli.error("Expected positive workload and duration thresholds")
    try:
        result = report(args.input.read_text(encoding="utf-8"), args.commit, args.min_seconds,
                        args.min_enemies, args.min_turrets, args.min_items)
        result.update(model=args.model, capture_sha256=sha256_file(args.input),
                      rom_sha256=sha256_file(args.rom), input_save_sha256=sha256_file(args.save))
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, AssertionError) as error:
        cli.exit(1, f"Playable hardware report: {error}\n")
    seconds = result["continuous_span"]["seconds"]
    print(f"Playable hardware report: {seconds:.2f}s of continuous >=24 FPS under load")
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
