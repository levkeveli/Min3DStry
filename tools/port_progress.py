#!/usr/bin/env python3
"""Validate docs/port-capabilities.json against repo evidence and render docs/progress-gauge.svg."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape

REQUIRED_STAGES = (
    "planned",
    "experimental",
    "host-tested",
    "emulator-tested",
    "hardware-verified",
    "complete",
)


def load_and_validate_capabilities(ledger_path: Path, repo_root: Path) -> dict[str, Any]:
    """Load docs/port-capabilities.json and verify weights sum to 100 and all evidence files exist."""
    raw = json.loads(ledger_path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError("Capability ledger root must be a JSON object.")

    stage_weights = raw.get("stage_weights")
    if not isinstance(stage_weights, dict):
        raise ValueError("stage_weights must be an object.")
    for stage in REQUIRED_STAGES:
        val = stage_weights.get(stage)
        if not isinstance(val, (int, float)) or val < 0.0 or val > 1.0:
            raise ValueError(f"Invalid stage weight for '{stage}': {val!r}")

    caps = raw.get("capabilities")
    if not isinstance(caps, list) or not caps:
        raise ValueError("capabilities must be a non-empty list.")

    seen_ids: set[str] = set()
    total_weight = 0
    for item in caps:
        if not isinstance(item, dict):
            raise ValueError("Each capability must be an object.")
        cid = str(item.get("id") or "").strip()
        cat = str(item.get("category") or "").strip()
        name = str(item.get("name") or "").strip()
        stage = str(item.get("stage") or "").strip()
        weight = item.get("weight")
        evidence = item.get("evidence")

        if not cid or cid in seen_ids:
            raise ValueError(f"Duplicate or empty capability id: {cid!r}")
        seen_ids.add(cid)
        if not cat or not name:
            raise ValueError(f"Capability '{cid}' missing category or name.")
        if not isinstance(weight, int) or isinstance(weight, bool) or weight <= 0:
            raise ValueError(f"Capability '{cid}' weight must be a positive integer, got {weight!r}")
        if stage not in stage_weights:
            raise ValueError(f"Capability '{cid}' has unknown stage '{stage}'.")
        if not isinstance(evidence, list):
            raise ValueError(f"Capability '{cid}' evidence must be a list.")

        if stage != "planned":
            if not evidence:
                raise ValueError(f"Capability '{cid}' at stage '{stage}' requires at least one evidence file.")
            for rel_path in evidence:
                p = (repo_root / str(rel_path)).resolve()
                if not p.is_file() or p.stat().st_size == 0:
                    raise ValueError(f"Capability '{cid}' missing or empty evidence file: {rel_path}")

        total_weight += weight

    if total_weight != 100:
        raise ValueError(f"Capability weights must sum to 100, got {total_weight}.")

    return raw


def compute_progress(ledger: dict[str, Any]) -> dict[str, Any]:
    """Compute weighted overall and per-category port completion progress."""
    stage_weights: dict[str, float] = {str(k): float(v) for k, v in ledger["stage_weights"].items()}
    caps: list[dict[str, Any]] = ledger["capabilities"]
    hw_mult = stage_weights["hardware-verified"]
    comp_mult = stage_weights["complete"]

    stage_counts = {s: 0 for s in REQUIRED_STAGES}
    categories_map: dict[str, dict[str, Any]] = {}
    capabilities_summary: list[dict[str, Any]] = []
    total_weight = 0
    earned_total = 0.0
    hw_gap_total = 0.0
    rel_gap_total = 0.0

    for item in caps:
        weight = int(item["weight"])
        stage = str(item["stage"])
        cat_name = str(item["category"])
        mult = stage_weights[stage]
        earned = weight * mult
        missing = weight - earned
        hw_gap = weight * max(0.0, hw_mult - mult)
        rel_gap = weight * max(0.0, comp_mult - max(mult, hw_mult))
        evidence_list = [str(p) for p in item.get("evidence", [])]
        primary_doc = next((p for p in evidence_list if p.endswith(".md")), evidence_list[0] if evidence_list else None)

        total_weight += weight
        earned_total += earned
        hw_gap_total += hw_gap
        rel_gap_total += rel_gap
        stage_counts[stage] += 1

        capabilities_summary.append(
            {
                "id": str(item["id"]),
                "name": str(item["name"]),
                "category": cat_name,
                "stage": stage,
                "weight": weight,
                "earned": round(earned, 2),
                "missing": round(missing, 2),
                "hw_gap": round(hw_gap, 2),
                "release_gap": round(rel_gap, 2),
                "issue": item.get("issue"),
                "evidence_count": len(evidence_list),
                "primary_doc": primary_doc,
            }
        )

        bucket = categories_map.setdefault(
            cat_name,
            {"name": cat_name, "earned": 0.0, "weight": 0, "count": 0, "hw_gap": 0.0, "release_gap": 0.0},
        )
        bucket["earned"] += earned
        bucket["weight"] += weight
        bucket["count"] += 1
        bucket["hw_gap"] += hw_gap
        bucket["release_gap"] += rel_gap

    categories: list[dict[str, Any]] = []
    for bucket in categories_map.values():
        w = bucket["weight"]
        e = round(bucket["earned"], 2)
        pct = round((bucket["earned"] / w) * 100.0, 1) if w else 0.0
        categories.append(
            {
                "name": bucket["name"],
                "earned": e,
                "weight": w,
                "count": bucket["count"],
                "percent": pct,
                "missing": round(w - bucket["earned"], 2),
                "hw_gap": round(bucket["hw_gap"], 2),
                "release_gap": round(bucket["release_gap"], 2),
            }
        )

    percent = round((earned_total / total_weight) * 100.0, 1) if total_weight else 0.0
    return {
        "earned_points": round(earned_total, 2),
        "missing_points": round(total_weight - earned_total, 2),
        "hardware_gap_points": round(hw_gap_total, 2),
        "release_gap_points": round(rel_gap_total, 2),
        "total_points": total_weight,
        "percent": percent,
        "capability_count": len(caps),
        "stage_weights": stage_weights,
        "stage_counts": stage_counts,
        "categories": categories,
        "capabilities": capabilities_summary,
    }


def render_progress_svg(summary: dict[str, Any]) -> str:
    """Render a deterministic, timestamp-free SVG port completion gauge."""
    width = 760
    height = 308
    pct = float(summary["percent"])
    earned = float(summary["earned_points"])
    total = int(summary["total_points"])
    missing = float(summary.get("missing_points", max(0.0, total - earned)))
    hw_pts = float(summary.get("hardware_gap_points", 0.0))
    rel_pts = float(summary.get("release_gap_points", max(0.0, missing - hw_pts)))
    hw_target_pct = round(((earned + hw_pts) / total) * 100.0, 1) if total else 0.0

    bar_x = 24
    bar_w = width - 48
    fill_w = max(0.0, min(float(bar_w), round((pct / 100.0) * bar_w, 1)))
    hw_w = max(0.0, min(float(bar_w) - fill_w, round((hw_pts / total) * bar_w, 1))) if total else 0.0
    rel_w = max(0.0, min(float(bar_w) - fill_w - hw_w, round((rel_pts / total) * bar_w, 1))) if total else 0.0

    sc = summary["stage_counts"]
    pills_text = (
        f"Complete: {sc['complete']}  ·  Hardware: {sc['hardware-verified']}  ·  "
        f"Emulator: {sc['emulator-tested']}  ·  Host: {sc['host-tested']}  ·  "
        f"Exp: {sc['experimental']}  ·  Planned: {sc['planned']}"
    )
    gap_text = (
        f"Remaining: -{missing:.2f} pts  (HW gate ->{hw_target_pct:.1f}%: +{hw_pts:.2f} pts  ·  "
        f"Release parity ->100.0%: +{rel_pts:.2f} pts)"
    )

    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}" role="img" aria-label="Min3DStry Port Completion {pct:.1f}%">',
        '  <defs>',
        f'    <clipPath id="barClip"><rect x="{bar_x}" y="66" width="{bar_w}" height="14" rx="7"/></clipPath>',
        '  </defs>',
        f'  <rect x="0.5" y="0.5" width="{width - 1}" height="{height - 1}" rx="8" fill="#0d1117" stroke="#30363d"/>',
        '  <text x="24" y="34" fill="#e6edf3" font-family="Segoe UI, Ubuntu, Arial, sans-serif" '
        'font-size="16" font-weight="700">Min3DStry Full-Port Completion Gauge</text>',
        f'  <text x="{width - 24}" y="34" text-anchor="end" fill="#f5b841" '
        'font-family="Consolas, Liberation Mono, monospace" font-size="20" font-weight="700">'
        f'{pct:.1f}%</text>',
        '  <text x="24" y="54" fill="#8b949e" font-family="Segoe UI, Ubuntu, Arial, sans-serif" '
        f'font-size="12">Evidence-weighted across {summary["capability_count"]} capabilities '
        f'({earned:.2f} / {total} pts · verified against docs/port-capabilities.json)</text>',
        f'  <rect x="{bar_x}" y="66" width="{bar_w}" height="14" rx="7" fill="#161b22" stroke="#30363d"/>',
        '  <g clip-path="url(#barClip)">',
        f'    <rect x="{bar_x}" y="66" width="{fill_w}" height="14" fill="#f5b841"/>',
    ]
    if hw_w > 0.0:
        lines.append(
            f'    <rect x="{bar_x + fill_w:.1f}" y="66" width="{hw_w}" height="14" fill="#d29922" fill-opacity="0.32"/>'
        )
    if rel_w > 0.0:
        lines.append(
            f'    <rect x="{bar_x + fill_w + hw_w:.1f}" y="66" width="{rel_w}" height="14" fill="#58a6ff" fill-opacity="0.28"/>'
        )
    lines.extend([
        '  </g>',
        '  <text x="24" y="98" fill="#8b949e" font-family="Consolas, Liberation Mono, monospace" '
        f'font-size="11">{escape(pills_text)}</text>',
        '  <text x="24" y="114" fill="#8b949e" font-family="Consolas, Liberation Mono, monospace" '
        f'font-size="11">{escape(gap_text)}</text>',
        '  <line x1="24" y1="126" x2="736" y2="126" stroke="#21262d" stroke-width="1"/>',
    ])

    categories = summary["categories"]
    col_w = 344
    gap_x = 24
    start_y = 146
    row_h = 38
    mini_w = 344

    for idx, cat in enumerate(categories):
        col = idx % 2
        row = idx // 2
        x = 24 + col * (col_w + gap_x)
        y = start_y + row * row_h
        c_pct = float(cat["percent"])
        c_fill = max(0.0, min(float(mini_w), round((c_pct / 100.0) * mini_w, 1)))
        bar_color = "#3fb950" if c_pct >= 50.0 else ("#f5b841" if c_pct > 0.0 else "#30363d")

        lines.append(
            f'  <text x="{x}" y="{y}" fill="#e6edf3" font-family="Segoe UI, Ubuntu, Arial, sans-serif" '
            f'font-size="12" font-weight="600">{escape(str(cat["name"]))}</text>'
        )
        lines.append(
            f'  <text x="{x + col_w}" y="{y}" text-anchor="end" fill="#8b949e" '
            f'font-family="Consolas, Liberation Mono, monospace" font-size="11">'
            f'{cat["earned"]:.2f}/{cat["weight"]} ({c_pct:.1f}%)</text>'
        )
        lines.append(
            f'  <rect x="{x}" y="{y + 6}" width="{mini_w}" height="8" rx="4" '
            f'fill="#161b22" stroke="#30363d" stroke-width="0.75"/>'
        )
        if c_fill > 0.0:
            lines.append(
                f'  <rect x="{x}" y="{y + 6}" width="{c_fill}" height="8" rx="4" fill="{bar_color}"/>'
            )

    lines.append("</svg>")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", default="docs/port-capabilities.json")
    parser.add_argument("--svg", default="docs/progress-gauge.svg")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--write-svg", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    repo_root = Path(args.repo_root).resolve()
    ledger_path = Path(args.ledger)
    svg_path = Path(args.svg)

    ledger = load_and_validate_capabilities(ledger_path, repo_root)
    summary = compute_progress(ledger)
    svg_text = render_progress_svg(summary)

    if args.write_svg:
        svg_path.parent.mkdir(parents=True, exist_ok=True)
        svg_path.write_bytes(svg_text.encode("utf-8"))

    if args.check:
        if not svg_path.is_file():
            print(f"port_progress: missing {svg_path}; run python3 tools/port_progress.py --write-svg", file=sys.stderr)
            return 1
        actual_svg = svg_path.read_text(encoding="utf-8")
        if actual_svg != svg_text:
            print(
                f"port_progress: {svg_path} is out of date; run python3 tools/port_progress.py --write-svg",
                file=sys.stderr,
            )
            return 1
        print(
            f"[PASS] port_progress: {summary['percent']:.1f}% "
            f"({summary['earned_points']:.2f}/{summary['total_points']} pts across {summary['capability_count']} capabilities)"
        )

    if args.json:
        print(json.dumps(summary, indent=2))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
