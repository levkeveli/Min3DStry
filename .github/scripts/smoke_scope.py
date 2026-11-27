#!/usr/bin/env python3
"""Determine which upstream-arm11-smoke jobs need to run for a PR or main push."""

from __future__ import annotations

import json
import os
import re
import subprocess
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
    "campaign-capture",
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
    "campaign-capture",
    "campaign-saves",
    "campaign-slot-discovery",
}

AUDIO_EXACT = {
    "tools/build_audio_assets.py",
    "tools/verify_offline_stock_audio.py",
    "tools/stage_music_assets.py",
    "tests/tools/test_audio_assets.py",
    "tests/tools/test_stage_music_assets.py",
    "research/upstream-runtime/src/gameplay/java/UpstreamAudioTickProbe.java",
}

NET_EXACT = {
    "tools/verify_upstream_net.py",
    "tools/verify_upstream_live_net.py",
    "tools/verify_stock_live_crafting.py",
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

CAMPAIGN_RESUME_EXACT = {
    "tools/campaign_resume_fixtures.py",
    "tools/verify_upstream_campaign_resume.py",
    "tools/verify_stock_campaign_archive.py",
    "research/upstream-runtime/src/singleplayer/java/UpstreamCampaignSave.java",
    "research/upstream-runtime/src/singleplayer/java/UpstreamSinglePlayerSession.java",
    "research/upstream-runtime/src/singleplayer-campaign-host/java/UpstreamCampaignResumeReference.java",
}

PROCESSOR_EDITOR_EXACT = {
    "tools/verify_stock_processor_editor.py",
    "tools/verify_stock_command_orders.py",
    "research/upstream-runtime/src/network-live/java/UpstreamBuildingConfig.java",
    "research/upstream-runtime/src/network-live/java/UpstreamLogicStatements.java",
    "research/upstream-runtime/src/network-live/java/UpstreamProcessorConfiguration.java",
    "research/upstream-runtime/src/network-live/host/UpstreamProcessorConfigurationReference.java",
}

SINGLEPLAYER_EXACT = {
    "tools/verify_upstream_singleplayer.py",
    "tools/verify_upstream_campaign_globe.py",
    "tools/UpstreamCampaignPlanetMeshes.java",
    "tests/tools/test_verify_upstream_singleplayer.py",
}

FULL_SUITE_EXACT = {
    ".github/scripts/build_unified_arm11_smoke.sh",
    ".github/scripts/build_teavm_host.sh",
    "research/upstream-runtime/arm11/compile_teavm.py",
    ".github/scripts/setup_smoke_azahar.sh",
    "tools/verify_azahar.py",
    "tools/UpstreamSpriteAtlas.java",
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
        "run_singleplayer": True,
        "run_campaign_resume": True,
        "run_processor_editor": True,
    }


RUNTIME = "research/upstream-runtime/"
LIVE_AUDIO_PREFIXES = (
    RUNTIME + "arm11/m3ds_live_audio.",
    RUNTIME + "src/network-render/java/port/audio/",
    RUNTIME + "src/network-render/host/port/audio/",
    "src/audio/",
    "include/audio/",
    "tests/audio/",
)
LIVE_AUDIO_TOOLS = {
    "tools/stage_sound_catalog.py",
    "tools/verify_live_stock_audio.py",
    "tools/verify_stock_live_audio.py",
}
LIVE_GRAPHICS_PREFIXES = (
    RUNTIME + "src/terrain/",
    RUNTIME + "src/status-hud/",
    RUNTIME + "src/network-render/",
    RUNTIME + "arm11/m3ds_terrain_renderer.",
    RUNTIME + "arm11/m3ds_quad",
    RUNTIME + "arm11/m3ds_stock_font.",
    RUNTIME + "arm11/m3ds_stock_text.",
    RUNTIME + "arm11/m3ds_world_fragment.",
)


def _classify_path(path: str, flags: dict[str, bool], probes: set[str]) -> str:
    norm = path.lstrip("/")

    if norm in FULL_SUITE_EXACT:
        return "full"

    if norm.startswith(RUNTIME) and norm.endswith(".md"):
        return "unrelated"

    if norm.startswith(LIVE_AUDIO_PREFIXES) or norm in LIVE_AUDIO_TOOLS:
        flags["run_audio"] = True
        flags["run_net"] = True
        return "scoped"

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

    if norm == RUNTIME + "src/gameplay/java/ProbeListenerShutdown.java":
        probes.add("wave-routing")
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

    if norm in PROCESSOR_EDITOR_EXACT:
        flags["run_processor_editor"] = True
        flags["run_singleplayer"] = True
        return "scoped"

    if norm in CAMPAIGN_RESUME_EXACT:
        flags["run_singleplayer"] = True
        flags["run_campaign_resume"] = True
        return "scoped"

    if norm in SINGLEPLAYER_EXACT or norm.startswith((
        RUNTIME + "src/singleplayer/",
        RUNTIME + "src/singleplayer-campaign-host/",
        RUNTIME + "src/singleplayer-custom-host/",
        RUNTIME + "src/singleplayer-research-host/",
    )):
        flags["run_singleplayer"] = True
        return "scoped"

    if norm.startswith(LIVE_GRAPHICS_PREFIXES):
        flags["run_singleplayer"] = True
        flags["run_net"] = True
        return "scoped"

    if norm.startswith((RUNTIME + "src/network-live/", RUNTIME + "arm11/m3ds_net_bridge.")):
        flags["run_net"] = True
        flags["run_singleplayer"] = True
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
    if event_name not in ("pull_request", "push") or not changed_files:
        return _full_scope()

    flags = {
        "run_save": False,
        "run_audio": False,
        "run_progression": False,
        "run_combat": False,
        "run_net": False,
        "run_production": False,
        "run_factory": False,
        "run_singleplayer": False,
        "run_campaign_resume": False,
        "run_processor_editor": False,
    }
    probes: set[str] = set()
    for raw_path in changed_files:
        kind = _classify_path(raw_path, flags, probes)
        if kind == "full":
            return _full_scope()

    if not probes and not any(flags.values()) and not all(path.endswith(".md") for path in changed_files):
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
        "run_singleplayer": flags["run_singleplayer"],
        "run_campaign_resume": flags["run_campaign_resume"],
        "run_processor_editor": flags["run_processor_editor"],
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
        for item in batch:
            files.append(item["filename"])
            if previous := item.get("previous_filename"):
                files.append(previous)
        if len(batch) < 100:
            break
        if page == 30:
            # The PR files endpoint stops at 3,000 entries.
            return []
        page += 1
    return files


def push_changed_files(before: str, after: str) -> list[str]:
    # A missing base (including branch creation) falls back to the full suite.
    if any(not re.fullmatch(r"[0-9a-f]{40}", sha) for sha in (before, after)):
        return []
    try:
        result = subprocess.run(
            ["git", "diff", "--no-renames", "--name-only", "-z", before, after, "--"],
            check=True, capture_output=True, text=True,
        )
    except subprocess.CalledProcessError:
        return []
    return [path for path in result.stdout.split("\0") if path]


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
        "run_singleplayer": "true" if scope["run_singleplayer"] else "false",
        "run_campaign_resume": "true" if scope["run_campaign_resume"] else "false",
        "run_processor_editor": "true" if scope["run_processor_editor"] else "false",
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

    event_path = os.environ.get("GITHUB_EVENT_PATH", "").strip()
    if event_name == "pull_request":
        repo = os.environ.get("GITHUB_REPOSITORY", "").strip()
        token = os.environ.get("GITHUB_TOKEN", "").strip()
        if event_path and repo and token:
            with open(event_path, encoding="utf-8") as fh:
                event = json.load(fh)
            pr_number = int((event.get("pull_request") or {})["number"])
            try:
                changed_files = fetch_pr_files(repo, pr_number, token)
            except (RuntimeError, OSError, ValueError) as exc:
                print(f"Unable to read PR changed files; running full suite: {type(exc).__name__}",
                      file=sys.stderr)
    elif event_name == "push" and event_path:
        with open(event_path, encoding="utf-8") as fh:
            event = json.load(fh)
        changed_files = push_changed_files(event.get("before", ""), event.get("after", ""))

    scope = classify_smoke_scope(changed_files, event_name)
    outputs = format_outputs(scope)
    write_outputs(outputs)
    print(f"Smoke scope ({event_name or 'local'}): {json.dumps(outputs, sort_keys=True)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
