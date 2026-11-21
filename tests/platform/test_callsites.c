#include <assert.h>
#include <limits.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

typedef struct TeaVM_ExceptionHandler {
    int32_t id;
    void *exceptionClass;
    struct TeaVM_ExceptionHandler *next;
} TeaVM_ExceptionHandler;

typedef struct TeaVM_CallSiteLocation {
    void *method;
    int32_t lineNumber;
    struct TeaVM_CallSiteLocation *next;
} TeaVM_CallSiteLocation;

typedef struct TeaVM_CallSite {
    TeaVM_ExceptionHandler *firstHandler;
    TeaVM_CallSiteLocation *location;
} TeaVM_CallSite;

#include "m3ds_callsites.h"

static TeaVM_ExceptionHandler g_handlers[4] = {
    {1, NULL, NULL},
    {2, NULL, NULL},
    {3, NULL, NULL},
    {4, NULL, NULL},
};

TeaVM_CallSite m3ds_empty_callsite = {NULL, NULL};
M3dsSparseCallSite m3ds_sparse_callsites[4] = {
    {18, {&g_handlers[0], NULL}},
    {1024, {&g_handlers[1], NULL}},
    {1025, {&g_handlers[2], NULL}},
    {207271, {&g_handlers[3], NULL}},
};
const size_t m3ds_sparse_callsite_count = 4u;

static void test_empty_and_null_tables(void) {
    TeaVM_CallSite empty = {NULL, NULL};
    M3dsSparseCallSite dummy[1] = {{-1, {NULL, NULL}}};
    const int32_t probe_ids[] = {INT32_MIN, -1, 0, 1, 42, INT32_MAX};

    for (size_t i = 0u; i < sizeof(probe_ids) / sizeof(probe_ids[0]); ++i) {
        assert(m3ds_find_callsite_in(dummy, 0u, &empty, probe_ids[i]) == &empty);
        assert(m3ds_find_callsite_in(NULL, 1u, &empty, probe_ids[i]) == &empty);
    }
}

static void test_single_entry_table(void) {
    TeaVM_CallSite empty = {NULL, NULL};
    M3dsSparseCallSite single[1] = {{42, {&g_handlers[0], NULL}}};

    assert(m3ds_find_callsite_in(single, 1u, &empty, 41) == &empty);
    assert(m3ds_find_callsite_in(single, 1u, &empty, 43) == &empty);
    TeaVM_CallSite *hit = m3ds_find_callsite_in(single, 1u, &empty, 42);
    assert(hit == &single[0].site);
    assert(hit->firstHandler == &g_handlers[0]);
    assert(hit->location == NULL);
}

static void test_sparse_table_and_bounds(void) {
    TeaVM_CallSite empty = {NULL, NULL};
    M3dsSparseCallSite boundary[4] = {
        {INT32_MIN, {&g_handlers[0], NULL}},
        {-5, {&g_handlers[1], NULL}},
        {0, {&g_handlers[2], NULL}},
        {INT32_MAX, {&g_handlers[3], NULL}},
    };

    for (size_t i = 0u; i < 4u; ++i) {
        TeaVM_CallSite *hit =
            m3ds_find_callsite_in(boundary, 4u, &empty, boundary[i].id);
        assert(hit == &boundary[i].site);
        assert(hit->firstHandler == &g_handlers[i]);
        assert(hit->location == NULL);
    }
    assert(m3ds_find_callsite_in(boundary, 4u, &empty, INT32_MIN + 1) == &empty);
    assert(m3ds_find_callsite_in(boundary, 4u, &empty, -1) == &empty);
    assert(m3ds_find_callsite_in(boundary, 4u, &empty, 1) == &empty);
    assert(m3ds_find_callsite_in(boundary, 4u, &empty, INT32_MAX - 1) == &empty);

    /* Global m3ds_find_callsite() lookups across hits and misses. */
    assert(m3ds_find_callsite(0) == &m3ds_empty_callsite);
    assert(m3ds_find_callsite(17) == &m3ds_empty_callsite);
    assert(m3ds_find_callsite(19) == &m3ds_empty_callsite);
    assert(m3ds_find_callsite(1023) == &m3ds_empty_callsite);
    assert(m3ds_find_callsite(1026) == &m3ds_empty_callsite);
    assert(m3ds_find_callsite(207272) == &m3ds_empty_callsite);

    assert(m3ds_find_callsite(18) == &m3ds_sparse_callsites[0].site);
    assert(m3ds_find_callsite(1024) == &m3ds_sparse_callsites[1].site);
    assert(m3ds_find_callsite(1025) == &m3ds_sparse_callsites[2].site);
    assert(m3ds_find_callsite(207271) == &m3ds_sparse_callsites[3].site);
}

int main(void) {
    test_empty_and_null_tables();
    test_single_entry_table();
    test_sparse_table_and_bounds();
    printf("test_callsites: all assertions passed.\n");
    return 0;
}
