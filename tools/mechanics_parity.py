#!/usr/bin/env python3
"""Validate docs/mechanics-parity.json against repo evidence and render docs/mechanics-gauge.svg."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape

REQUIRED_STATUSES = (
    "missing",
    "harness-only",
    "probe-arm11",
    "partial-arm11",
    "live-arm11",
    "complete",
)

ALLOWED_MODES = (
    "offline",
    "multiplayer",
    "probe",
    "harness",
)


def load_and_validate_mechanics(ledger_path: Path, repo_root: Path) -> dict[str, Any]:
    """Load docs/mechanics-parity.json and verify weights, modes, statuses, and evidence files."""
    resolved_root = repo_root.resolve()
    raw = json.loads(ledger_path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError("Mechanics ledger root must be a JSON object.")

    status_weights = raw.get("status_weights")
    if not isinstance(status_weights, dict):
        raise ValueError("status_weights must be an object.")
    for status in REQUIRED_STATUSES:
        val = status_weights.get(status)
        if not isinstance(val, (int, float)) or isinstance(val, bool) or val < 0.0 or val > 1.0:
            raise ValueError(f"Invalid status weight for '{status}': {val!r}")

    features = raw.get("features")
    if not isinstance(features, list) or not features:
        raise ValueError("features must be a non-empty list.")

    seen_ids: set[str] = set()
    total_weight = 0
    for item in features:
        if not isinstance(item, dict):
            raise ValueError("Each feature must be an object.")
        fid = str(item.get("id") or "").strip()
        domain = str(item.get("domain") or "").strip()
        name = str(item.get("name") or "").strip()
        status = str(item.get("status") or "").strip()
        notes = str(item.get("notes") or "").strip()
        weight = item.get("weight")
        modes = item.get("modes")
        evidence = item.get("evidence")

        if not fid or fid in seen_ids:
            raise ValueError(f"Duplicate or empty feature id: {fid!r}")
        seen_ids.add(fid)
        if not domain or not name or not notes:
            raise ValueError(f"Feature '{fid}' missing domain, name, or notes.")
        if not isinstance(weight, int) or isinstance(weight, bool) or weight <= 0:
            raise ValueError(f"Feature '{fid}' weight must be a positive integer, got {weight!r}")
        if status not in status_weights:
            raise ValueError(f"Feature '{fid}' has unknown status '{status}'.")
        if not isinstance(modes, list):
            raise ValueError(f"Feature '{fid}' modes must be a list.")
        seen_modes: set[str] = set()
        for mode in modes:
            mode_str = str(mode)
            if mode_str not in ALLOWED_MODES or mode_str in seen_modes:
                raise ValueError(f"Feature '{fid}' has invalid or duplicate mode: {mode!r}")
            seen_modes.add(mode_str)
        if not isinstance(evidence, list):
            raise ValueError(f"Feature '{fid}' evidence must be a list.")

        if status == "missing":
            if modes or evidence:
                raise ValueError(f"Missing feature '{fid}' must not claim modes or evidence.")
        else:
            if not evidence:
                raise ValueError(f"Feature '{fid}' at status '{status}' requires at least one evidence file.")
            for rel_path in evidence:
                p = (resolved_root / str(rel_path)).resolve()
                try:
                    p.relative_to(resolved_root)
                except ValueError as exc:
                    raise ValueError(f"Feature '{fid}' evidence path escapes repo root: {rel_path}") from exc
                if not p.is_file() or p.stat().st_size == 0:
                    raise ValueError(f"Feature '{fid}' missing or empty evidence file: {rel_path}")

        total_weight += weight

    if total_weight != 100:
        raise ValueError(f"Feature weights must sum to 100, got {total_weight}.")

    return raw


def compute_mechanics_parity(ledger: dict[str, Any]) -> dict[str, Any]:
    """Compute weighted overall and per-domain granular Mindustry v160.8 mechanics parity."""
    status_weights: dict[str, float] = {str(k): float(v) for k, v in ledger["status_weights"].items()}
    features: list[dict[str, Any]] = ledger["features"]

    status_counts = {s: 0 for s in REQUIRED_STATUSES}
    status_earned = {s: 0.0 for s in REQUIRED_STATUSES}
    status_weight_points = {s: 0 for s in REQUIRED_STATUSES}
    domains_map: dict[str, dict[str, Any]] = {}
    features_summary: list[dict[str, Any]] = []
    total_weight = 0
    earned_total = 0.0

    for item in features:
        weight = int(item["weight"])
        status = str(item["status"])
        domain_name = str(item["domain"])
        mult = status_weights[status]
        earned = weight * mult
        missing = weight - earned
        modes = [str(m) for m in item.get("modes", [])]
        evidence_list = [str(p) for p in item.get("evidence", [])]
        primary_doc = next((p for p in evidence_list if p.endswith(".md")), evidence_list[0] if evidence_list else None)

        total_weight += weight
        earned_total += earned
        status_counts[status] += 1
        status_earned[status] += earned
        status_weight_points[status] += weight

        features_summary.append(
            {
                "id": str(item["id"]),
                "name": str(item["name"]),
                "domain": domain_name,
                "status": status,
                "weight": weight,
                "earned": round(earned, 2),
                "missing": round(missing, 2),
                "modes": modes,
                "notes": str(item["notes"]),
                "issue": item.get("issue"),
                "evidence_count": len(evidence_list),
                "primary_doc": primary_doc,
            }
        )

        bucket = domains_map.setdefault(
            domain_name,
            {
                "name": domain_name,
                "earned": 0.0,
                "weight": 0,
                "count": 0,
                "status_counts": {s: 0 for s in REQUIRED_STATUSES},
            },
        )
        bucket["earned"] += earned
        bucket["weight"] += weight
        bucket["count"] += 1
        bucket["status_counts"][status] += 1

    domains: list[dict[str, Any]] = []
    for bucket in domains_map.values():
        w = bucket["weight"]
        pct = round((bucket["earned"] / w) * 100.0, 1) if w else 0.0
        domains.append(
            {
                "name": bucket["name"],
                "earned": round(bucket["earned"], 2),
                "weight": w,
                "count": bucket["count"],
                "percent": pct,
                "missing": round(w - bucket["earned"], 2),
                "status_counts": bucket["status_counts"],
            }
        )

    percent = round((earned_total / total_weight) * 100.0, 1) if total_weight else 0.0
    return {
        "earned_points": round(earned_total, 2),
        "missing_points": round(total_weight - earned_total, 2),
        "total_points": total_weight,
        "percent": percent,
        "feature_count": len(features),
        "status_weights": status_weights,
        "status_counts": status_counts,
        "status_earned_points": {s: round(v, 2) for s, v in status_earned.items()},
        "status_weight_points": status_weight_points,
        "domains": domains,
        "features": features_summary,
    }


def render_mechanics_svg(summary: dict[str, Any]) -> str:
    """Render a deterministic, timestamp-free SVG granular mechanics parity gauge."""
    width = 760
    height = 314
    pct = float(summary["percent"])
    earned = float(summary["earned_points"])
    total = int(summary["total_points"])

    bar_x = 24
    bar_w = width - 48
    segment_specs = (
        ("complete", "#3fb950"),
        ("live-arm11", "#f5b841"),
        ("partial-arm11", "#58a6ff"),
        ("probe-arm11", "#e07a3f"),
        ("harness-only", "#bc8cff"),
    )
    se = summary["status_earned_points"]
    sw = summary["status_weight_points"]
    sc = summary["status_counts"]

    pills_text = (
        f"Complete: {sc['complete']}  ·  Live ARM11: {sc['live-arm11']}  ·  "
        f"Partial: {sc['partial-arm11']}  ·  Probe: {sc['probe-arm11']}  ·  "
        f"Harness: {sc['harness-only']}  ·  Missing: {sc['missing']}"
    )
    live_partial_wt = sw["complete"] + sw["live-arm11"] + sw["partial-arm11"]
    probe_harness_wt = sw["probe-arm11"] + sw["harness-only"]
    missing_wt = sw["missing"]
    weight_text = (
        f"Weight breakdown:  Live/Partial {live_partial_wt}/{total} pts  ·  "
        f"Probe/Harness {probe_harness_wt}/{total} pts  ·  Missing {missing_wt}/{total} pts"
    )

    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}" role="img" '
        f'aria-label="Min3DStry Mechanics &amp; Feature Parity {pct:.1f}%">',
        "  <defs>",
        f'    <clipPath id="mechBarClip"><rect x="{bar_x}" y="66" width="{bar_w}" height="14" rx="7"/></clipPath>',
        "  </defs>",
        f'  <rect x="0.5" y="0.5" width="{width - 1}" height="{height - 1}" rx="8" fill="#0d1117" stroke="#30363d"/>',
        '  <text x="24" y="34" fill="#e6edf3" font-family="Segoe UI, Ubuntu, Arial, sans-serif" '
        'font-size="16" font-weight="700">Min3DStry Mechanics &amp; Feature Parity Gauge</text>',
        f'  <text x="{width - 24}" y="34" text-anchor="end" fill="#58a6ff" '
        'font-family="Consolas, Liberation Mono, monospace" font-size="20" font-weight="700">'
        f"{pct:.1f}%</text>",
        '  <text x="24" y="54" fill="#8b949e" font-family="Segoe UI, Ubuntu, Arial, sans-serif" '
        f'font-size="12">Granular Mindustry v160.8 parity across {summary["feature_count"]} mechanics '
        f"({earned:.2f} / {total} pts · verified against docs/mechanics-parity.json)</text>",
        f'  <rect x="{bar_x}" y="66" width="{bar_w}" height="14" rx="7" fill="#161b22" stroke="#30363d"/>',
        '  <g clip-path="url(#mechBarClip)">',
    ]

    offset = 0.0
    for key, color in segment_specs:
        pts = float(se.get(key, 0.0))
        if pts <= 0.0 or total <= 0:
            continue
        seg_w = max(0.0, min(float(bar_w) - offset, round((pts / total) * bar_w, 1)))
        if seg_w > 0.0:
            lines.append(
                f'    <rect x="{bar_x + offset:.1f}" y="66" width="{seg_w:.1f}" height="14" fill="{color}"/>'
            )
            offset += seg_w

    lines.extend([
        "  </g>",
        '  <text x="24" y="98" fill="#8b949e" font-family="Consolas, Liberation Mono, monospace" '
        f'font-size="11">{escape(pills_text)}</text>',
        '  <text x="24" y="114" fill="#8b949e" font-family="Consolas, Liberation Mono, monospace" '
        f'font-size="11">{escape(weight_text)}</text>',
        '  <line x1="24" y1="126" x2="736" y2="126" stroke="#21262d" stroke-width="1"/>',
    ])

    domains = summary["domains"]
    col_w = 344
    gap_x = 24
    start_y = 146
    row_h = 40
    mini_w = 344

    for idx, dom in enumerate(domains):
        col = idx % 2
        row = idx // 2
        x = 24 + col * (col_w + gap_x)
        y = start_y + row * row_h
        d_pct = float(dom["percent"])
        d_fill = max(0.0, min(float(mini_w), round((d_pct / 100.0) * mini_w, 1)))
        if d_pct >= 70.0:
            bar_color = "#3fb950"
        elif d_pct >= 40.0:
            bar_color = "#f5b841"
        elif d_pct > 0.0:
            bar_color = "#e07a3f"
        else:
            bar_color = "#30363d"

        lines.append(
            f'  <text x="{x}" y="{y}" fill="#e6edf3" font-family="Segoe UI, Ubuntu, Arial, sans-serif" '
            f'font-size="12" font-weight="600">{escape(str(dom["name"]))}</text>'
        )
        lines.append(
            f'  <text x="{x + col_w}" y="{y}" text-anchor="end" fill="#8b949e" '
            f'font-family="Consolas, Liberation Mono, monospace" font-size="11">'
            f'{dom["earned"]:.2f}/{dom["weight"]} ({d_pct:.1f}%)</text>'
        )
        lines.append(
            f'  <rect x="{x}" y="{y + 6}" width="{mini_w}" height="8" rx="4" '
            f'fill="#161b22" stroke="#30363d" stroke-width="0.75"/>'
        )
        if d_fill > 0.0:
            lines.append(
                f'  <rect x="{x}" y="{y + 6}" width="{d_fill}" height="8" rx="4" fill="{bar_color}"/>'
            )

    lines.append("</svg>")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", default="docs/mechanics-parity.json")
    parser.add_argument("--svg", default="docs/mechanics-gauge.svg")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--write-svg", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    repo_root = Path(args.repo_root).resolve()
    ledger_path = Path(args.ledger)
    svg_path = Path(args.svg)

    ledger = load_and_validate_mechanics(ledger_path, repo_root)
    summary = compute_mechanics_parity(ledger)
    svg_text = render_mechanics_svg(summary)

    if args.write_svg:
        svg_path.parent.mkdir(parents=True, exist_ok=True)
        svg_path.write_bytes(svg_text.encode("utf-8"))

    if args.check:
        if not svg_path.is_file():
            print(
                f"mechanics_parity: missing {svg_path}; run python3 tools/mechanics_parity.py --write-svg",
                file=sys.stderr,
            )
            return 1
        actual_svg = svg_path.read_text(encoding="utf-8")
        if actual_svg != svg_text:
            print(
                f"mechanics_parity: {svg_path} is out of date; run python3 tools/mechanics_parity.py --write-svg",
                file=sys.stderr,
            )
            return 1
        print(
            f"[PASS] mechanics_parity: {summary['percent']:.1f}% "
            f"({summary['earned_points']:.2f}/{summary['total_points']} pts across {summary['feature_count']} mechanics)"
        )

    if args.json:
        print(json.dumps(summary, indent=2))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
