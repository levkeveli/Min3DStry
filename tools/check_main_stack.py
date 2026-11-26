#!/usr/bin/env python3
"""Reject an ARM main() frame that would squeeze the embedded loader stack."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys

SUB_SP_RE = re.compile(r"\bsub\s+sp,\s+sp,\s+#(\d+)")
PUSH_RE = re.compile(r"\b(push|vpush)\s+\{([^}]*)\}")
MAIN_RE = re.compile(r"<main>:")
CALL_RE = re.compile(r"\bbl\b")


def _register_count(spec: str) -> tuple[int, int]:
    """Return (integer registers, double registers) in a push list."""
    ints = 0
    doubles = 0
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if part.startswith("d"):
            if "-d" in part:
                first, last = part.split("-d", 1)
                doubles += int(last) - int(first.lstrip("d")) + 1
            else:
                doubles += 1
        else:
            ints += 1
    return ints, doubles


def parse_main_frame(disassembly: str) -> int | None:
    """Parse bytes reserved in main's prologue before its first call."""
    in_main = False
    frame = 0
    for line in disassembly.splitlines():
        if MAIN_RE.search(line):
            in_main = True
            continue
        if not in_main:
            continue
        if CALL_RE.search(line):
            break
        match = SUB_SP_RE.search(line)
        if match:
            frame += int(match.group(1))
            continue
        match = PUSH_RE.search(line)
        if match:
            ints, doubles = _register_count(match.group(2))
            frame += ints * 4 + doubles * 8
    return frame if in_main else None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("elf")
    parser.add_argument("--max-bytes", type=int, default=4096)
    parser.add_argument("--objdump", default="arm-none-eabi-objdump")
    args = parser.parse_args(argv)
    if args.max_bytes <= 0:
        parser.error("--max-bytes must be > 0")
    try:
        result = subprocess.run(
            [args.objdump, "-d", args.elf],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"main_stack: FAIL cannot disassemble {args.elf}: {error}", file=sys.stderr)
        return 1
    frame = parse_main_frame(result.stdout)
    if frame is None:
        print("main_stack: FAIL no main symbol in disassembly", file=sys.stderr)
        return 1
    print(f"main_stack: bytes={frame} limit={args.max_bytes}")
    if frame > args.max_bytes:
        print(
            f"main_stack: FAIL {frame} > {args.max_bytes} bytes; move large "
            "locals to static storage before the loader runs again",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
