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
    def test_real_ledger_and_svg_are_valid_and_fresh(self) -> None:
        ledger = port_progress.load_and_validate_capabilities(
            REPO_ROOT / "docs" / "port-capabilities.json", REPO_ROOT
        )
        summary = port_progress.compute_progress(ledger)
        self.assertEqual(summary["total_points"], 100)
        self.assertGreater(summary["percent"], 0.0)
        self.assertLess(summary["percent"], 100.0)

        svg = port_progress.render_progress_svg(summary)
        self.assertIn(f"{summary['percent']:.1f}%", svg)
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


if __name__ == "__main__":
    unittest.main()
