"""Compact TeaVM 0.16.0 callsites.c into a sparse exception-handler table."""

from __future__ import annotations

import argparse
from pathlib import Path
import re

_DROP_ARRAY_OPEN_RE = re.compile(
    r"^\s*static\s+(?:TeaVM_MethodLocation\s+methodLocations_teavm_callSites|"
    r"TeaVM_CallSiteLocation\s+callSiteLocations_teavm_callSites)"
    r"\s*\[\s*\d*\s*\]\s*=\s*\{(.*)$"
)
_EXCEPTION_OPEN_RE = re.compile(
    r"^\s*static\s+TeaVM_ExceptionHandler\s+exceptionHandlers_teavm_callSites"
    r"\s*\[\s*\d*\s*\]\s*=\s*\{(.*)$"
)
_CALLSITE_OPEN_RE = re.compile(
    r"^\s*TeaVM_CallSite\s+teavm_callSites\s*\[\s*(\d*)\s*\]\s*=\s*\{(.*)$"
)
_ARRAY_CLOSE_RE = re.compile(r"^\s*\}\s*;\s*$")
_CALLSITE_ENTRY_RE = re.compile(
    r"^\s*\{\s*\.firstHandler\s*=\s*"
    r"(NULL|exceptionHandlers_teavm_callSites\s*\+\s*\d+)"
    r"(?:\s*,\s*\.location\s*=\s*(?:NULL|callSiteLocations_teavm_callSites\s*\+\s*\d+))?"
    r"\s*\}\s*,?\s*$"
)


def _consume_block(
    lines: list[str],
    start_idx: int,
    first_tail: str,
    keep: bool,
) -> tuple[int, list[str]]:
    """Consume an array initializer starting at start_idx, returning next index and kept lines."""
    collected = [lines[start_idx]] if keep else []
    if re.match(r"^\s*\}\s*;\s*$", first_tail):
        return start_idx + 1, collected

    i = start_idx + 1
    while i < len(lines):
        if keep:
            collected.append(lines[i])
        if _ARRAY_CLOSE_RE.match(lines[i]):
            return i + 1, collected
        i += 1
    raise ValueError(f"Unterminated array initializer starting at line {start_idx + 1}")


def compact_callsites_text(text: str) -> tuple[str, dict[str, int]]:
    """Compact TeaVM callsites.c text to keep only exception handlers in a sparse table."""
    lines = text.splitlines()
    includes: list[str] = []
    exception_block: list[str] = []
    sparse_entries: list[tuple[int, str]] = []
    saw_callsites = False
    total_count = 0

    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if not stripped:
            i += 1
            continue
        if stripped.startswith("#include "):
            includes.append(line.rstrip())
            i += 1
            continue

        drop_match = _DROP_ARRAY_OPEN_RE.match(line)
        if drop_match:
            i, _ = _consume_block(lines, i, drop_match.group(1), keep=False)
            continue

        exc_match = _EXCEPTION_OPEN_RE.match(line)
        if exc_match:
            if exception_block:
                raise ValueError("Duplicate exceptionHandlers_teavm_callSites definition")
            i, exception_block = _consume_block(lines, i, exc_match.group(1), keep=True)
            continue

        cs_match = _CALLSITE_OPEN_RE.match(line)
        if cs_match:
            if saw_callsites:
                raise ValueError("Duplicate teavm_callSites definition")
            saw_callsites = True
            declared_raw, first_tail = cs_match.group(1), cs_match.group(2)
            declared_count = int(declared_raw) if declared_raw else None

            if re.match(r"^\s*\}\s*;\s*$", first_tail):
                if declared_count not in (None, 0):
                    raise ValueError(
                        f"teavm_callSites length mismatch: declared {declared_count}, parsed 0"
                    )
                i += 1
                continue
            if first_tail.strip():
                raise ValueError(
                    f"Unexpected inline initializer content on line {i + 1}: {line!r}"
                )

            i += 1
            closed = False
            while i < len(lines):
                entry_line = lines[i]
                if _ARRAY_CLOSE_RE.match(entry_line):
                    closed = True
                    i += 1
                    break
                if not entry_line.strip():
                    i += 1
                    continue
                entry_match = _CALLSITE_ENTRY_RE.match(entry_line)
                if not entry_match:
                    raise ValueError(
                        f"Unrecognized teavm_callSites entry on line {i + 1}: {entry_line!r}"
                    )
                handler_expr = " ".join(entry_match.group(1).split())
                if handler_expr != "NULL":
                    sparse_entries.append((total_count, handler_expr))
                total_count += 1
                i += 1

            if not closed:
                raise ValueError("Unterminated teavm_callSites array initializer")
            if declared_count is not None and total_count != declared_count:
                raise ValueError(
                    f"teavm_callSites length mismatch: declared {declared_count}, parsed {total_count}"
                )
            continue

        raise ValueError(f"Unexpected top-level statement in callsites.c on line {i + 1}: {line!r}")

    if not saw_callsites:
        raise ValueError("Missing teavm_callSites definition in callsites.c")

    out_lines: list[str] = ['#include "stack.h"']
    for inc in includes:
        if inc != '#include "stack.h"':
            out_lines.append(inc)
    out_lines.append("")

    if exception_block:
        out_lines.extend(line.rstrip() for line in exception_block)
        out_lines.append("")

    out_lines.append(
        "TeaVM_CallSite m3ds_empty_callsite = { .firstHandler = NULL, .location = NULL };"
    )
    if sparse_entries:
        out_lines.append(f"M3dsSparseCallSite m3ds_sparse_callsites[{len(sparse_entries)}] = {{")
        for idx, (cs_id, handler_expr) in enumerate(sparse_entries):
            comma = "," if idx + 1 < len(sparse_entries) else ""
            out_lines.append(
                f"    {{ {cs_id}, {{ .firstHandler = {handler_expr}, .location = NULL }} }}{comma}"
            )
        out_lines.append("};")
        out_lines.append(f"const size_t m3ds_sparse_callsite_count = {len(sparse_entries)}u;")
    else:
        out_lines.append("M3dsSparseCallSite m3ds_sparse_callsites[1] = {")
        out_lines.append("    { -1, { .firstHandler = NULL, .location = NULL } }")
        out_lines.append("};")
        out_lines.append("const size_t m3ds_sparse_callsite_count = 0u;")

    stats = {"total": total_count, "handlers": len(sparse_entries)}
    return "\n".join(out_lines) + "\n", stats


def compact_callsites(output_dir: Path) -> dict[str, int]:
    """Read, compact, and overwrite output_dir/callsites.c cleanly."""
    callsites_path = output_dir / "callsites.c"
    compacted, stats = compact_callsites_text(callsites_path.read_text(encoding="utf-8"))
    callsites_path.write_text(compacted, encoding="utf-8", newline="\n")
    return stats


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="directory containing callsites.c")
    args = parser.parse_args()
    stats = compact_callsites(args.output)
    print(f"compacted callsites.c: total={stats['total']} handlers={stats['handlers']}")


if __name__ == "__main__":
    main()
