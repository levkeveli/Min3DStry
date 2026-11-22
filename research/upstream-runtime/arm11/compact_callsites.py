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
_STRING_REF_RE = re.compile(r"\bTEAVM_GET_STRING(?:_ADDRESS)?\s*\(\s*(\d+)\s*\)")
_STRING_RAW_RE = re.compile(r"\b(?:teavm_stringPool|TEAVM_GET_STRING(?:_ADDRESS)?)\b")
_STRING_POOL_OPEN_RE = re.compile(
    r"^\s*TeaVM_String\*\s+teavm_stringPool\s*\[\s*(\d+)\s*\]\s*=\s*\{\s*$"
)
_STRING_ENTRY_START_RE = re.compile(r"^\s*(?:TEAVM_STRING(?:_FROM_CODES)?\s*\(|NULL\b)")
_STRING_ENTRY_CONT_RE = re.compile(r'^\s*(?:u?"|\d)')
_STRING_INIT_LOOP_RE = re.compile(
    r"^(\s*for\s*\(\s*int\s+i\s*=\s*0\s*;\s*i\s*<\s*)(\d+)(\s*;\s*\+\+i\s*\)\s*\{\s*)$"
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


def collect_live_string_refs(output_dir: Path) -> tuple[set[int], list[str]]:
    """Scan all generated/staged C and header files for teavm_stringPool references."""
    live_refs: set[int] = set()
    non_constant_files: list[str] = []

    for path in sorted(output_dir.rglob("*")):
        if not path.is_file() or path.suffix not in (".c", ".h"):
            continue
        rel = path.relative_to(output_dir).as_posix()
        if rel in ("strings.c", "strings.h"):
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in _STRING_REF_RE.finditer(text):
            live_refs.add(int(match.group(1)))
        stripped = _STRING_REF_RE.sub("", text)
        if _STRING_RAW_RE.search(stripped):
            non_constant_files.append(rel)

    return live_refs, non_constant_files


def _split_trailing_comment(line: str) -> tuple[str, str]:
    in_quotes = False
    escaped = False
    i = 0
    n = len(line)
    while i < n:
        ch = line[i]
        if escaped:
            escaped = False
        elif ch == "\\" and in_quotes:
            escaped = True
        elif ch == '"':
            in_quotes = not in_quotes
        elif not in_quotes and line.startswith("//", i):
            return line[:i], line[i:]
        i += 1
    return line, ""


def _format_kept_string_entry(entry_lines: list[str], need_comma: bool) -> list[str]:
    formatted = [line.rstrip() for line in entry_lines]
    code_part, comment_part = _split_trailing_comment(formatted[-1])
    code_clean = code_part.rstrip()
    if code_clean.endswith(","):
        code_clean = code_clean[:-1].rstrip()
    comma = "," if need_comma else ""
    formatted[-1] = f"{code_clean}{comma} {comment_part}" if comment_part else f"{code_clean}{comma}"
    return formatted


def prune_unused_strings_text(text: str, live_refs: set[int]) -> tuple[str, dict[str, int]]:
    """Prune unreferenced string pool tail entries and replace interior dead slots with NULL."""
    lines = text.splitlines()
    open_idx: int | None = None
    declared_count = 0
    for idx, line in enumerate(lines):
        match = _STRING_POOL_OPEN_RE.match(line)
        if match:
            if open_idx is not None:
                raise ValueError("Duplicate teavm_stringPool definition in strings.c")
            open_idx = idx
            declared_count = int(match.group(1))
    if open_idx is None:
        raise ValueError("Missing teavm_stringPool definition in strings.c")

    close_idx: int | None = None
    for idx in range(open_idx + 1, len(lines)):
        if _ARRAY_CLOSE_RE.match(lines[idx]):
            close_idx = idx
            break
    if close_idx is None:
        raise ValueError("Unterminated teavm_stringPool initializer in strings.c")

    entries: list[list[str]] = []
    for idx in range(open_idx + 1, close_idx):
        line = lines[idx]
        if not line.strip():
            continue
        if _STRING_ENTRY_START_RE.match(line):
            entries.append([line.rstrip()])
        elif entries and _STRING_ENTRY_CONT_RE.match(line):
            entries[-1].append(line.rstrip())
        else:
            raise ValueError(
                f"Unexpected line inside teavm_stringPool initializer at line {idx + 1}: {line!r}"
            )

    if len(entries) != declared_count:
        raise ValueError(
            f"teavm_stringPool length mismatch: declared {declared_count}, parsed {len(entries)}"
        )

    for ref in live_refs:
        if ref < 0 or ref >= declared_count:
            raise ValueError(
                f"Out-of-range live string index {ref} for teavm_stringPool[{declared_count}]"
            )

    new_count = (max(live_refs) + 1) if live_refs else 0

    footer_lines: list[str] = []
    loop_rewrites = 0
    for line in lines[close_idx + 1 :]:
        loop_match = _STRING_INIT_LOOP_RE.match(line)
        if loop_match:
            if int(loop_match.group(2)) != declared_count:
                raise ValueError(
                    f"teavm_initStringPool loop bound {loop_match.group(2)} != declared {declared_count}"
                )
            loop_rewrites += 1
            footer_lines.append(f"{loop_match.group(1)}{new_count}{loop_match.group(3)}")
        else:
            footer_lines.append(line.rstrip())

    if loop_rewrites != 1:
        raise ValueError(
            f"Expected 1 teavm_initStringPool loop in strings.c, found {loop_rewrites}"
        )

    out_lines: list[str] = [line.rstrip() for line in lines[:open_idx]]
    if new_count == 0:
        out_lines.append("TeaVM_String* teavm_stringPool[1] = {")
        out_lines.append("    NULL")
        out_lines.append("};")
    else:
        out_lines.append(f"TeaVM_String* teavm_stringPool[{new_count}] = {{")
        for idx in range(new_count):
            need_comma = idx + 1 < new_count
            if idx in live_refs:
                out_lines.extend(_format_kept_string_entry(entries[idx], need_comma))
            else:
                comma = "," if need_comma else ""
                out_lines.append(f"    NULL{comma} // string #{idx} (pruned)")
        out_lines.append("};")
    out_lines.extend(footer_lines)

    stats = {
        "total": declared_count,
        "kept": len(live_refs),
        "pruned_tail": declared_count - new_count,
        "pruned_interior": new_count - len(live_refs),
        "skipped_non_constant": 0,
    }
    return "\n".join(out_lines) + "\n", stats


def prune_unused_strings(output_dir: Path) -> dict[str, int]:
    """Prune callsite-only unreferenced strings from output_dir/strings.c when safe."""
    strings_path = output_dir / "strings.c"
    if not strings_path.is_file():
        return {
            "total": 0,
            "kept": 0,
            "pruned_tail": 0,
            "pruned_interior": 0,
            "skipped_non_constant": 0,
        }

    text = strings_path.read_text(encoding="utf-8")
    live_refs, non_constant_files = collect_live_string_refs(output_dir)
    if non_constant_files:
        declared_count = 0
        for line in text.splitlines():
            match = _STRING_POOL_OPEN_RE.match(line)
            if match:
                declared_count = int(match.group(1))
                break
        return {
            "total": declared_count,
            "kept": declared_count,
            "pruned_tail": 0,
            "pruned_interior": 0,
            "skipped_non_constant": len(non_constant_files),
        }

    pruned, stats = prune_unused_strings_text(text, live_refs)
    strings_path.write_text(pruned, encoding="utf-8", newline="\n")
    return stats


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="directory containing callsites.c and strings.c")
    args = parser.parse_args()
    stats = compact_callsites(args.output)
    string_stats = prune_unused_strings(args.output)
    print(f"compacted callsites.c: total={stats['total']} handlers={stats['handlers']}")
    print(
        "pruned strings.c: "
        f"total={string_stats['total']} kept={string_stats['kept']} "
        f"pruned_tail={string_stats['pruned_tail']} "
        f"pruned_interior={string_stats['pruned_interior']} "
        f"skipped_non_constant={string_stats['skipped_non_constant']}"
    )


if __name__ == "__main__":
    main()
