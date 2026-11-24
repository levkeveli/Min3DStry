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
import main_verify_alert  # noqa: E402
import pr_automation  # noqa: E402
import pr_triage  # noqa: E402


class MainVerificationTests(unittest.TestCase):
    def test_only_current_main_runs_can_clear_the_hold(self) -> None:
        repo = "owner/repo"
        success = {
            "head_sha": "current",
            "head_repository": {"full_name": repo},
            "event": "push",
            "status": "completed",
            "conclusion": "success",
        }
        unrelated = [
            dict(success, head_sha="older", conclusion="failure"),
            dict(success, event="pull_request"),
            dict(success, head_repository={"full_name": "fork/repo"}),
        ]
        for runs, expected in (
            ([success, *unrelated], []),
            (unrelated, ["3DS full build: no run on main HEAD"]),
            ([dict(success, conclusion="timed_out"), success], ["3DS full build: timed_out"]),
            ([dict(success, status="queued", conclusion=None)], ["3DS full build: queued"]),
        ):
            with self.subTest(expected=expected):
                responses = [
                    {"sha": "current"},
                    {"workflow_runs": runs},
                    {"workflow_runs": [dict(success, event="workflow_dispatch")]},
                ]
                with mock.patch.object(
                    pr_automation, "github_request",
                    side_effect=[(200, json.dumps(response)) for response in responses],
                ) as request:
                    missing = ["full-build.yml"] if runs == unrelated else []
                    self.assertEqual(
                        pr_automation.main_verification_status(repo, "token"),
                        ("current", expected, missing),
                    )
                    for call in request.call_args_list[1:]:
                        self.assertIn("branch=main&head_sha=current", call.args[0])

    def test_green_main_releases_only_automation_labels(self) -> None:
        repo = "owner/repo"
        pr = {
            "number": 7,
            "labels": [{"name": name} for name in ("hold", "main-verify-blocked", "main-verify-fix")],
            "isDraft": False,
        }
        with (
            mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": repo, "GH_TOKEN": "token"}),
            mock.patch.object(main_verify_alert, "main_verification_status", return_value=("sha", [], [])),
            mock.patch.object(main_verify_alert, "gh") as gh,
        ):
            gh.side_effect = ["", "", json.dumps([pr]), '[{"number": 8}]', "", "", ""]
            main_verify_alert.main()
        removals = [call.args[-1] for call in gh.call_args_list if "--remove-label" in call.args]
        self.assertEqual(removals, ["main-verify-blocked", "main-verify-fix"])
        self.assertEqual(gh.call_args.args[:4], (repo, "issue", "close", "8"))

    def test_alert_dispatches_missing_workflow_on_main(self) -> None:
        repo = "owner/repo"
        status = ("sha", ["3DS full build: no run on main HEAD"], ["full-build.yml"])
        with (
            mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": repo, "GH_TOKEN": "token"}),
            mock.patch.object(main_verify_alert, "main_verification_status", return_value=status),
            mock.patch.object(main_verify_alert, "gh") as gh,
        ):
            gh.side_effect = ["", "", "[]", '[{"number": 8}]', ""]
            main_verify_alert.main()
        self.assertEqual(gh.call_args.args, (repo, "workflow", "run", "full-build.yml", "--ref", "main"))


class PrTriageTests(unittest.TestCase):
    def test_comments_are_flattened_across_pages(self) -> None:
        comments = [{"body": "First page"}, {"body": "<!-- auto-rerun:sha -->"}]
        result = mock.Mock(returncode=0, stdout=json.dumps([[comment] for comment in comments]))
        with mock.patch.object(pr_triage, "gh_api", return_value=result) as api:
            self.assertEqual(pr_triage.get_comments(7), comments)
            self.assertEqual(api.call_args.args[1:], ("--paginate", "--slurp"))

    def test_older_gh_uses_json_lines_for_paginated_comments(self) -> None:
        comments = [{"body": "First page"}, {"body": "<!-- auto-rerun:sha -->"}]
        results = [
            mock.Mock(returncode=1, stderr="unknown flag: --slurp"),
            mock.Mock(returncode=0, stdout="\n".join(map(json.dumps, comments))),
        ]
        with mock.patch.object(pr_triage, "gh_api", side_effect=results) as api:
            self.assertEqual(pr_triage.get_comments(7), comments)
            self.assertEqual(api.call_args.args[1:], ("--paginate", "--jq", ".[] | @json"))


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
        draft_proc = mock.Mock(returncode=0, stdout="false\n", stderr="")
        fake_proc = mock.Mock(returncode=0, stdout="", stderr="")
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": "levkeveli/Min3DStry"}):
            with mock.patch("subprocess.run", side_effect=[draft_proc, fake_proc]) as run_mock:
                rc = pr_automation.cmd_auto_merge(args)
        self.assertEqual(rc, 0)
        self.assertEqual(
            run_mock.call_args_list[0],
            mock.call(
                ["gh", "pr", "view", "13", "-R", "levkeveli/Min3DStry", "--json", "isDraft", "--jq", ".isDraft"],
                text=True,
                capture_output=True,
            ),
        )
        self.assertEqual(
            run_mock.call_args_list[1],
            mock.call(
                ["gh", "pr", "merge", "13", "-R", "levkeveli/Min3DStry", "--squash", "--match-head-commit", "abc123", "--auto"],
                text=True,
                capture_output=True,
            ),
        )

    def test_auto_merge_skips_draft(self) -> None:
        args = pr_automation.build_parser().parse_args(
            ["auto-merge", "--pr-number", "13", "--head-sha", "abc123", "--tier", "A", "--eligible", "true"]
        )
        draft_proc = mock.Mock(returncode=0, stdout="true\n", stderr="")
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": "levkeveli/Min3DStry"}):
            with mock.patch("subprocess.run", return_value=draft_proc) as run_mock:
                rc = pr_automation.cmd_auto_merge(args)
        self.assertEqual(rc, 0)
        run_mock.assert_called_once_with(
            ["gh", "pr", "view", "13", "-R", "levkeveli/Min3DStry", "--json", "isDraft", "--jq", ".isDraft"],
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

    def test_call_openai_compatible_streams_sse_on_first_attempt(self) -> None:
        sse_lines = [
            b': ping\n',
            b'data: {"choices":[{"delta":{"reasoning_content":"Checking diff... "}}]}\n',
            b'data: {"choices":[{"delta":{"content":"{\\"verdict\\":\\"APPROVE\\","}}]}\n',
            b'data: {"choices":[{"delta":{"content":"\\"summary\\":\\"Stream ok\\",\\"findings\\":[]}"}}]}\n',
            b'data: [DONE]\n',
        ]
        fake_resp = mock.MagicMock()
        fake_resp.__enter__.return_value.headers = {"Content-Type": "text/event-stream"}
        fake_resp.__enter__.return_value.__iter__.return_value = iter(sse_lines)

        seen_payloads: list[dict[str, object]] = []

        def fake_urlopen(req, timeout=90):
            _ = timeout
            seen_payloads.append(json.loads(req.data.decode("utf-8")))
            return fake_resp

        with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            res = pr_automation.call_openai_compatible(
                "k", "m", "sys", "usr", endpoint="https://example.invalid/v1/chat/completions"
            )
        self.assertEqual(res["verdict"], "APPROVE")
        self.assertEqual(res["summary"], "Stream ok")
        self.assertEqual([p.get("stream") for p in seen_payloads], [True])
        self.assertNotIn("response_format", seen_payloads[0])

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
        fake_resp.__enter__.return_value.headers = {"Content-Type": "application/json"}
        fake_resp.__enter__.return_value.read.return_value = fake_ok_body

        seen_stream_flags: list[bool] = []

        def fake_urlopen(req, timeout=240):
            _ = timeout
            payload = json.loads(req.data.decode("utf-8"))
            seen_stream_flags.append(bool(payload.get("stream")))
            if len(seen_stream_flags) == 1:
                raise TimeoutError("timed out")
            return fake_resp

        with mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            res = pr_automation.call_openai_compatible(
                "k", "m", "sys", "usr", endpoint="https://example.invalid/v1/chat/completions"
            )
        self.assertEqual(res["verdict"], "APPROVE")
        self.assertEqual(seen_stream_flags, [True, False])

    def test_trim_pr_diff_omits_generated_svg_and_caps_size(self) -> None:
        diff = (
            "diff --git a/src/a.c b/src/a.c\n+int x = 1;\n"
            "diff --git a/docs/progress-gauge.svg b/docs/progress-gauge.svg\n"
            + ("+svg line\n" * 50)
            + "diff --git a/src/b.c b/src/b.c\n+int y = 2;\n"
        )
        trimmed, truncated = pr_automation.trim_pr_diff(diff, max_chars=40000)
        self.assertFalse(truncated)
        self.assertIn("+int x = 1;", trimmed)
        self.assertIn("+int y = 2;", trimmed)
        self.assertIn("generated file diff omitted", trimmed)
        self.assertNotIn("+svg line", trimmed)

        long_diff = "diff --git a/src/c.c b/src/c.c\n+" + ("a" * 200)
        capped, truncated_cap = pr_automation.trim_pr_diff(long_diff, max_chars=80)
        self.assertTrue(truncated_cap)
        self.assertLess(len(capped), 140)

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
