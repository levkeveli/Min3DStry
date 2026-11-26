import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import check_azahar_report  # noqa: E402


class CheckAzaharReportTests(unittest.TestCase):
    def _fixture(self, payload: bytes, sha: str | None = None, size: int | None = None):
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        artifact = root / "min3dstry.3dsx"
        artifact.write_bytes(payload)
        report = root / "report.json"
        report.write_text(
            json.dumps(
                {
                    "artifact": {
                        "path": str(artifact),
                        "bytes": len(payload) if size is None else size,
                        "sha256": check_azahar_report.file_sha256(artifact)
                        if sha is None
                        else sha,
                    }
                }
            ),
            encoding="utf-8",
        )
        self.addCleanup(temp.cleanup)
        return report, artifact

    def test_matching_report_passes(self) -> None:
        report, artifact = self._fixture(b"min3dstry")
        sha = check_azahar_report.check_report(report, artifact)
        self.assertEqual(sha, check_azahar_report.file_sha256(artifact))

    def test_expected_sha_must_match(self) -> None:
        report, artifact = self._fixture(b"min3dstry")
        with self.assertRaises(ValueError):
            check_azahar_report.check_report(report, artifact, "00" * 32)

    def test_wrong_recorded_sha_is_rejected(self) -> None:
        report, artifact = self._fixture(b"min3dstry", sha="11" * 32)
        with self.assertRaises(ValueError):
            check_azahar_report.check_report(report, artifact)

    def test_wrong_recorded_size_is_rejected(self) -> None:
        report, artifact = self._fixture(b"min3dstry", size=1)
        with self.assertRaises(ValueError):
            check_azahar_report.check_report(report, artifact)

    def test_missing_artifact_metadata_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            artifact = root / "min3dstry.3dsx"
            artifact.write_bytes(b"x")
            report = root / "report.json"
            report.write_text("{}", encoding="utf-8")
            with self.assertRaises(ValueError):
                check_azahar_report.check_report(report, artifact)


if __name__ == "__main__":
    unittest.main()
