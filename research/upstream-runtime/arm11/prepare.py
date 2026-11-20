"""Stage the pinned TeaVM translation for the 3DS smoke probe."""
import argparse
import shutil
from pathlib import Path


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
    replacements = {
        "definitions.h": [
            ("#if defined(__GNUC__) || defined(__APPLE__)", "#include <stdalign.h>\n#if (defined(__GNUC__) || defined(__APPLE__)) && !defined(__3DS__)"),
        ],
        "uchar.h": [
            ("#if TEAVM_PSP || defined(__APPLE__)", "#if TEAVM_PSP || defined(__APPLE__) || defined(__3DS__)"),
        ],
        "all.c": [
            ('#include "date.c"', ""),
            ('#include "fiber.c"', '#include "m3ds_platform.c"'),
            ('#include "file.c"', ""),
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
    for name in ("m3ds_platform.c", "m3ds_main.c"):
        shutil.copyfile(Path(__file__).with_name(name), args.output / name)


if __name__ == "__main__":
    main()
