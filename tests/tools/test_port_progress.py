import json
import sys
import tempfile
import unittest
from pathlib import Path
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import port_progress  # noqa: E402


REPO_ROOT = Path(__file__).resolve().parents[2]


class PortProgressTests(unittest.TestCase):
    def test_software_can_complete_with_all_hardware_checks_pending(self) -> None:
        ledger = port_progress.load_and_validate_capabilities(
            REPO_ROOT / "docs" / "port-capabilities.json", REPO_ROOT
        )
        for cap in ledger["capabilities"]:
            cap["stage"] = "complete"
        for check in ledger["hardware_checks"]:
            check["evidence"] = []
        self.assertEqual(port_progress.compute_progress(ledger)["percent"], 100.0)
        self.assertEqual(port_progress.compute_hardware_progress(ledger)["percent"], 0.0)
        weights = ledger["stage_weights"]
        self.assertEqual(weights["emulator-tested"], weights["hardware-verified"])

    def test_real_ledger_and_svg_are_valid_and_fresh(self) -> None:
        ledger = port_progress.load_and_validate_capabilities(
            REPO_ROOT / "docs" / "port-capabilities.json", REPO_ROOT
        )
        summary = port_progress.compute_progress(ledger)
        self.assertEqual(summary["total_points"], 95)
        self.assertGreater(summary["percent"], 0.0)
        self.assertLess(summary["percent"], 100.0)
        self.assertAlmostEqual(summary["earned_points"] + summary["missing_points"], 95.0, places=2)
        self.assertEqual(len(summary["capabilities"]), summary["capability_count"])
        for cap in summary["capabilities"]:
            self.assertIn(cap["stage"], summary["stage_weights"])
            self.assertGreaterEqual(cap["evidence_count"], 1 if cap["stage"] != "planned" else 0)
            if cap["primary_doc"]:
                self.assertTrue((REPO_ROOT / cap["primary_doc"]).is_file())

        svg = port_progress.render_progress_svg(summary)
        self.assertIn(f"{summary['percent']:.1f}%", svg)
        self.assertIn("Remaining software work:", svg)
        hardware = port_progress.compute_hardware_progress(ledger)
        self.assertEqual(hardware["verified"], 1)
        self.assertEqual((REPO_ROOT / "docs/hardware-gauge.svg").read_text(encoding="utf-8"),
                         port_progress.render_hardware_svg(hardware))
        for cat in summary["categories"]:
            self.assertIn(escape(cat["name"]), svg)

    def test_rejects_claimed_stage_without_existing_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ledger_file = root / "caps.json"
            ledger_file.write_text(
                json.dumps(
                    {
                        "stage_weights": {
                            "planned": 0.0,
                            "experimental": 0.15,
                            "host-tested": 0.3,
                            "emulator-tested": 0.55,
                            "hardware-verified": 0.85,
                            "complete": 1.0,
                        },
                        "capabilities": [
                            {
                                "id": "fake-cap",
                                "category": "Core",
                                "name": "Fake Cap",
                                "weight": 100,
                                "stage": "host-tested",
                                "evidence": ["does/not/exist.c"],
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                port_progress.load_and_validate_capabilities(ledger_file, root)

    def test_rejects_weights_not_summing_to_100(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ledger_file = root / "caps.json"
            ledger_file.write_text(
                json.dumps(
                    {
                        "stage_weights": {
                            "planned": 0.0,
                            "experimental": 0.15,
                            "host-tested": 0.3,
                            "emulator-tested": 0.55,
                            "hardware-verified": 0.85,
                            "complete": 1.0,
                        },
                        "capabilities": [
                            {
                                "id": "short-cap",
                                "category": "Core",
                                "name": "Short Cap",
                                "weight": 50,
                                "stage": "planned",
                                "evidence": [],
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaises(ValueError):
                port_progress.load_and_validate_capabilities(ledger_file, root)

    def test_dashboard_generator_exports_capabilities_and_gaps(self) -> None:
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
            progress = snapshot["port_progress"]
            self.assertIsNotNone(progress)
            self.assertEqual(snapshot["hardware_progress"]["total"], 6)
            self.assertEqual(progress["total_points"], 95)
            self.assertEqual(len(progress["capabilities"]), 21)
            self.assertIn("primary_doc", progress["capabilities"][0])
            self.assertIn("evidence_count", progress["capabilities"][0])
        finally:
            if str(site_dir) in sys.path:
                sys.path.remove(str(site_dir))


if __name__ == "__main__":
    unittest.main()
