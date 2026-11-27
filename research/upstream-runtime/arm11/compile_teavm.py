#!/usr/bin/env python3
"""Compile TeaVM's C sources in balanced translation units."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import re
import subprocess
import time


PREAMBLE = "#define _XOPEN_SOURCE\n#define __USE_XOPEN\n#define _GNU_SOURCE\n"
GENERATED_INCLUDE = re.compile(r'#include "(c/[^"]+\.c)"')


def partition(source, output, count):
    amalgamation = source.read_text(encoding="utf-8")
    if not amalgamation.startswith(PREAMBLE) or re.search(r"^\s*#\s*if", amalgamation, re.M):
        raise ValueError("Expected TeaVM all.c with its standard feature defines and flat includes")

    runtime = [PREAMBLE]
    generated = []
    for line in amalgamation[len(PREAMBLE):].splitlines():
        match = GENERATED_INCLUDE.fullmatch(line)
        if match:
            size = (source.parent / match[1]).stat().st_size
            generated.append((size, line))
        else:
            runtime.append(line + "\n")

    # TeaVM emits this reflection helper as an external function in a header.
    # Its callers include core.h, so give each translation unit its own copy.
    header = source.parent / "core.h"
    text = header.read_text(encoding="utf-8")
    definition = "    int32_t teavm_initNewInstance(void* cls, void* obj) {"
    if definition in text:
        header.write_text(text.replace(definition, "    static inline " + definition.lstrip()), encoding="utf-8")

    # Keep the platform/runtime includes together, in their original order.
    units = [output / "runtime.c"]
    units[0].write_text("".join(runtime), encoding="utf-8")
    groups = [[] for _ in range(min(count, len(generated)))]
    sizes = [0] * len(groups)
    for size, include in sorted(generated, reverse=True):
        index = min(range(len(groups)), key=sizes.__getitem__)
        groups[index].append(include)
        sizes[index] += size
    for index, group in enumerate(groups):
        unit = output / f"classes-{index:02d}.c"
        unit.write_text(PREAMBLE + "\n".join(group) + "\n", encoding="utf-8")
        units.append(unit)
    return units


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="TeaVM's generated all.c")
    parser.add_argument("output", type=Path, help="new object directory")
    parser.add_argument("compiler", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not args.compiler:
        parser.error("provide a compiler command and its compile flags")
    cpus = len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else os.cpu_count()
    jobs = int(os.environ.get("M3DS_TEAVM_JOBS", min(cpus or 1, 4)))
    if jobs < 1:
        parser.error("M3DS_TEAVM_JOBS must be positive")
    start = time.monotonic()
    args.output.mkdir()
    units = partition(args.source, args.output, jobs * 2) if jobs > 1 else [args.source]

    def compile_unit(unit):
        obj = args.output / unit.with_suffix(".o").name
        subprocess.run(
            [*args.compiler, "-iquote", str(args.source.parent), "-c", unit, "-o", obj],
            check=True,
        )

    print(f"Compiling TeaVM C in {len(units)} units with up to {jobs} workers", flush=True)
    try:
        with ThreadPoolExecutor(max_workers=jobs) as pool:
            list(pool.map(compile_unit, units))
    except subprocess.CalledProcessError as error:
        raise SystemExit(f"TeaVM C compilation failed (compiler exit {error.returncode})") from None
    print(f"TeaVM C compilation finished in {time.monotonic() - start:.1f}s", flush=True)


if __name__ == "__main__":
    main()
