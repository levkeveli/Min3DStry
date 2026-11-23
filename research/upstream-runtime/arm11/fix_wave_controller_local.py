#!/usr/bin/env python3
"""Keep the generated wave probe's controller reference in memory around setjmp."""

import argparse
from pathlib import Path
import re


CONTROLLER_LOCAL = re.compile(
    r"^(?P<header>void\* meth_mi_TypeIO_readController\([^\n]+\) \{\n)"
    r"    void\* (?:volatile )?teavm_local_3;$",
    re.MULTILINE,
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("generated", type=Path)
    source = parser.parse_args().generated / "c/m/i/TypeIO.c"
    original = source.read_text(encoding="utf-8")
    # GCC 13 fails to coalesce this reused pointer across TeaVM's try/catch
    # setjmp edges. A volatile pointer gives it a fixed memory location.
    updated, count = CONTROLLER_LOCAL.subn(r"\g<header>    void* volatile teavm_local_3;", original)
    if count != 1:
        raise ValueError(f"Expected one controller reference in {source}, found {count}")
    if updated != original:
        source.write_text(updated, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
