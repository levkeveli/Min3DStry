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

    def test_prunes_tail_interior_multiline_and_from_codes_strings(self) -> None:
        sample = """#include "strings.h"
#include "stringhash.h"

TeaVM_String* teavm_stringPool[6] = {
    TEAVM_STRING(4, 3392903,u"null"), // string #0
    TEAVM_STRING(10, 1,u"dead-mid-0"), // string #1
    TEAVM_STRING(20, 2,u"multi line "
    "with https://x ) tail"), // string #2
    TEAVM_STRING_FROM_CODES(3, 3, 65, 66, 67), // string #3
    TEAVM_STRING(9, 4,u"dead-tail-4"), // string #4
    TEAVM_STRING(9, 5,u"dead-tail-5")  // string #5
};
void teavm_initStringPool() {
    for (int i = 0; i < 6; ++i) {
        teavm_stringPool[i] = teavm_registerString(teavm_stringPool[i]);
    }
}
"""
        pruned, stats = compact_callsites.prune_unused_strings_text(sample, {0, 2, 3})
        self.assertEqual(
            stats,
            {
                "total": 6,
                "kept": 3,
                "pruned_tail": 2,
                "pruned_interior": 1,
                "skipped_non_constant": 0,
            },
        )
        self.assertIn("TeaVM_String* teavm_stringPool[4] = {", pruned)
        self.assertIn('TEAVM_STRING(4, 3392903,u"null"), // string #0', pruned)
        self.assertIn("    NULL, // string #1 (pruned)", pruned)
        self.assertIn('"with https://x ) tail"), // string #2', pruned)
        self.assertIn("    TEAVM_STRING_FROM_CODES(3, 3, 65, 66, 67) // string #3", pruned)
        self.assertNotIn("TEAVM_STRING_FROM_CODES(3, 3, 65, 66, 67),", pruned)
        self.assertNotIn("dead-mid-0", pruned)
        self.assertNotIn("dead-tail-4", pruned)
        self.assertNotIn("dead-tail-5", pruned)
        self.assertIn("    for (int i = 0; i < 4; ++i) {", pruned)

    def test_prunes_empty_live_refs_to_valid_single_sentinel(self) -> None:
        sample = """TeaVM_String* teavm_stringPool[2] = {
    TEAVM_STRING(1, 1,u"a"), // string #0
    TEAVM_STRING(1, 2,u"b")  // string #1
};
void teavm_initStringPool() {
    for (int i = 0; i < 2; ++i) {
    }
}
"""
        pruned, stats = compact_callsites.prune_unused_strings_text(sample, set())
        self.assertEqual(
            stats,
            {
                "total": 2,
                "kept": 0,
                "pruned_tail": 2,
                "pruned_interior": 0,
                "skipped_non_constant": 0,
            },
        )
        self.assertIn("TeaVM_String* teavm_stringPool[1] = {\n    NULL\n};", pruned)
        self.assertIn("    for (int i = 0; i < 0; ++i) {", pruned)

    def test_directory_pipeline_prunes_callsite_only_strings_and_fails_closed(self) -> None:
        callsites = """#include "strings.h"
static TeaVM_MethodLocation methodLocations_teavm_callSites[1] = {
    { .fileName = TEAVM_GET_STRING_ADDRESS(2), .className = TEAVM_GET_STRING_ADDRESS(0), .methodName = TEAVM_GET_STRING_ADDRESS(3) }
};
static TeaVM_CallSiteLocation callSiteLocations_teavm_callSites[1] = {
    { .method = methodLocations_teavm_callSites + 0, .lineNumber = 10, .next = NULL }
};
TeaVM_CallSite teavm_callSites[1] = {
    { .firstHandler = NULL, .location = callSiteLocations_teavm_callSites + 0 }
};
"""
        strings = """#include "strings.h"
TeaVM_String* teavm_stringPool[4] = {
    TEAVM_STRING(4, 1,u"live"), // string #0
    TEAVM_STRING(5, 2,u"live2"), // string #1
    TEAVM_STRING(8, 3,u"cs-only0"), // string #2
    TEAVM_STRING(8, 4,u"cs-only1")  // string #3
};
void teavm_initStringPool() {
    for (int i = 0; i < 4; ++i) {
    }
}
"""
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp)
            (out_dir / "callsites.c").write_text(callsites, encoding="utf-8")
            (out_dir / "strings.c").write_text(strings, encoding="utf-8")
            (out_dir / "runtime_user.c").write_text(
                "void f() { void *a = TEAVM_GET_STRING(0); void *b = TEAVM_GET_STRING_ADDRESS(1); }\n",
                encoding="utf-8",
            )
            compact_callsites.compact_callsites(out_dir)
            stats = compact_callsites.prune_unused_strings(out_dir)
            self.assertEqual(
                stats,
                {
                    "total": 4,
                    "kept": 2,
                    "pruned_tail": 2,
                    "pruned_interior": 0,
                    "skipped_non_constant": 0,
                },
            )
            pruned_strings = (out_dir / "strings.c").read_text(encoding="utf-8")
            self.assertIn("TeaVM_String* teavm_stringPool[2] = {", pruned_strings)
            self.assertNotIn("cs-only0", pruned_strings)

        # Non-constant teavm_stringPool[idx] reference must leave strings.c unmodified.
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp)
            (out_dir / "strings.c").write_text(strings, encoding="utf-8")
            (out_dir / "dyn.c").write_text(
                "void *g(int idx) { return teavm_stringPool[idx]; }\n", encoding="utf-8"
            )
            stats = compact_callsites.prune_unused_strings(out_dir)
            self.assertEqual(
                stats,
                {
                    "total": 4,
                    "kept": 4,
                    "pruned_tail": 0,
                    "pruned_interior": 0,
                    "skipped_non_constant": 1,
                },
            )
            self.assertEqual((out_dir / "strings.c").read_text(encoding="utf-8"), strings)

    def test_prune_strings_rejects_out_of_range_and_malformed_inputs(self) -> None:
        sample = """TeaVM_String* teavm_stringPool[2] = {
    TEAVM_STRING(1, 1,u"a"), // string #0
    TEAVM_STRING(1, 2,u"b")  // string #1
};
void teavm_initStringPool() {
    for (int i = 0; i < 2; ++i) {
    }
}
"""
        with self.assertRaises(ValueError):
            compact_callsites.prune_unused_strings_text(sample, {2})
        with self.assertRaises(ValueError):
            compact_callsites.prune_unused_strings_text(
                sample.replace("teavm_stringPool[2]", "teavm_stringPool[3]"), {0}
            )
        with self.assertRaises(ValueError):
            compact_callsites.prune_unused_strings_text(
                sample.replace("for (int i = 0; i < 2; ++i)", "for (int i = 0; i < 9; ++i)"), {0}
            )


if __name__ == "__main__":
    unittest.main()
