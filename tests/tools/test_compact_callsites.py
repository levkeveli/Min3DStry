import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "research" / "upstream-runtime" / "arm11"))
import compact_callsites  # noqa: E402


class CompactCallsitesTests(unittest.TestCase):
    def test_compacts_non_obfuscated_callsites_and_preserves_handlers(self) -> None:
        sample = """#include "strings.h"
#include "c/j/i/IOException.h"

static TeaVM_MethodLocation methodLocations_teavm_callSites[2] = {
    { .fileName = NULL, .className = TEAVM_GET_STRING_ADDRESS(2), .methodName = TEAVM_GET_STRING_ADDRESS(11) },
    { .fileName = TEAVM_GET_STRING_ADDRESS(100), .className = TEAVM_GET_STRING_ADDRESS(2), .methodName = TEAVM_GET_STRING_ADDRESS(12) }
};
static TeaVM_ExceptionHandler exceptionHandlers_teavm_callSites[2] = {
    { .id = 1, .exceptionClass = (TeaVM_Class*) &ji_IOException_Cls, .next = NULL },
    { .id = 2, .exceptionClass = NULL, .next = exceptionHandlers_teavm_callSites + 0 }
};
static TeaVM_CallSiteLocation callSiteLocations_teavm_callSites[2] = {
    { .method = methodLocations_teavm_callSites + 0, .lineNumber = 42, .next = NULL },
    { .method = methodLocations_teavm_callSites + 1, .lineNumber = 99, .next = callSiteLocations_teavm_callSites + 0 }
};
TeaVM_CallSite teavm_callSites[5] = {
    { .firstHandler = NULL, .location = callSiteLocations_teavm_callSites + 0 },
    { .firstHandler = exceptionHandlers_teavm_callSites + 0, .location = callSiteLocations_teavm_callSites + 1 },
    { .firstHandler = NULL, .location = NULL },
    { .firstHandler = NULL, .location = callSiteLocations_teavm_callSites + 1 },
    { .firstHandler = exceptionHandlers_teavm_callSites + 1, .location = callSiteLocations_teavm_callSites + 0 }
};
"""
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp)
            (out_dir / "callsites.c").write_text(sample, encoding="utf-8")
            stats = compact_callsites.compact_callsites(out_dir)
            result = (out_dir / "callsites.c").read_text(encoding="utf-8")

        self.assertEqual(stats, {"total": 5, "handlers": 2})
        self.assertIn('#include "stack.h"', result)
        self.assertIn('#include "strings.h"', result)
        self.assertIn('#include "c/j/i/IOException.h"', result)
        self.assertNotIn("methodLocations_teavm_callSites", result)
        self.assertNotIn("callSiteLocations_teavm_callSites", result)
        self.assertIn("static TeaVM_ExceptionHandler exceptionHandlers_teavm_callSites[2] = {", result)
        self.assertIn("M3dsSparseCallSite m3ds_sparse_callsites[2] = {", result)
        self.assertIn("{ 1, { .firstHandler = exceptionHandlers_teavm_callSites + 0, .location = NULL } },", result)
        self.assertIn("{ 4, { .firstHandler = exceptionHandlers_teavm_callSites + 1, .location = NULL } }", result)
        self.assertIn("const size_t m3ds_sparse_callsite_count = 2u;", result)

    def test_compacts_obfuscated_callsites_without_location_tables(self) -> None:
        sample = """#include "stack.h"
#include "c/j/l/Throwable.h"
static TeaVM_ExceptionHandler exceptionHandlers_teavm_callSites[1] = {
    { .id = 1, .exceptionClass = (TeaVM_Class*) &jl_Throwable_Cls, .next = NULL }
};
TeaVM_CallSite teavm_callSites[3] = {
    { .firstHandler = NULL },
    { .firstHandler = exceptionHandlers_teavm_callSites + 0 },
    { .firstHandler = NULL }
};
"""
        compacted, stats = compact_callsites.compact_callsites_text(sample)
        self.assertEqual(stats, {"total": 3, "handlers": 1})
        self.assertEqual(compacted.count('#include "stack.h"'), 1)
        self.assertIn("M3dsSparseCallSite m3ds_sparse_callsites[1] = {", compacted)
        self.assertIn("{ 1, { .firstHandler = exceptionHandlers_teavm_callSites + 0, .location = NULL } }", compacted)
        self.assertIn("const size_t m3ds_sparse_callsite_count = 1u;", compacted)

    def test_zero_handler_and_empty_callsites_emit_valid_empty_sentinel(self) -> None:
        all_null = """#include "strings.h"
static TeaVM_CallSiteLocation callSiteLocations_teavm_callSites[0] = {};
TeaVM_CallSite teavm_callSites[2] = {
    { .firstHandler = NULL, .location = NULL },
    { .firstHandler = NULL, .location = NULL }
};
"""
        compacted_null, stats_null = compact_callsites.compact_callsites_text(all_null)
        self.assertEqual(stats_null, {"total": 2, "handlers": 0})
        self.assertIn("M3dsSparseCallSite m3ds_sparse_callsites[1] = {", compacted_null)
        self.assertIn("    { -1, { .firstHandler = NULL, .location = NULL } }", compacted_null)
        self.assertIn("const size_t m3ds_sparse_callsite_count = 0u;", compacted_null)

        empty_table = """#include "strings.h"
TeaVM_CallSite teavm_callSites[0] = {};
"""
        compacted_empty, stats_empty = compact_callsites.compact_callsites_text(empty_table)
        self.assertEqual(stats_empty, {"total": 0, "handlers": 0})
        self.assertIn("const size_t m3ds_sparse_callsite_count = 0u;", compacted_empty)

    def test_rejects_unexpected_declarations_and_mismatches(self) -> None:
        with self.assertRaises(ValueError):
            compact_callsites.compact_callsites_text("int unexpected_symbol = 1;\n")
        with self.assertRaises(ValueError):
            compact_callsites.compact_callsites_text(
                "TeaVM_CallSite teavm_callSites[2] = {\n    { .firstHandler = NULL }\n};\n"
            )
        with self.assertRaises(ValueError):
            compact_callsites.compact_callsites_text(
                "TeaVM_CallSite teavm_callSites[1] = {\n    { .firstHandler = other_ptr }\n};\n"
            )
        with self.assertRaises(ValueError):
            compact_callsites.compact_callsites_text('#include "strings.h"\n')


if __name__ == "__main__":
    unittest.main()
