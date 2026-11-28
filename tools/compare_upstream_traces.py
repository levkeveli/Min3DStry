#!/usr/bin/env python3
"""Compare ordered gameplay checkpoints from pinned JVM and port probe logs."""

import argparse
import json
from pathlib import Path
import re
from itertools import zip_longest


def read_checkpoints(path: Path, pattern: re.Pattern[str]) -> list[str]:
    records = []
    for line in path.read_text(encoding="utf-8").splitlines():
        # Accept raw probe output and GitHub Actions job logs. Anchoring after
        # the runner timestamp excludes echoed grep commands and expectations.
        message = re.sub(r"^.*\t\d{4}-\d\d-\d\dT[^ ]+Z ", "", line)
        if pattern.match(message):
            records.append(message.rstrip())
    return records


def compare(reference: list[str], port: list[str]) -> dict:
    differences = []
    matched = 0
    for index, (expected, actual) in enumerate(zip_longest(reference, port), start=1):
        if expected == actual:
            matched += 1
        else:
            differences.append({"checkpoint": index, "reference": expected, "port": actual})
    return {
        "reference_checkpoints": len(reference),
        "port_checkpoints": len(port),
        "matched": matched,
        "differences": differences,
        "passed": bool(reference) and not differences,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("port", type=Path)
    parser.add_argument("--select", required=True, help="regex matching the start of each checkpoint")
    args = parser.parse_args()
    try:
        pattern = re.compile(args.select)
        reference = read_checkpoints(args.reference, pattern)
        port = read_checkpoints(args.port, pattern)
    except (OSError, ValueError, re.error) as error:
        parser.error(str(error))
    result = compare(reference, port)
    print(json.dumps(result, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
