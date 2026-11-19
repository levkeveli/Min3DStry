#!/usr/bin/env python3
"""Unit tests for pr_automation.py."""

import os
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pr_automation  # noqa: E402


class PrAutomationTests(unittest.TestCase):
    def test_tier_a_routine_files(self) -> None:
        tier, _ = pr_automation.classify_risk_tier(
            ["src/game/world.c", "tests/world_test.c", "README.md"], []
        )
        self.assertEqual(tier, "A")

    def test_tier_b_coordinated_files(self) -> None:
        tier, _ = pr_automation.classify_risk_tier(
            ["src/platform/3ds_input.c", "docs/architecture.md"], []
        )
        self.assertEqual(tier, "B")

    def test_tier_c_explicit_hold_labels_only(self) -> None:
        tier_workflows, _ = pr_automation.classify_risk_tier(
            [".github/workflows/pages.yml", ".github/workflows/ai-pr-review.yml"], []
        )
        self.assertEqual(tier_workflows, "A")

        for label in ("status:blocked", "tier:c", "hold"):
            tier_label, _ = pr_automation.classify_risk_tier(["README.md"], [label])
            self.assertEqual(tier_label, "C")

    def test_metadata_validation_and_warnings(self) -> None:
        empty_body_errors = pr_automation.validate_pr_metadata("")
        self.assertEqual(empty_body_errors, [])
        self.assertEqual(len(pr_automation.collect_metadata_warnings("")), 1)
        self.assertEqual(len(pr_automation.collect_metadata_warnings("Quick fix")), 1)
        self.assertEqual(pr_automation.collect_metadata_warnings("Fixes #9"), [])

    def test_auto_merge_passes_head_sha(self) -> None:
        args = pr_automation.build_parser().parse_args(
            ["auto-merge", "--pr-number", "13", "--head-sha", "abc123", "--tier", "A", "--eligible", "true"]
        )
        fake_proc = mock.Mock(returncode=0, stdout="", stderr="")
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": "levkeveli/Min3DStry"}):
            with mock.patch("subprocess.run", return_value=fake_proc) as run_mock:
                rc = pr_automation.cmd_auto_merge(args)
        self.assertEqual(rc, 0)
        run_mock.assert_called_once_with(
            ["gh", "pr", "merge", "13", "-R", "levkeveli/Min3DStry", "--squash", "--match-head-commit", "abc123", "--auto"],
            text=True,
            capture_output=True,
        )

    def test_parse_ai_json_with_fences(self) -> None:
        raw = '```json\n{"verdict":"APPROVE","summary":"Looks good","findings":[]}\n```'
        parsed = pr_automation.parse_ai_json(raw)
        self.assertEqual(parsed["verdict"], "APPROVE")

    def test_select_ai_provider_precedence(self) -> None:
        with mock.patch.dict(
            os.environ,
            {
                "OPENAI_API_KEY": "sk-test",
                "GEMINI_API_KEY": "gm-test",
                "AI_REVIEW_PROVIDER": "gemini",
            },
            clear=True,
        ):
            selected = pr_automation.select_ai_provider()
            self.assertIsNotNone(selected)
            assert selected is not None
            self.assertEqual(selected[0], "gemini")

    def test_select_opencode_go_default(self) -> None:
        with mock.patch.dict(
            os.environ,
            {"OPENCODE_API_KEY": "oc-test"},
            clear=True,
        ):
            selected = pr_automation.select_ai_provider()
            self.assertEqual(selected, ("opencode-go", "oc-test", "deepseek-v4.1-flash"))


if __name__ == "__main__":
    unittest.main()
