#!/usr/bin/env python3
"""Unit tests for smoke_scope.py."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import smoke_scope  # noqa: E402


class SmokeScopeTests(unittest.TestCase):
    def test_non_pr_events_run_full_suite(self) -> None:
        for event in ("push", "workflow_dispatch", ""):
            scope = smoke_scope.classify_smoke_scope(["audio/upstream-audio-map.json"], event)
            self.assertTrue(scope["run_all"])
            self.assertTrue(scope["run_probes"])
            self.assertEqual(scope["probes"], smoke_scope.ALL_PROBES)
            self.assertTrue(scope["run_audio"])
            self.assertTrue(scope["run_factory"])

    def test_empty_changed_files_fails_open_to_full_suite(self) -> None:
        scope = smoke_scope.classify_smoke_scope([], "pull_request")
        self.assertTrue(scope["run_all"])
        self.assertEqual(scope["probes"], smoke_scope.ALL_PROBES)

    def test_audio_only_pr_scopes_to_audio_jobs(self) -> None:
        scope = smoke_scope.classify_smoke_scope(
            ["audio/upstream-music-map.json", "tools/stage_music_assets.py"],
            "pull_request",
        )
        self.assertFalse(scope["run_all"])
        self.assertFalse(scope["run_probes"])
        self.assertEqual(scope["probes"], [])
        self.assertTrue(scope["run_audio"])
        self.assertFalse(scope["run_net"])
        self.assertFalse(scope["run_save"])
        self.assertFalse(scope["run_combat"])

    def test_net_only_pr_scopes_to_net_job(self) -> None:
        scope = smoke_scope.classify_smoke_scope(
            ["src/net/mindustry_net.c", "tests/net/test_mindustry_net.c"],
            "pull_request",
        )
        self.assertFalse(scope["run_all"])
        self.assertFalse(scope["run_probes"])
        self.assertTrue(scope["run_net"])
        self.assertFalse(scope["run_audio"])
        self.assertFalse(scope["run_production"])

    def test_single_campaign_probe_pr(self) -> None:
        scope = smoke_scope.classify_smoke_scope(
            ["research/upstream-runtime/src/campaign-research/java/UpstreamCampaignResearchStateProbe.java"],
            "pull_request",
        )
        self.assertFalse(scope["run_all"])
        self.assertTrue(scope["run_probes"])
        self.assertEqual(scope["probes"], ["campaign-research"])
        self.assertFalse(scope["run_progression"])
        self.assertFalse(scope["run_save"])

    def test_combat_pr_includes_combat_and_wave_routing_probe(self) -> None:
        scope = smoke_scope.classify_smoke_scope(
            ["research/upstream-runtime/src/gameplay/java/UpstreamWaveRoutingProbe.java"],
            "pull_request",
        )
        self.assertFalse(scope["run_all"])
        self.assertTrue(scope["run_probes"])
        self.assertEqual(scope["probes"], ["wave-routing"])
        self.assertTrue(scope["run_combat"])
        self.assertFalse(scope["run_factory"])
        self.assertFalse(scope["run_singleplayer"])

    def test_singleplayer_pr_scopes_to_singleplayer_baseline(self) -> None:
        scope = smoke_scope.classify_smoke_scope(
            [
                "research/upstream-runtime/src/singleplayer/java/UpstreamSinglePlayerSession.java",
                "tools/verify_upstream_singleplayer.py",
            ],
            "pull_request",
        )
        self.assertFalse(scope["run_all"])
        self.assertFalse(scope["run_probes"])
        self.assertTrue(scope["run_singleplayer"])
        self.assertFalse(scope["run_factory"])

    def test_multi_scope_and_unrelated_docs(self) -> None:
        scope = smoke_scope.classify_smoke_scope(
            [
                "docs/audio/music-assets.md",
                "audio/upstream-audio-map.json",
                "tools/verify_upstream_net.py",
                "research/upstream-runtime/src/campaign-launch/java/UpstreamCampaignLaunchProbe.java",
            ],
            "pull_request",
        )
        self.assertFalse(scope["run_all"])
        self.assertTrue(scope["run_audio"])
        self.assertTrue(scope["run_net"])
        self.assertTrue(scope["run_probes"])
        self.assertEqual(scope["probes"], ["campaign-launch"])
        self.assertFalse(scope["run_combat"])

    def test_shared_runtime_or_workflow_changes_fail_open_to_full_suite(self) -> None:
        full_triggers = [
            "research/upstream-runtime/build.gradle",
            "research/upstream-runtime/arm11/prepare.py",
            "research/upstream-runtime/src/world/java/UpstreamSaveRowsProbe.java",
            ".github/workflows/upstream-arm11-smoke.yml",
            ".github/scripts/smoke_scope.py",
            ".github/scripts/fetch_upstream_v160.sh",
            "README.md",
        ]
        for path in full_triggers:
            scope = smoke_scope.classify_smoke_scope([path], "pull_request")
            self.assertTrue(scope["run_all"], msg=f"expected full suite for {path}")
            self.assertEqual(scope["probes"], smoke_scope.ALL_PROBES)

    def test_format_outputs_json_valid(self) -> None:
        scope = smoke_scope.classify_smoke_scope(
            ["research/upstream-runtime/host/UpstreamFactoryBaseline.java"],
            "pull_request",
        )
        out = smoke_scope.format_outputs(scope)
        self.assertEqual(out["run_factory"], "true")
        self.assertEqual(out["run_probes"], "false")
        self.assertEqual(json.loads(out["probes_json"]), [])


if __name__ == "__main__":
    unittest.main()
