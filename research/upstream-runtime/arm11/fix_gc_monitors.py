"""Teach TeaVM 0.16.0's C collector about packed object monitors."""

import argparse
from pathlib import Path


MARK_OBJECT = """int32_t meth_otr_GC_markObjectData(void* teavm_local_1) {
    void* teavm_local_2;
    teavm_local_2 = meth_otr_RuntimeClass_getClass(teavm_local_1);
    if ((TEAVM_FIELD(teavm_local_2, TeaVM_Class, itemType) != NULL)) {
        return meth_otr_GC_markArray(teavm_local_2, teavm_local_1);
    }
    return meth_otr_GC_markObject(teavm_local_2, teavm_local_1);
}"""
TRACE_MONITOR = """int32_t meth_otr_GC_markObjectData(void* teavm_local_1) {
    void* cls = meth_otr_RuntimeClass_getClass(teavm_local_1);
    // The monitor is stored in the hash word, outside the GC field layout.
    int32_t hash = TEAVM_FIELD(teavm_local_1, TeaVM_Object, hash);
    int32_t youngMonitor = meth_otr_GC_enqueueMark(TEAVM_UNPACK_MONITOR(hash));
    int32_t youngFields = TEAVM_FIELD(cls, TeaVM_Class, itemType) != NULL
        ? meth_otr_GC_markArray(cls, teavm_local_1)
        : meth_otr_GC_markObject(cls, teavm_local_1);
    return youngMonitor | youngFields;
}"""
UPDATE_OBJECT = "void meth_otr_GC_updatePointers(void* teavm_local_1, void* teavm_local_2) {"
UPDATE_MONITOR = UPDATE_OBJECT + """
    // Relocation temporarily stores its own pointer in the object header.
    void* relocation = meth_otr_GC_getRelocation(teavm_local_2);
    int32_t* hash = relocation != NULL
        ? &TEAVM_FIELD(relocation, cls_otr_Relocation, fld_sizeBackup)
        : &TEAVM_FIELD(teavm_local_2, TeaVM_Object, hash);
    void* monitor = TEAVM_UNPACK_MONITOR(*hash);
    if (monitor != NULL) {
        *hash = TEAVM_PACK_MONITOR(meth_otr_GC_updatePointer(monitor));
    }"""


def fix_gc_monitors(generated):
    path = generated / "c" / "o" / "t" / "r" / "GC.c"
    source = path.read_text(encoding="utf-8")
    for original, replacement in ((MARK_OBJECT, TRACE_MONITOR), (UPDATE_OBJECT, UPDATE_MONITOR)):
        if source.count(original) != 1:
            raise ValueError(f"Unrecognized TeaVM 0.16.0 collector in {path}")
        source = source.replace(original, replacement)
    path.write_text(source, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("generated", type=Path)
    fix_gc_monitors(parser.parse_args().generated)
