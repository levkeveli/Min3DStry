#!/usr/bin/env python3
"""Determine which upstream-arm11-smoke jobs need to run for a PR."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any

ALL_PROBES = [
    "logic",
    "map",
    "resource",
    "executor",
    "campaign-settings",
    "campaign-content",
    "campaign-research",
    "campaign-slot-meta",
    "campaign-sector-info",
    "campaign-planet",
    "campaign-sector-state",
    "campaign-launch",
    "campaign-saves",
    "campaign-slot-discovery",
    "wave-routing",
]

NARROW_CAMPAIGN_PROBES = {
    "campaign-settings",
    "campaign-content",
    "campaign-research",
    "campaign-slot-meta",
    "campaign-sector-info",
    "campaign-planet",
    "campaign-sector-state",
    "campaign-launch",
    "campaign-saves",
    "campaign-slot-discovery",
}

AUDIO_EXACT = {
    "tools/build_audio_assets.py",
    "tools/stage_music_assets.py",
    "tests/tools/test_audio_assets.py",
    "tests/tools/test_stage_music_assets.py",
    "research/upstream-runtime/src/gameplay/java/UpstreamAudioTickProbe.java",
}

NET_EXACT = {
    "tools/verify_upstream_net.py",
    "tools/verify_upstream_live_net.py",
}

SAVE_EXACT = {
    "research/upstream-runtime/host/UpstreamCurrentSaveFixture.java",
}

PROGRESSION_EXACT = {
    "research/upstream-runtime/host/UpstreamProgressionBaseline.java",
    "research/upstream-runtime/host/UpstreamCampaignPersistenceBaseline.java",
}

COMBAT_EXACT = {
    "research/upstream-runtime/src/gameplay/java/UpstreamCombatTickProbe.java",
    "research/upstream-runtime/src/gameplay/java/UpstreamWaveRoutingProbe.java",
}

PRODUCTION_EXACT = {
    "research/upstream-runtime/host/UpstreamProductionBaseline.java",
    "research/upstream-runtime/src/gameplay/java/UpstreamProductionTickProbe.java",
}

FACTORY_EXACT = {
    "research/upstream-runtime/host/UpstreamFactoryBaseline.java",
    "research/upstream-runtime/src/gameplay/java/UpstreamFactoryTickProbe.java",
}

FULL_SUITE_EXACT = {
    ".github/workflows/upstream-arm11-smoke.yml",
    ".github/scripts/fetch_upstream_v160.sh",
    ".github/scripts/smoke_scope.py",
}


def _full_scope() -> dict[str, Any]:
    return {
        "run_all": True,
        "run_probes": True,
        "probes": list(ALL_PROBES),
        "run_save": True,
        "run_audio": True,
        "run_progression": True,
        "run_combat": True,
        "run_net": True,
        "run_production": True,
        "run_factory": True,
    }


def _classify_path(path: str, flags: dict[str, bool], probes: set[str]) -> str:
    norm = path.lstrip("/")

    if norm in FULL_SUITE_EXACT:
        return "full"

    if norm.startswith("audio/") or norm in AUDIO_EXACT or norm.startswith(
        "research/upstream-runtime/host/UpstreamAudio"
    ):
        flags["run_audio"] = True
        return "scoped"

    if (
        norm.startswith("src/net/")
        or norm.startswith("include/net/")
        or norm.startswith("tests/net/")
        or norm in NET_EXACT
        or norm.startswith("research/upstream-runtime/host/UpstreamNet")
    ):
        flags["run_net"] = True
        return "scoped"

    if (
        norm.startswith("research/upstream-runtime/host/UpstreamSave")
        or norm in SAVE_EXACT
        or norm.startswith("research/upstream-runtime/src/campaign-sector/")
    ):
        flags["run_save"] = True
        return "scoped"

    if norm in PROGRESSION_EXACT:
        flags["run_progression"] = True
        return "scoped"

    if norm in COMBAT_EXACT or norm.startswith("research/upstream-runtime/host/UpstreamCombat"):
        flags["run_combat"] = True
        probes.add("wave-routing")
        return "scoped"

    if norm in PRODUCTION_EXACT:
        flags["run_production"] = True
        return "scoped"

    if norm in FACTORY_EXACT:
        flags["run_factory"] = True
        return "scoped"

    if norm.startswith("research/upstream-runtime/src/"):
        parts = norm.split("/")
        if len(parts) >= 4 and parts[3] in NARROW_CAMPAIGN_PROBES:
            probes.add(parts[3])
            return "scoped"

    if norm.startswith("research/upstream-runtime/"):
        return "full"

    return "unrelated"


def classify_smoke_scope(changed_files: list[str], event_name: str) -> dict[str, Any]:
    """Return a conservative job/probe execution plan for upstream-arm11-smoke.yml."""
    if event_name != "pull_request" or not changed_files:
        return _full_scope()

    flags = {
        "run_save": False,
        "run_audio": False,
        "run_progression": False,
        "run_combat": False,
        "run_net": False,
        "run_production": False,
        "run_factory": False,
    }
    probes: set[str] = set()
    saw_scoped = False

    for raw_path in changed_files:
        kind = _classify_path(raw_path, flags, probes)
        if kind == "full":
            return _full_scope()
        if kind == "scoped":
            saw_scoped = True

    if not saw_scoped:
        return _full_scope()

    ordered_probes = [probe for probe in ALL_PROBES if probe in probes]
    return {
        "run_all": False,
        "run_probes": bool(ordered_probes),
        "probes": ordered_probes,
        "run_save": flags["run_save"],
        "run_audio": flags["run_audio"],
        "run_progression": flags["run_progression"],
        "run_combat": flags["run_combat"],
        "run_net": flags["run_net"],
        "run_production": flags["run_production"],
        "run_factory": flags["run_factory"],
    }


def fetch_pr_files(repo: str, pr_number: int, token: str) -> list[str]:
    files: list[str] = []
    page = 1
    while True:
        url = (
            f"https://api.github.com/repos/{repo}/pulls/{pr_number}/files"
            f"?per_page=100&page={page}"
        )
        req = urllib.request.Request(
            url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "Min3DStry-Smoke-Scope",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                batch = json.loads(resp.read().decode("utf-8", errors="replace"))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"GitHub API GET {url} failed ({exc.code}): {body}") from exc
        if not batch:
            break
        files.extend(item["filename"] for item in batch)
        if len(batch) < 100:
            break
        page += 1
    return files


def format_outputs(scope: dict[str, Any]) -> dict[str, str]:
    return {
        "run_all": "true" if scope["run_all"] else "false",
        "run_probes": "true" if scope["run_probes"] else "false",
        "probes_json": json.dumps(scope["probes"], separators=(",", ":")),
        "run_save": "true" if scope["run_save"] else "false",
        "run_audio": "true" if scope["run_audio"] else "false",
        "run_progression": "true" if scope["run_progression"] else "false",
        "run_combat": "true" if scope["run_combat"] else "false",
        "run_net": "true" if scope["run_net"] else "false",
        "run_production": "true" if scope["run_production"] else "false",
        "run_factory": "true" if scope["run_factory"] else "false",
    }


def write_outputs(outputs: dict[str, str]) -> None:
    out_path = os.environ.get("GITHUB_OUTPUT")
    if not out_path:
        for key, value in outputs.items():
            print(f"{key}={value}")
        return
    with open(out_path, "a", encoding="utf-8") as fh:
        for key, value in outputs.items():
            fh.write(f"{key}={value}\n")


def main() -> int:
    event_name = os.environ.get("GITHUB_EVENT_NAME", "").strip()
    changed_files: list[str] = []

    if event_name == "pull_request":
        event_path = os.environ.get("GITHUB_EVENT_PATH", "").strip()
        repo = os.environ.get("GITHUB_REPOSITORY", "").strip()
        token = os.environ.get("GITHUB_TOKEN", "").strip()
        if event_path and repo and token:
            with open(event_path, encoding="utf-8") as fh:
                event = json.load(fh)
            pr_number = int((event.get("pull_request") or {})["number"])
            changed_files = fetch_pr_files(repo, pr_number, token)

    scope = classify_smoke_scope(changed_files, event_name)
    outputs = format_outputs(scope)
    write_outputs(outputs)
    print(f"Smoke scope ({event_name or 'local'}): {json.dumps(outputs, sort_keys=True)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
