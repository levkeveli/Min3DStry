#pragma once

#include <stddef.h>
#include <stdint.h>

typedef struct M3dsSparseCallSite {
    int32_t id;
    TeaVM_CallSite site;
} M3dsSparseCallSite;

extern M3dsSparseCallSite m3ds_sparse_callsites[];
extern const size_t m3ds_sparse_callsite_count;
extern TeaVM_CallSite m3ds_empty_callsite;

/* Binary-searches a sorted sparse call-site table, returning empty_site on miss. */
static inline TeaVM_CallSite *m3ds_find_callsite_in(
    M3dsSparseCallSite *entries,
    size_t count,
    TeaVM_CallSite *empty_site,
    int32_t id) {
    if (!entries) {
        return empty_site;
    }
    size_t lo = 0u;
    size_t hi = count;
    while (lo < hi) {
        size_t mid = lo + ((hi - lo) >> 1u);
        int32_t mid_id = entries[mid].id;
        if (mid_id < id) {
            lo = mid + 1u;
        } else if (mid_id > id) {
            hi = mid;
        } else {
            return &entries[mid].site;
        }
    }
    return empty_site;
}

static inline TeaVM_CallSite *m3ds_find_callsite(int32_t id) {
    return m3ds_find_callsite_in(
        m3ds_sparse_callsites, m3ds_sparse_callsite_count,
        &m3ds_empty_callsite, id);
}
