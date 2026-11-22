"""Stage the pinned TeaVM translation for the 3DS smoke probe."""
import argparse
import os
import shutil
from pathlib import Path
from compact_callsites import compact_callsites, prune_unused_strings
from fix_gc_monitors import fix_gc_monitors
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
    parser.add_argument(
        "--embed", action="store_true",
        help="stage a loader without the probe main or 3DS heap symbols")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("use a new staging directory")
    shutil.copytree(args.generated, args.output)
    fix_heap_alignment(args.output)
    fix_gc_monitors(args.output)
    fix_reflection_lifetime(args.output)
    fix_constructor_roots(args.output)
    replacements = {
        # TeaVM can reuse the same local for an array and a string in different
        # parts of a coroutine. Both values are Java object references.
        "strings.h": [
            ("#define TEAVM_GET_STRING(i) teavm_stringPool[i]",
             "#define TEAVM_GET_STRING(i) ((void*)teavm_stringPool[i])"),
        ],
        "c/o/t/r/GC.c": [
            ('#include "RelocationBlock.h"', '#include "RelocationBlock.h"\n#include "../../../../m3ds_gc_relocations.h"'),
            (
                "teavm_local_6 = TEAVM_STRUCTURE_ADD(cls_otr_FreeChunkHolder, sfld_otr_GC_currentChunkPointer, teavm_local_3);",
                "teavm_local_6 = m3ds_gc_begin_relocations(\n"
                "        TEAVM_STRUCTURE_ADD(cls_otr_FreeChunkHolder, sfld_otr_GC_currentChunkPointer, teavm_local_3));",
            ),
            (
                "                    teavm_local_6 = TEAVM_STRUCTURE_ADD(cls_otr_RelocationBlock, teavm_local_6, INT32_C(1));",
                "                    teavm_local_6 = m3ds_gc_append_relocation(teavm_local_6, &teavm_local_4);",
            ),
            (
                "\n            teavm_local_6 = TEAVM_STRUCTURE_ADD(cls_otr_RelocationBlock, teavm_local_6, INT32_C(1));",
                "\n            teavm_local_6 = m3ds_gc_append_relocation(teavm_local_6, &teavm_local_4);",
            ),
            (
                "teavm_local_5 = TEAVM_STRUCTURE_ADD(cls_otr_FreeChunkHolder, sfld_otr_GC_currentChunkPointer, teavm_local_2);",
                "teavm_local_5 = m3ds_gc_relocations;",
            ),
            (
                "teavm_local_2 = TEAVM_STRUCTURE_ADD(cls_otr_FreeChunkHolder, teavm_local_1, sfld_otr_GC_freeChunks);",
                "teavm_local_2 = m3ds_gc_relocations;",
            ),
            (
                "    meth_otr_GC_putNewFreeChunks();",
                "    meth_otr_GC_putNewFreeChunks();\n    m3ds_gc_end_relocations();",
            ),
            (
                "    TEAVM_FIELD(teavm_local_2, cls_otr_FreeChunkHolder, fld_value) = teavm_local_1;",
                "    m3ds_gc_check_free_chunk(teavm_local_2);\n"
                "    TEAVM_FIELD(teavm_local_2, cls_otr_FreeChunkHolder, fld_value) = teavm_local_1;",
            ),
            (
                "            TEAVM_FIELD(teavm_local_1, cls_otr_FreeChunkHolder, fld_value) = teavm_local_3;",
                "            m3ds_gc_check_free_chunk(teavm_local_1);\n"
                "            TEAVM_FIELD(teavm_local_1, cls_otr_FreeChunkHolder, fld_value) = teavm_local_3;",
            ),
            (
                "                TEAVM_FIELD(TEAVM_STRUCTURE_ADD(cls_otr_FreeChunkHolder, sfld_otr_GC_currentChunkPointer, "
                "sfld_otr_GC_freeChunks), cls_otr_FreeChunkHolder, fld_value) = sfld_otr_GC_lastChunk;",
                "                void *slot = TEAVM_STRUCTURE_ADD(cls_otr_FreeChunkHolder, sfld_otr_GC_currentChunkPointer, "
                "sfld_otr_GC_freeChunks);\n"
                "                m3ds_gc_check_free_chunk(slot);\n"
                "                TEAVM_FIELD(slot, cls_otr_FreeChunkHolder, fld_value) = sfld_otr_GC_lastChunk;",
            ),
        ],
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
                '#include "m3ds_array_class_pool.h"',
            ),
            (
                "    if (teavm_dynamicClassPoolSize + 1 >= TEAVM_DYNAMIC_CLASS_POOL_CAPACITY) {\n"
                "        teavm_printWString(L\"Metaspace size\\n\");\n"
                "        abort();\n"
                "    }\n"
                "    TEAVM_OBJECT_CLASS* ptr = &teavm_dynamicClassPool[teavm_dynamicClassPoolSize++];",
                "    TEAVM_OBJECT_CLASS* ptr = m3ds_array_class_alloc(&teavm_dynamicClassPoolSize);",
            ),
            (
                "&teavm_dynamicClassPool[index].parent",
                "&m3ds_array_class_get(index)->parent",
            ),
        ],
        "all.c": [
            ('#include "date.c"', '#include "m3ds_date.c"'),
            ('#include "fiber.c"', '#include "m3ds_platform.c"'),
            ('#include "file.c"', '#include "m3ds_file.c"'),
            ('#include "time.c"', ""),
            ('#include "main.c"', '#define main m3ds_teavm_main\n#include "main.c"\n#undef main'),
        ] + ([] if args.embed else [
            ('#include "virtcall.c"', '#include "virtcall.c"\n#include "m3ds_main.c"'),
        ]),
        "memory.c": [
            ("void* teavm_gc_heapAddress", "static int64_t teavm_pageSize(void);\nstatic void* teavm_virtualAlloc(int64_t size);\nstatic void teavm_virtualCommit(void* address, int64_t size);\nstatic void teavm_virtualUncommit(void* address, int64_t size);\n\nvoid* teavm_gc_heapAddress"),
        ],
        "heaptrace.c": [
            (
                "void teavm_outOfMemory() {",
                "void teavm_outOfMemory() {\n"
                "    static const char marker[] = \"UPSTREAM_MEMORY managed-heap-exhausted\";\n"
                "    svcOutputDebugString(marker, sizeof(marker) - 1);",
            ),
        ],
        "log.c": [
            ("            putwchar(c);", "            m3ds_log_codepoint(c);"),
        ],
    }
    if not args.embed:
        replacements["main.c"] = [
            (
                "int main(int argc, char** argv) {",
                "static void m3ds_init_heap(int64_t minimum, int64_t maximum);\n\n"
                "int main(int argc, char** argv) {",
            ),
            ("    teavm_initHeap(", "    m3ds_init_heap("),
        ]
    keep_callsite_locations = os.environ.get("M3DS_KEEP_CALLSITE_LOCATIONS", "0") == "1"
    if not keep_callsite_locations:
        replacements["stack.h"] = [
            (
                "    extern TeaVM_CallSite teavm_callSites[];\n"
                "    #define TEAVM_FIND_CALLSITE(id, frame) (teavm_callSites + id)",
                '    #include "m3ds_callsites.h"\n'
                "    #define TEAVM_FIND_CALLSITE(id, frame) m3ds_find_callsite(id)",
            ),
        ]
    for name, changes in replacements.items():
        for old, new in changes:
            replace(args.output / name, old, new)
    for name in ("m3ds_date.c", "m3ds_platform.c", "m3ds_file.c", "m3ds_main.c",
                 "m3ds_gc_relocations.h", "m3ds_array_class_pool.h", "m3ds_callsites.h"):
        if args.embed and name == "m3ds_main.c":
            continue
        shutil.copyfile(Path(__file__).with_name(name), args.output / name)
    if args.embed:
        platform = args.output / "m3ds_platform.c"
        text = platform.read_text(encoding="utf-8")
        text = text.replace("u32 __stacksize__ = 256 * 1024;\n", "")
        text = text.replace("u32 __ctru_linear_heap_size = 4 * 1024 * 1024;\n", "")
        platform.write_text(text, encoding="utf-8")
    includes = args.output / "nativeinclude"
    includes.mkdir()
    shutil.copyfile(Path(__file__).with_name("m3ds_map_file.h"), includes / "m3ds_map_file.h")
    if not keep_callsite_locations:
        compact_callsites(args.output)
        prune_unused_strings(args.output)


if __name__ == "__main__":
    main()
