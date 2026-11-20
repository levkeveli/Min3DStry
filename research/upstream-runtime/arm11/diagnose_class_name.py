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
