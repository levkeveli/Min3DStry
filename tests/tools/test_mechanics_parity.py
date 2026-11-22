import json
import sys
import tempfile
import unittest
from pathlib import Path
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import mechanics_parity  # noqa: E402


REPO_ROOT = Path(__file__).resolve().parents[2]


class MechanicsParityTests(unittest.TestCase):
    def test_real_ledger_and_svg_are_valid_and_fresh(self) -> None:
        ledger = mechanics_parity.load_and_validate_mechanics(
            REPO_ROOT / "docs" / "mechanics-parity.json", REPO_ROOT
        )
        summary = mechanics_parity.compute_mechanics_parity(ledger)
        self.assertEqual(summary["total_points"], 100)
        self.assertGreater(summary["percent"], 0.0)
        self.assertLess(summary["percent"], 100.0)
        self.assertAlmostEqual(summary["earned_points"] + summary["missing_points"], 100.0, places=2)
        self.assertAlmostEqual(sum(summary["status_earned_points"].values()), summary["earned_points"], places=2)
        self.assertEqual(sum(summary["status_weight_points"].values()), 100)
        self.assertEqual(len(summary["domains"]), 8)
        self.assertEqual(sum(dom["weight"] for dom in summary["domains"]), 100)
        self.assertEqual(len(summary["features"]), summary["feature_count"])
        for feat in summary["features"]:
            self.assertIn(feat["status"], summary["status_weights"])
            self.assertGreaterEqual(feat["evidence_count"], 1 if feat["status"] != "missing" else 0)
            if feat["primary_doc"]:
                self.assertTrue((REPO_ROOT / feat["primary_doc"]).is_file())

        expected_svg = mechanics_parity.render_mechanics_svg(summary)
        actual_svg = (REPO_ROOT / "docs" / "mechanics-gauge.svg").read_text(encoding="utf-8")
        self.assertEqual(actual_svg, expected_svg)
        self.assertIn(f"{summary['percent']:.1f}%", expected_svg)
        self.assertIn("Weight breakdown:", expected_svg)
        for dom in summary["domains"]:
            self.assertIn(escape(dom["name"]), expected_svg)

    def test_rejects_missing_evidence_and_invalid_modes(self) -> None:
        base_weights = {
            "missing": 0.0,
            "harness-only": 0.15,
            "probe-arm11": 0.4,
            "partial-arm11": 0.6,
            "live-arm11": 0.85,
            "complete": 1.0,
        }

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            good_ev = root / "proof.txt"
            good_ev.write_text("ok\n", encoding="utf-8")
            ledger_file = root / "mech.json"

            def write_and_validate(features: list[dict[str, object]]) -> None:
                ledger_file.write_text(
                    json.dumps({"status_weights": base_weights, "features": features}),
                    encoding="utf-8",
                )
                mechanics_parity.load_and_validate_mechanics(ledger_file, root)

            with self.assertRaises(ValueError):
                write_and_validate(
                    [
                        {
                            "id": "bad-ev",
                            "domain": "Core",
                            "name": "Missing Evidence",
                            "weight": 100,
                            "status": "probe-arm11",
                            "modes": ["probe"],
                            "evidence": ["does/not/exist.java"],
                            "notes": "invalid",
                        }
                    ]
                )

            with self.assertRaises(ValueError):
                write_and_validate(
                    [
                        {
                            "id": "missing-with-mode",
                            "domain": "Core",
                            "name": "Missing With Mode",
                            "weight": 100,
                            "status": "missing",
                            "modes": ["offline"],
                            "evidence": [],
                            "notes": "invalid",
                        }
                    ]
                )

            with self.assertRaises(ValueError):
                write_and_validate(
                    [
                        {
                            "id": "bad-mode",
                            "domain": "Core",
                            "name": "Bad Mode",
                            "weight": 100,
                            "status": "live-arm11",
                            "modes": ["bogus"],
                            "evidence": ["proof.txt"],
                            "notes": "invalid",
                        }
                    ]
                )

            with self.assertRaises(ValueError):
                write_and_validate(
                    [
                        {
                            "id": "escape-ev",
                            "domain": "Core",
                            "name": "Escape Repo",
                            "weight": 100,
                            "status": "live-arm11",
                            "modes": ["offline"],
                            "evidence": ["../outside.txt"],
                            "notes": "invalid",
                        }
                    ]
                )

            with self.assertRaises(ValueError):
                write_and_validate(
                    [
                        {
                            "id": "short-weight",
                            "domain": "Core",
                            "name": "Short Weight",
                            "weight": 50,
                            "status": "live-arm11",
                            "modes": ["offline"],
                            "evidence": ["proof.txt"],
                            "notes": "invalid",
                        }
                    ]
                )

    def test_dashboard_generator_exports_mechanics_parity(self) -> None:
        from unittest import mock

        site_dir = REPO_ROOT / "site"
        sys.path.insert(0, str(site_dir))
        try:
            import generate_dashboard_data  # type: ignore  # noqa: E402

            with (
                mock.patch.object(generate_dashboard_data, "api_get_paginated", return_value=[]),
                mock.patch.object(
                    generate_dashboard_data,
                    "api_get",
                    side_effect=lambda path, _tok: {} if path.startswith("actions/runs") else [],
                ),
            ):
                snapshot = generate_dashboard_data.build_snapshot("testsha", None)

            self.assertFalse(snapshot["partial"], snapshot.get("warnings"))
            mech = snapshot["mechanics_parity"]
            self.assertIsNotNone(mech)
            self.assertEqual(mech["total_points"], 100)
            self.assertEqual(mech["feature_count"], 35)
            self.assertEqual(len(mech["domains"]), 8)
            first = mech["features"][0]
            for field in ("status", "modes", "notes", "primary_doc", "evidence_count"):
                self.assertIn(field, first)
        finally:
            if str(site_dir) in sys.path:
                sys.path.remove(str(site_dir))


if __name__ == "__main__":
    unittest.main()
