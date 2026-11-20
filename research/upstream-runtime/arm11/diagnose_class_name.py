from pathlib import Path

path = Path("research/upstream-runtime/build/generated/teavm/c/c/j/l/Class.c")
source = path.read_text(encoding="utf-8")
point = "    teavm_local_2 = TEAVM_FIELD(teavm_this_, cls_jl_Class, fld_name);"
if source.count(point) != 1:
    raise RuntimeError("Unable to locate the generated Class.getName")
trace = """
    if (teavm_local_2 == NULL) {
        TeaVM_Class* kind = TEAVM_FIELD(teavm_this_, cls_jl_Class, fld_classInfo);
        char diagnostic[160];
        int length = snprintf(diagnostic, sizeof(diagnostic),
            "UPSTREAM_CLASS_NULL class=%p metadata=%p tag=%ld flags=%lx nameSlot=%p nameValue=%p",
            teavm_this_, (void*)kind, (long)kind->tag,
            (unsigned long)TEAVM_FIELD(teavm_this_, cls_jl_Class, fld_flags),
            (void*)kind->name, kind->name ? (void*)*kind->name : NULL);
        if (length > 0 && length < (int)sizeof(diagnostic)) {
            svcOutputDebugString(diagnostic, length);
        }
    }"""
path.write_text("#include <3ds.h>\n#include <stdio.h>\n" + source.replace(point, point + trace),
                encoding="utf-8", newline="\n")

path = Path("research/upstream-runtime/build/generated/teavm/c/c/UpstreamBuildingBaseline.c")
source = path.read_text(encoding="utf-8")
import re
pattern = re.compile(r"(    +teavm_local_\d+ = TEAVM_WITH_CALL_SITE_ID\(\d+, teavm_getClassObject\(\(TeaVM_Class\*\) (teavm_local_\d+)\)\);)")
matches = list(pattern.finditer(source))
if len(matches) != 1:
    raise RuntimeError("Unable to locate the building registry getClass call")
def report(match):
    statement, metadata = match.groups()
    return f"""
        if (teavm_local_11 >= 353 && teavm_local_11 <= 355) {{
            TeaVM_Class* slot = {metadata};
            char trace[160];
            int length = snprintf(trace, sizeof(trace),
                "UPSTREAM_CLASS_LOOKUP id=%d classMetadata=%p tag=%ld cached=%p heap=%p",
                (int)teavm_local_11, (void*)slot, (long)slot->tag, (void*)slot->classObject,
                (void*)teavm_gc_heapAddress);
            if (length > 0 && length < (int)sizeof(trace)) {{
                svcOutputDebugString(trace, length);
            }}
        }}
""" + statement
if "teavm_local_11 = TEAVM_FIELD" not in source:
    raise RuntimeError("Building record ID local changed")
path.write_text("#include <3ds.h>\n#include <stdio.h>\n" + pattern.sub(report, source),
                encoding="utf-8", newline="\n")
