"""Keep TeaVM array payload alignment stable across ARM11 heap moves."""

import re


CLASS_SIZE = re.compile(
    rb"(\.size = \(int32_t\) \(intptr_t\) TEAVM_ALIGN\(sizeof\([^()\r\n]+\), )sizeof\(void\*\)(\))"
)
ARRAY_SIZE_ALIGNMENT = re.compile(
    rb"(TEAVM_ALIGN\([^\r\n]*sizeof\(TeaVM_Array\)[^\r\n]*, )sizeof\(void\*\)(\)\);)"
)


def fix_heap_alignment(generated):
    # The heap base is page aligned. Rounding every managed object up to
    # eight bytes prevents compaction from changing the offset to a long[]
    # or double[] payload on a platform with four-byte reference slots.
    count = 0
    for path in (generated / "c").rglob("*.c"):
        contents = path.read_bytes()
        updated, matches = CLASS_SIZE.subn(rb"\g<1>TEAVM_HEAP_ALIGNMENT\2", contents)
        if matches:
            path.write_bytes(updated)
            count += matches
    if not count:
        raise ValueError("No generated TeaVM managed class sizes found")

    runtime = generated / "c" / "o" / "t" / "r"
    for name in ("Allocator.c", "GC.c"):
        path = runtime / name
        contents = path.read_bytes()
        updated, matches = ARRAY_SIZE_ALIGNMENT.subn(rb"\g<1>TEAVM_HEAP_ALIGNMENT\2", contents)
        if matches != 1:
            raise ValueError(f"Unrecognized TeaVM array size calculation in {path}")
        path.write_bytes(updated)

    header = generated / "core.h"
    contents = header.read_bytes()
    marker = b"#define TEAVM_ALIGN(addr, alignment)"
    if contents.count(marker) != 1:
        raise ValueError("Unrecognized TeaVM core alignment macros")
    header.write_bytes(contents.replace(marker, b"#define TEAVM_HEAP_ALIGNMENT 8\n" + marker))
