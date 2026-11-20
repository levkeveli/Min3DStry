"""Stage the pinned TeaVM translation for the 3DS smoke probe."""
import argparse
import shutil
from pathlib import Path
from fix_heap_alignment import fix_heap_alignment
from fix_reflection_lifetime import fix_constructor_roots, fix_reflection_lifetime


def replace(path, old, new):
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise ValueError(f"TeaVM 0.16.0 runtime changed: {path.name}: {old}")
    path.write_text(text.replace(old, new), encoding="utf-8", newline="\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("generated", type=Path)
    parser.add_argument("output", type=Path, help="new staging directory")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("use a new staging directory")
    shutil.copytree(args.generated, args.output)
    fix_heap_alignment(args.output)
    fix_reflection_lifetime(args.output)
    fix_constructor_roots(args.output)
    replacements = {
        "definitions.h": [
            ("#if defined(__GNUC__) || defined(__APPLE__)", "#include <stdalign.h>\n#if (defined(__GNUC__) || defined(__APPLE__)) && !defined(__3DS__)"),
        ],
        "uchar.h": [
            ("#if TEAVM_PSP || defined(__APPLE__)", "#if TEAVM_PSP || defined(__APPLE__) || defined(__3DS__)"),
        ],
        "exceptions.h": [
            ("#if TEAVM_PSP", "#if TEAVM_PSP || defined(__3DS__)"),
        ],
        "arrayclass.c": [
            (
                "static TEAVM_OBJECT_CLASS teavm_dynamicClassPool[TEAVM_DYNAMIC_CLASS_POOL_CAPACITY];",
                "typedef struct { _Alignas(8) TEAVM_OBJECT_CLASS value; } M3dsArrayClassSlot;\n"
                "static M3dsArrayClassSlot teavm_dynamicClassPool[TEAVM_DYNAMIC_CLASS_POOL_CAPACITY];",
            ),
            ("&teavm_dynamicClassPool[teavm_dynamicClassPoolSize++]", "&teavm_dynamicClassPool[teavm_dynamicClassPoolSize++].value"),
            ("&teavm_dynamicClassPool[index].parent", "&teavm_dynamicClassPool[index].value.parent"),
        ],
        "all.c": [
            ('#include "date.c"', '#include "m3ds_date.c"'),
            ('#include "fiber.c"', '#include "m3ds_platform.c"'),
            ('#include "file.c"', '#include "m3ds_file.c"'),
            ('#include "time.c"', ""),
            ('#include "main.c"', '#define main m3ds_teavm_main\n#include "main.c"\n#undef main'),
            ('#include "virtcall.c"', '#include "virtcall.c"\n#include "m3ds_main.c"'),
        ],
        "memory.c": [
            ("void* teavm_gc_heapAddress", "static int64_t teavm_pageSize(void);\nstatic void* teavm_virtualAlloc(int64_t size);\nstatic void teavm_virtualCommit(void* address, int64_t size);\nstatic void teavm_virtualUncommit(void* address, int64_t size);\n\nvoid* teavm_gc_heapAddress"),
        ],
        "log.c": [
            ("            putwchar(c);", "            m3ds_log_codepoint(c);"),
        ],
    }
    for name, changes in replacements.items():
        for old, new in changes:
            replace(args.output / name, old, new)
    for name in ("m3ds_date.c", "m3ds_platform.c", "m3ds_file.c", "m3ds_main.c"):
        shutil.copyfile(Path(__file__).with_name(name), args.output / name)
    includes = args.output / "nativeinclude"
    includes.mkdir()
    shutil.copyfile(Path(__file__).with_name("m3ds_map_file.h"), includes / "m3ds_map_file.h")


if __name__ == "__main__":
    main()
