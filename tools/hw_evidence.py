#!/usr/bin/env python3
"""Turn a captured [MIN3DSTRY] hardware log into checked, reproducible evidence.

The tool parses only the app's own records, verifies the canonical sector-170
values (or a reduced smoke set), optionally hashes the shipped .3dsx, and emits
a Markdown or JSON record. `--strict` makes it exit non-zero when any required
check fails, so CI or a maintainer can gate on real hardware output instead of
reading pasted logs by eye.

See docs/hardware/verification-runbook.md for the capture procedure.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

RECORD_RE = re.compile(r"\[MIN3DSTRY\]\s*(?P<rest>.*)$")
KV_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)=([^\s]+)")
HW_RE = re.compile(
    r"\((?P<tag>N3DS|O3DS)(?P<rest>[^)]*)\)"
)

PROFILES: dict[str, dict[str, Any]] = {
    "new3ds": {"tag": "N3DS", "app_mem_mib": 124},
    "old3ds": {"tag": "O3DS", "app_mem_mib": 64},
}

# Canonical sector-170 fixture (`tests/fixtures/glacier-v13.msav`) values.
CANONICAL = {
    "loader_tiles": 37500,
    "loader_buildings": 2,
    "alloc_hash": "783fbd80e415dcd7",
    "tile_state_hash": "e35397041df8e5fe",
    "tile_state_linked": 2,
    "save_hash": "3a375fd4f81c5680",
    "parity_hash": "0x53a483c7",
    "parity_scenario": "starter_copper_defense_v1",
    "workers_checksum": "0x4bb2b98d",
    "save_version": 13,
}

SCENARIOS = ("sector170", "smoke")


@dataclass
class Check:
    id: str
    required: bool
    expected: str
    observed: str
    ok: bool
    detail: str = ""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_records(text: str) -> list[dict[str, Any]]:
    """Extract every `[MIN3DSTRY]` record, tolerating emulator/log prefixes."""
    records: list[dict[str, Any]] = []
    for line in text.splitlines():
        match = RECORD_RE.search(line)
        if not match:
            continue
        rest = match.group("rest").strip()
        if not rest:
            continue
        head, _, payload = rest.partition(" ")
        tag = head.rstrip(":").strip()
        kv = {key: value for key, value in KV_RE.findall(payload)}
        records.append({"tag": tag, "payload": payload, "raw": line.strip(), "kv": kv})
    return records


def first(records: list[dict[str, Any]], tag: str) -> dict[str, Any] | None:
    return next((record for record in records if record["tag"] == tag), None)


def last(records: list[dict[str, Any]], tag: str) -> dict[str, Any] | None:
    found = [record for record in records if record["tag"] == tag]
    return found[-1] if found else None


def parse_int(value: str | None) -> int | None:
    if value is None:
        return None
    try:
        return int(value, 0)
    except ValueError:
        return None


def hardware_summary(record: dict[str, Any] | None) -> dict[str, Any]:
    if not record:
        return {}
    match = HW_RE.search(record["payload"])
    if not match:
        return {"raw": record["payload"]}
    fields = {}
    for part in match.group("rest").split(","):
        part = part.strip()
        if not part:
            continue
        key, _, value = part.partition(":")
        fields[key.strip().lower()] = value.strip()
    return {
        "tag": match.group("tag"),
        "mhz": fields.get("mhz", ""),
        "l2": fields.get("l2", ""),
        "app_mem_mib": parse_int(fields.get("appmem", "").split()[0] if fields.get("appmem") else None),
        "firm": fields.get("firm", ""),
        "model": record["payload"].split(" (")[0],
    }


def _check(
    checks: list[Check],
    cid: str,
    ok: bool,
    expected: str,
    observed: str,
    *,
    required: bool = True,
    detail: str = "",
) -> None:
    checks.append(
        Check(
            id=cid,
            required=required,
            expected=expected,
            observed=observed,
            ok=ok,
            detail=detail,
        )
    )


def build_checks(
    records: list[dict[str, Any]],
    profile: str,
    scenario: str,
    hardware: dict[str, Any],
) -> list[Check]:
    checks: list[Check] = []
    cfg = PROFILES[profile]

    tag = hardware.get("tag", "")
    mem = hardware.get("app_mem_mib")
    _check(
        checks,
        "hardware.profile",
        tag == cfg["tag"] and mem == cfg["app_mem_mib"],
        f"{cfg['tag']} AppMem:{cfg['app_mem_mib']} MiB",
        f"{tag or 'missing'} AppMem:{mem if mem is not None else 'missing'} MiB",
        detail="" if tag == cfg["tag"] else f"log reports {tag or 'no tag'}; profile is {profile}",
    )

    storage = first(records, "STORAGE_ROUNDTRIP")
    _check(
        checks,
        "storage.roundtrip",
        bool(storage and storage["kv"].get("status") == "ok"),
        "status=ok",
        (storage["kv"].get("status", "missing") if storage else "missing"),
    )

    if scenario == "sector170":
        slot = first(records, "SAVE_SLOT")
        _check(
            checks,
            "save.slot",
            bool(
                slot
                and slot["kv"].get("present") == "1"
                and slot["kv"].get("valid") == "1"
                and parse_int(slot["kv"].get("version")) == CANONICAL["save_version"]
            ),
            f"present=1 valid=1 version={CANONICAL['save_version']}",
            (
                f"present={slot['kv'].get('present', '?')} valid={slot['kv'].get('valid', '?')} "
                f"version={slot['kv'].get('version', '?')}"
            )
            if slot
            else "missing",
        )

    loader = first(records, "SECTOR_LOADER")
    loader_ok = bool(
        loader
        and loader["kv"].get("available") == "1"
        and loader["kv"].get("loaded") == "1"
        and parse_int(loader["kv"].get("tiles")) == CANONICAL["loader_tiles"]
        and parse_int(loader["kv"].get("buildings")) == CANONICAL["loader_buildings"]
    )
    _check(
        checks,
        "loader.sector",
        loader_ok,
        f"available=1 loaded=1 tiles={CANONICAL['loader_tiles']} buildings={CANONICAL['loader_buildings']}",
        (
            f"available={loader['kv'].get('available', '?')} loaded={loader['kv'].get('loaded', '?')} "
            f"tiles={loader['kv'].get('tiles', '?')} buildings={loader['kv'].get('buildings', '?')}"
        )
        if loader
        else "missing",
        required=scenario == "sector170",
    )

    alloc = first(records, "SECTOR_ALLOC")
    _check(
        checks,
        "loader.alloc",
        bool(alloc and alloc["kv"].get("hash") == CANONICAL["alloc_hash"]),
        f"hash={CANONICAL['alloc_hash']}",
        alloc["kv"].get("hash", "missing") if alloc else "missing",
        required=scenario == "sector170",
    )

    tile_state = first(records, "SECTOR_TILE_STATE")
    _check(
        checks,
        "loader.tile_state",
        bool(
            tile_state
            and tile_state["kv"].get("hash") == CANONICAL["tile_state_hash"]
            and parse_int(tile_state["kv"].get("linked")) == CANONICAL["tile_state_linked"]
        ),
        f"linked={CANONICAL['tile_state_linked']} hash={CANONICAL['tile_state_hash']}",
        (
            f"linked={tile_state['kv'].get('linked', '?')} hash={tile_state['kv'].get('hash', 'missing')}"
        )
        if tile_state
        else "missing",
        required=scenario == "sector170",
    )

    save_parity = first(records, "SECTOR_PARITY")
    _check(
        checks,
        "loader.save_parity",
        bool(save_parity and save_parity["kv"].get("hash") == CANONICAL["save_hash"]),
        f"hash={CANONICAL['save_hash']}",
        save_parity["kv"].get("hash", "missing") if save_parity else "missing",
        required=scenario == "sector170",
    )

    parity = first(records, "PARITY")
    parity_ok = bool(
        parity
        and parity["kv"].get("hash") == CANONICAL["parity_hash"]
        and parity["kv"].get("scenario") == CANONICAL["parity_scenario"]
    )
    _check(
        checks,
        "sim.parity",
        parity_ok,
        f"scenario={CANONICAL['parity_scenario']} hash={CANONICAL['parity_hash']}",
        (
            f"scenario={parity['kv'].get('scenario', '?')} hash={parity['kv'].get('hash', 'missing')}"
        )
        if parity
        else "missing",
    )

    worker_line = None
    for record in records:
        if record["tag"] == "WORKERS" and record["kv"].get("checksum") == CANONICAL["workers_checksum"]:
            worker_line = record
            break
    _check(
        checks,
        "workers.checksum",
        bool(worker_line and worker_line["kv"].get("match") == "1"),
        f"match=1 checksum={CANONICAL['workers_checksum']}",
        (
            f"match={worker_line['kv'].get('match', '?')} checksum={worker_line['kv'].get('checksum', '?')}"
        )
        if worker_line
        else "missing or checksum mismatch",
    )

    frames = [record for record in records if record["tag"] == "FRAME"]
    drops = [parse_int(record["kv"].get("drop")) or 0 for record in frames]
    _check(
        checks,
        "frame.drops",
        bool(frames) and max(drops, default=0) == 0,
        ">=1 FRAME record, max drop=0",
        f"frames={len(frames)} max_drop={max(drops, default=0)}",
    )

    mem = last(records, "MEM")
    _check(
        checks,
        "memory.budget",
        bool(
            mem
            and mem["kv"].get("status") == "ok"
            and parse_int(mem["kv"].get("failures")) == 0
        ),
        "status=ok failures=0",
        (
            f"status={mem['kv'].get('status', '?')} failures={mem['kv'].get('failures', '?')}"
        )
        if mem
        else "missing",
    )

    audio = last(records, "AUDIO")
    audio_state = audio["kv"].get("state", "missing") if audio else "missing"
    _check(
        checks,
        "audio.state",
        True,
        "ready (hardware with dspfirm) or fallback_no_dsp",
        audio_state,
        required=False,
        detail="informational: fallback_no_dsp means sd:/3ds/dspfirm.cdc is unavailable",
    )

    return checks


def build_summary(
    records: list[dict[str, Any]],
    checks: list[Check],
    *,
    commit: str,
    target: str,
    profile: str,
    scenario: str,
    rom: Path | None,
    timestamp: str,
) -> dict[str, Any]:
    hardware = hardware_summary(first(records, "HW"))
    loader = first(records, "SECTOR_LOADER")
    parity = first(records, "PARITY")
    frames = [record for record in records if record["tag"] == "FRAME"]
    required = [check for check in checks if check.required]
    failed = [check for check in required if not check.ok]
    return {
        "format": 1,
        "commit": commit,
        "target": target,
        "profile": profile,
        "scenario": scenario,
        "timestamp": timestamp,
        "hardware": hardware,
        "rom": (
            {"path": str(rom), "sha256": sha256_file(rom), "bytes": rom.stat().st_size}
            if rom
            else None
        ),
        "verdict": "pass" if not failed else "fail",
        "counts": {
            "checks": len(checks),
            "required": len(required),
            "passed": len(required) - len(failed),
            "failed": len(failed),
            "records": len(records),
            "frames": len(frames),
        },
        "loader": (
            {
                "available": loader["kv"].get("available"),
                "loaded": loader["kv"].get("loaded"),
                "tiles": loader["kv"].get("tiles"),
                "buildings": loader["kv"].get("buildings"),
                "path": loader["kv"].get("path"),
            }
            if loader
            else None
        ),
        "parity": parity["kv"] if parity else None,
        "checks": [
            {
                "id": check.id,
                "required": check.required,
                "expected": check.expected,
                "observed": check.observed,
                "ok": check.ok,
                "detail": check.detail,
            }
            for check in checks
        ],
        "records": [record["raw"] for record in records],
    }


def render_markdown(summary: dict[str, Any]) -> str:
    hardware = summary["hardware"]
    rom = summary["rom"]
    verdict = summary["verdict"].upper()
    counts = summary["counts"]
    lines = [
        f"# Hardware evidence — {summary['target']}",
        "",
        f"- Commit: `{summary['commit']}`",
        f"- Profile: `{summary['profile']}`",
        f"- Scenario: `{summary['scenario']}`",
        f"- Captured: `{summary['timestamp']}`",
        f"- Model: {hardware.get('model', 'unknown')} "
        f"({hardware.get('tag', '?')}, {hardware.get('mhz', '?')}, "
        f"L2:{hardware.get('l2', '?')}, AppMem:{hardware.get('app_mem_mib', '?')} MiB, "
        f"FIRM:{hardware.get('firm', '?')})",
        (
            f"- ROM: `{rom['path']}` (sha256 `{rom['sha256']}`, {rom['bytes']} bytes)"
            if rom
            else "- ROM: not supplied"
        ),
        f"- Verdict: **{verdict}** "
        f"({counts['passed']}/{counts['required']} required checks passed, "
        f"{counts['records']} records)",
        "",
        "| Check | Expected | Observed | Result |",
        "| --- | --- | --- | --- |",
    ]
    for check in summary["checks"]:
        marker = "PASS" if check["ok"] else ("FAIL" if check["required"] else "INFO")
        expected = check["expected"].replace("|", "\\|")
        observed = check["observed"].replace("|", "\\|")
        lines.append(f"| `{check['id']}` | {expected} | {observed} | {marker} |")

    notes = [check for check in summary["checks"] if check["detail"]]
    if notes:
        lines.extend(["", "## Notes", ""])
        for check in notes:
            lines.append(f"- `{check['id']}`: {check['detail']}")

    lines.extend(["", "## Captured records", "", "```text"])
    lines.extend(summary["records"])
    lines.extend(["```", ""])
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="Captured log containing [MIN3DSTRY] records.")
    parser.add_argument("--rom", default=None, help="Optional .3dsx/.cia to hash into the evidence.")
    parser.add_argument("--commit", default="unknown", help="Source commit SHA the build came from.")
    parser.add_argument("--target", default="3DS Hardware", help="Human label, e.g. 'New 3DS XL'.")
    parser.add_argument("--profile", choices=sorted(PROFILES), default="new3ds")
    parser.add_argument("--scenario", choices=SCENARIOS, default="sector170")
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    parser.add_argument("--out", default=None, help="Write the record here instead of stdout.")
    parser.add_argument("--timestamp", default=None, help="Override the capture timestamp (ISO-8601).")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Exit 1 when any required check fails.",
    )
    args = parser.parse_args(argv)

    log_path = Path(args.input)
    if not log_path.is_file():
        print(f"hw_evidence: log not found: {log_path}", file=sys.stderr)
        return 2
    rom = Path(args.rom) if args.rom else None
    if rom is not None and not rom.is_file():
        print(f"hw_evidence: rom not found: {rom}", file=sys.stderr)
        return 2

    records = parse_records(log_path.read_text(encoding="utf-8", errors="replace"))
    hardware = hardware_summary(first(records, "HW"))
    checks = build_checks(records, args.profile, args.scenario, hardware)
    timestamp = args.timestamp or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    summary = build_summary(
        records,
        checks,
        commit=args.commit,
        target=args.target,
        profile=args.profile,
        scenario=args.scenario,
        rom=rom,
        timestamp=timestamp,
    )

    output = (
        json.dumps(summary, indent=2) + "\n"
        if args.format == "json"
        else render_markdown(summary)
    )
    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(output, encoding="utf-8")
        print(
            f"hw_evidence: {summary['verdict'].upper()} "
            f"({summary['counts']['passed']}/{summary['counts']['required']}) -> {out_path}"
        )
    else:
        sys.stdout.write(output)

    if args.strict and summary["verdict"] != "pass":
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
