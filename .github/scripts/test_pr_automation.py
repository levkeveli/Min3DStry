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

    def test_tier_c_protected_files_and_labels(self) -> None:
        tier, _ = pr_automation.classify_risk_tier(
            ["src/game/world.c", ".github/workflows/ci.yml"], []
        )
        self.assertEqual(tier, "C")

        tier_agents, _ = pr_automation.classify_risk_tier(["AGENTS.md"], [])
        self.assertEqual(tier_agents, "C")

        tier_label, _ = pr_automation.classify_risk_tier(
            ["README.md"], ["status:blocked"]
        )
        self.assertEqual(tier_label, "C")

    def test_metadata_validation(self) -> None:
        bad_body = (
            "## Linked Issue\nCloses #<issue>\n\n"
            "## Verification (do not claim tests that were not run)\n"
            "- Commands executed:\n- Results:\n"
        )
        errors = pr_automation.validate_pr_metadata(bad_body)
        self.assertEqual(len(errors), 2)

        good_body = (
            "## Linked Issue\nCloses #9\n\n"
            "## Verification (do not claim tests that were not run)\n"
            "- Commands executed: `./dev.sh test`\n"
            "- Results: passed locally\n"
        )
        self.assertEqual(pr_automation.validate_pr_metadata(good_body), [])

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


if __name__ == "__main__":
    unittest.main()
