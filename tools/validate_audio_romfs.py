#!/usr/bin/env python3
"""Validate the generated upstream-audio RomFS before packaging the .3dsx."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def validate(root: Path, minimum_entries: int = 9) -> tuple[int, int]:
    manifest_path = root / "audio" / "audio-manifest.json"
    if not manifest_path.is_file():
        raise ValueError(f"missing audio manifest: {manifest_path}")
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    entries = data.get("entries")
    if not isinstance(entries, list) or len(entries) < minimum_entries:
        raise ValueError(
            f"expected at least {minimum_entries} audio entries, got "
            f"{len(entries) if isinstance(entries, list) else 'none'}"
        )
    total = 0
    for entry in entries:
        name = entry.get("output")
        expected = entry.get("bytes")
        if not name or not isinstance(expected, int):
            raise ValueError(f"invalid manifest entry: {entry!r}")
        path = root / "audio" / name
        if not path.is_file():
            raise ValueError(f"missing PCM file: {path}")
        actual = path.stat().st_size
        if actual != expected:
            raise ValueError(f"size mismatch for {path}: {actual} != {expected}")
        total += actual
    return len(entries), total


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("romfs", type=Path, help="RomFS output directory")
    parser.add_argument("--minimum-entries", type=int, default=9)
    args = parser.parse_args(argv)
    try:
        count, total = validate(args.romfs, args.minimum_entries)
    except (OSError, ValueError) as error:
        print(f"audio_romfs: FAIL {error}", file=sys.stderr)
        return 1
    print(f"audio_romfs: OK entries={count} bytes={total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
