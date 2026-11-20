#!/usr/bin/env python3
"""Unit tests for pr_automation.py."""

import argparse
import json
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

    def test_call_openai_compatible_bounds_retries_to_two(self) -> None:
        fake_ok_body = json.dumps(
            {
                "choices": [
                    {
                        "message": {
                            "content": '{"verdict":"APPROVE","summary":"ok","findings":[]}'
                        }
                    }
                ]
            }
        ).encode("utf-8")
        fake_resp = mock.MagicMock()
        fake_resp.__enter__.return_value.read.return_value = fake_ok_body

        seen_response_formats: list[bool] = []

        def fake_urlopen(req, timeout=240):
            payload = json.loads(req.data.decode("utf-8"))
            seen_response_formats.append("response_format" in payload)
            if len(seen_response_formats) == 1:
                raise TimeoutError("timed out")
            return fake_resp

        with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            res = pr_automation.call_openai_compatible(
                "k", "m", "sys", "usr", endpoint="https://example.invalid/v1/chat/completions"
            )
        self.assertEqual(res["verdict"], "APPROVE")
        self.assertEqual(seen_response_formats, [True, False])

    def test_has_bot_approval_for_commit(self) -> None:
        def make_reviews(reviews):
            return [(200, json.dumps(reviews))]

        with mock.patch(
            "pr_automation.github_request",
            side_effect=make_reviews(
                [
                    {"user": {"login": "github-actions[bot]"}, "commit_id": "sha1", "state": "APPROVED"},
                    {"user": {"login": "other"}, "commit_id": "sha1", "state": "CHANGES_REQUESTED"},
                ]
            ),
        ):
            self.assertTrue(pr_automation.has_bot_approval_for_commit("o/r", 1, "sha1", "tok"))

        with mock.patch(
            "pr_automation.github_request",
            side_effect=make_reviews(
                [
                    {"user": {"login": "github-actions[bot]"}, "commit_id": "sha1", "state": "APPROVED"},
                    {"user": {"login": "github-actions[bot]"}, "commit_id": "sha1", "state": "CHANGES_REQUESTED"},
                ]
            ),
        ):
            self.assertFalse(pr_automation.has_bot_approval_for_commit("o/r", 1, "sha1", "tok"))

        with mock.patch(
            "pr_automation.github_request",
            side_effect=make_reviews(
                [
                    {"user": {"login": "github-actions[bot]"}, "commit_id": "oldsha", "state": "APPROVED"},
                ]
            ),
        ):
            self.assertFalse(pr_automation.has_bot_approval_for_commit("o/r", 1, "sha1", "tok"))

    def test_cmd_ai_review_skips_duplicate_on_metadata_events(self) -> None:
        pr_payload = {
            "number": 60,
            "head": {"sha": "abc123456789"},
            "base": {"ref": "main"},
            "title": "Test",
            "body": "Fixes #1",
        }
        args = argparse.Namespace(tier="A")

        with mock.patch.dict(
            os.environ, {"GITHUB_TOKEN": "tok", "OPENCODE_GO_API_KEY": "k"}, clear=True
        ):
            with mock.patch(
                "pr_automation.load_pr_event",
                return_value=("o/r", "labeled", pr_payload),
            ), mock.patch(
                "pr_automation.has_bot_approval_for_commit", return_value=True
            ), mock.patch(
                "pr_automation.call_openai_compatible"
            ) as llm_mock, mock.patch(
                "pr_automation.write_outputs"
            ) as out_mock:
                rc = pr_automation.cmd_ai_review(args)
        self.assertEqual(rc, 0)
        llm_mock.assert_not_called()
        out_mock.assert_called_once_with({"approved": "true"})

        with mock.patch.dict(
            os.environ, {"GITHUB_TOKEN": "tok", "OPENCODE_GO_API_KEY": "k"}, clear=True
        ):
            with mock.patch(
                "pr_automation.load_pr_event",
                return_value=("o/r", "synchronize", pr_payload),
            ), mock.patch(
                "pr_automation.has_bot_approval_for_commit", return_value=True
            ), mock.patch(
                "pr_automation.github_request", return_value=(200, "diff")
            ), mock.patch(
                "pr_automation.fetch_repo_file", return_value="doc"
            ), mock.patch(
                "pr_automation.call_openai_compatible",
                return_value={"verdict": "APPROVE", "summary": "ok", "findings": []},
            ) as llm_mock, mock.patch(
                "pr_automation.write_outputs"
            ) as out_mock:
                rc = pr_automation.cmd_ai_review(args)
        self.assertEqual(rc, 0)
        llm_mock.assert_called_once()
        out_mock.assert_called_once_with({"approved": "true"})


if __name__ == "__main__":
    unittest.main()
