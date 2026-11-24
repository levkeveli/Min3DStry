#!/usr/bin/env python3
"""Check that an Azahar verification report describes the expected artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any


def file_sha256(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def check_report(
    report_path: Path,
    artifact_path: Path,
    expected_sha256: str | None = None,
) -> str:
    """Validate the report's artifact identity against the file on disk."""
    data: Any = json.loads(report_path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("report root must be an object")
    artifact = data.get("artifact")
    if not isinstance(artifact, dict):
        raise ValueError("report has no artifact metadata")

    if not artifact_path.is_file():
        raise ValueError(f"artifact not found: {artifact_path}")
    actual_sha = file_sha256(artifact_path)
    actual_bytes = artifact_path.stat().st_size

    recorded_sha = str(artifact.get("sha256") or "").lower()
    if recorded_sha != actual_sha:
        raise ValueError(
            f"report sha256 {recorded_sha or '<missing>'} does not match artifact {actual_sha}"
        )
    try:
        recorded_bytes = int(artifact.get("bytes"))
    except (TypeError, ValueError) as exc:
        raise ValueError("report artifact bytes is missing or invalid") from exc
    if recorded_bytes != actual_bytes:
        raise ValueError(
            f"report bytes {recorded_bytes} does not match artifact {actual_bytes}"
        )
    if expected_sha256 is not None and actual_sha != expected_sha256.lower():
        raise ValueError(
            f"artifact {actual_sha} does not match expected build sha256 {expected_sha256.lower()}"
        )
    return actual_sha


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True)
    parser.add_argument("--artifact", required=True)
    parser.add_argument("--sha256", default=None)
    args = parser.parse_args()

    try:
        actual = check_report(
            Path(args.report), Path(args.artifact), args.sha256
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"AZAHAR_PROVENANCE_FAIL {exc}", file=sys.stderr)
        return 1
    print(f"AZAHAR_PROVENANCE_OK sha256={actual}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
