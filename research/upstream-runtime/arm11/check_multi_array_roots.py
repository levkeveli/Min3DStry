"""Force a collection inside the pinned TeaVM C multi-array allocator."""

import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from prepare import replace, root_multi_array_allocation


CHECK = """
    TEAVM_ALLOC_STACK(1);
    TEAVM_CALL_SITE(-1);
    TEAVM_GC_ROOT_RELEASE(0);

    TeaVM_Class* leafClass = (TeaVM_Class*) &Arr_I_Cls;
    TeaVM_Class* middleClass = teavm_getArrayClass(leafClass);
    TeaVM_Class* rootClass = teavm_getArrayClass(middleClass);
    int32_t lengths[] = {4, 3, 5};
    // The fourth allocation occurs with two unfinished arrays on the stack.
    allocationsUntilCollection = 4;
    void* tree = meth_otr_Allocator_allocateMultiArray(rootClass, lengths, 3);
    TEAVM_GC_ROOT(0, tree);
    if (allocationsUntilCollection != 0 || TEAVM_ARRAY_LENGTH(tree) != 4) return 10;
    for (int i = 0; i < 4; ++i) {
        void* middle = TEAVM_ARRAY_AT(tree, void*, i);
        if (!middle || meth_otr_RuntimeClass_getClass(middle) != middleClass
                || TEAVM_ARRAY_LENGTH(middle) != 3) return 11;
        for (int j = 0; j < 3; ++j) {
            void* leaf = TEAVM_ARRAY_AT(middle, void*, j);
            if (!leaf || meth_otr_RuntimeClass_getClass(leaf) != leafClass
                    || TEAVM_ARRAY_LENGTH(leaf) != 5) return 12;
            TEAVM_ARRAY_AT(leaf, int32_t, 4) = 10 * i + j;
        }
    }

    // Also collect children that were assigned after their parents were promoted.
    teavm_gc_collect();
    tree = teavm_shadowStack.data[0];
    for (int i = 0; i < 4; ++i) {
        void* middle = TEAVM_ARRAY_AT(tree, void*, i);
        for (int j = 0; j < 3; ++j) {
            void* leaf = TEAVM_ARRAY_AT(middle, void*, j);
            if (TEAVM_ARRAY_AT(leaf, int32_t, 4) != 10 * i + j) return 13;
        }
    }
    TEAVM_RELEASE_STACK;
    puts("PASS: all 12 child arrays survived full and young collection");
    return 0;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("generated", type=Path, help="unmodified TeaVM C output from the logic probe")
    parser.add_argument("--cc", default="gcc")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="m3ds-multi-array-") as directory:
        root = Path(directory) / "c"
        shutil.copytree(args.generated, root)
        root_multi_array_allocation(root)
        signature = "void* meth_otr_Allocator_allocateArray(void* teavm_local_1, int32_t teavm_local_2) {"
        replace(root / "c/o/t/r/Allocator.c", signature,
                "static int allocationsUntilCollection;\n" + signature + """
    if (allocationsUntilCollection > 0 && --allocationsUntilCollection == 0) {
        teavm_gc_collectFull();
    }""")
        main_file = root / "main.c"
        replace(main_file, '#include "runtime.h"', '#include <stdio.h>\n#include "runtime.h"')
        replace(main_file, "    meth_otr_Fiber_startMain(teavm_parseArguments(argc, argv));\n"
                "    meth_otr_EventQueue_process();\n    return 0;", CHECK.rstrip())
        flags = ["-lm"]
        if sys.platform == "win32":
            definitions = root / "definitions.h"
            source = definitions.read_text(encoding="utf-8")
            if "#if defined(_WIN32) && defined(__GNUC__)" not in source:
                marker = "#if defined(__GNUC__) || defined(__APPLE__)"
                replace(definitions, marker,
                        "#if defined(_WIN32) && defined(__GNUC__)\n"
                        "    #undef TEAVM_WINDOWS\n    #define TEAVM_WINDOWS 1\n"
                        "    #include <stdalign.h>\n#elif defined(__GNUC__) || defined(__APPLE__)")
            flags.extend(["-D__assume(x)=__builtin_unreachable()", "-lws2_32", "-lbcrypt"])
        probe = root / "probe.exe"
        subprocess.run([args.cc, "-std=gnu11", "all.c", "-o", str(probe), *flags], cwd=root, check=True)
        subprocess.run([str(probe)], cwd=root, check=True)


if __name__ == "__main__":
    main()
