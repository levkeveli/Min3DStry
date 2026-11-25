/* Included by TeaVM's generated GC.c after the relocation record declaration. */
#include <3ds.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "stack.h"

static cls_otr_RelocationBlock *m3ds_gc_relocations;
static size_t m3ds_gc_relocation_capacity;
static bool m3ds_gc_relocations_owned;

static void m3ds_gc_storage_failure(const char *message) {
    svcOutputDebugString(message, strlen(message));
    abort();
}

TeaVM_Class* m3ds_gc_request_class;

static TeaVM_Class *m3ds_gc_describe_class(TeaVM_Class *cls, char *name, size_t limit, unsigned *depth) {
    snprintf(name, limit, "<unnamed>");
    *depth = 0;
    while (cls && cls->itemType) {
        cls = cls->itemType;
        ++*depth;
    }
#if TEAVM_CLASS_NAME_USED
    TeaVM_String *class_name = cls && cls->name ? (TeaVM_String*)*cls->name : NULL;
    if (class_name && class_name->characters) {
        TeaVM_Array *chars = class_name->characters;
        const uint16_t *units = TEAVM_ARRAY_DATA(chars, uint16_t);
        int i = 0;
        for (; i < chars->size && i < (int)limit - 1; ++i) {
            name[i] = units[i] < 128 ? (char)units[i] : '?';
        }
        name[i] = '\0';
    }
#endif
    return cls;
}

static void m3ds_gc_log_failed_allocation(int32_t requested, int32_t free_bytes) {
    char name[104];
    unsigned array_depth;
    TeaVM_Class *cls = m3ds_gc_describe_class(m3ds_gc_request_class, name, sizeof(name), &array_depth);
    char message[256];
    const int length = snprintf(message, sizeof(message),
        "UPSTREAM_MEMORY allocation-failed requested=%ld free=%ld committed=%lu cap=%lu class=%s arrayDepth=%u tag=%ld",
        (long)requested, (long)free_bytes,
        (unsigned long)teavm_gc_availableBytes, (unsigned long)teavm_gc_maxAvailableBytes,
        name, array_depth, cls ? (long)cls->tag : -1L);
    if (length > 0 && length < (int)sizeof(message)) svcOutputDebugString(message, length);

    // The generated C retains these IDs after ARM11 exception-table compaction.
    // Resolve them against the source map from this exact diagnostic build.
    const TeaVM_StackFrame *frame = teavm_stackTop;
    for (int depth = 0; frame != NULL && depth < 12; depth++, frame = frame->next) {
        char record[80];
        const int size = snprintf(record, sizeof(record),
            "UPSTREAM_HEAP_CALLSITE depth=%d id=%ld", depth, (long)frame->callSiteId);
        if (size > 0 && size < (int)sizeof(record)) svcOutputDebugString(record, size);
    }
}

typedef struct {
    TeaVM_Class *cls;
    uint32_t objects, bytes;
} M3DSHeapBucket;

static void m3ds_gc_log_heap_census(void) {
    const size_t capacity = 8192;
    M3DSHeapBucket *buckets = calloc(capacity, sizeof(*buckets));
    if (!buckets) return;

    teavm_gc_fixHeap();
    uintptr_t cursor = (uintptr_t)teavm_gc_heapAddress;
    const uintptr_t end = cursor + teavm_gc_availableBytes;
    uint32_t live = 0, free_bytes = 0;
    uint32_t distinct = 0;
    while (cursor < end) {
        TeaVM_Object *object = (TeaVM_Object*)cursor;
        int32_t size = meth_otr_GC_objectSize(object);
        if (size < (int32_t)sizeof(*object) || (uintptr_t)size > end - cursor) break;
        if (object->header == 0) {
            free_bytes += size;
        } else {
            TeaVM_Class *cls = TEAVM_CLASS_OF(object);
            size_t slot = ((uintptr_t)cls >> 3) & (capacity - 1);
            while (buckets[slot].cls && buckets[slot].cls != cls) {
                slot = (slot + 1) & (capacity - 1);
            }
            if (!buckets[slot].cls) {
                buckets[slot].cls = cls;
                if (++distinct == capacity) break;
            }
            ++buckets[slot].objects;
            buckets[slot].bytes += size;
            live += size;
        }
        cursor += size;
    }

    char line[256];
    int length = snprintf(line, sizeof(line), "UPSTREAM_HEAP_CENSUS complete=%s live=%lu free=%lu types=%lu",
        cursor == end ? "true" : "false", (unsigned long)live, (unsigned long)free_bytes, (unsigned long)distinct);
    if (length > 0 && length < (int)sizeof(line)) svcOutputDebugString(line, length);
    for (unsigned rank = 0; rank < 20 && rank < distinct; ++rank) {
        M3DSHeapBucket *largest = &buckets[0];
        for (size_t index = 1; index < capacity; ++index) {
            if (buckets[index].bytes > largest->bytes) largest = &buckets[index];
        }
        char name[104];
        unsigned depth;
        TeaVM_Class *cls = m3ds_gc_describe_class(largest->cls, name, sizeof(name), &depth);
        length = snprintf(line, sizeof(line),
            "UPSTREAM_HEAP_CLASS name=%s arrayDepth=%u tag=%ld objects=%lu bytes=%lu",
            name, depth, cls ? (long)cls->tag : -1L, (unsigned long)largest->objects, (unsigned long)largest->bytes);
        if (length > 0 && length < (int)sizeof(line)) svcOutputDebugString(line, length);
        largest->bytes = 0;
    }
    free(buckets);
}

static void m3ds_gc_check_free_chunk(const void *slot) {
    const uintptr_t start = (uintptr_t)teavm_gc_gcStorageAddress;
    const uintptr_t address = (uintptr_t)slot;
    const size_t bytes = (size_t)teavm_gc_gcStorageSize;
    if (address < start || address - start >= bytes
            || bytes - (address - start) < sizeof(cls_otr_FreeChunkHolder)) {
        m3ds_gc_storage_failure("UPSTREAM_GC free-chunk table exhausted");
    }
}

static void m3ds_gc_grow_relocations(size_t used) {
    const size_t limit = SIZE_MAX / sizeof(*m3ds_gc_relocations);
    if (used >= limit) m3ds_gc_storage_failure("UPSTREAM_GC relocation table overflow");
    const size_t increment = used / 4 + 1;
    const size_t capacity = increment <= limit - used ? used + increment : limit;
    const size_t bytes = capacity * sizeof(*m3ds_gc_relocations);
    void *replacement = m3ds_gc_relocations_owned
        ? realloc(m3ds_gc_relocations, bytes) : malloc(bytes);
    if (!replacement) m3ds_gc_storage_failure("UPSTREAM_GC relocation allocation failed");
    if (!m3ds_gc_relocations_owned && used != 0) {
        memcpy(replacement, m3ds_gc_relocations, used * sizeof(*m3ds_gc_relocations));
    }
    m3ds_gc_relocations = replacement;
    m3ds_gc_relocation_capacity = capacity;
    m3ds_gc_relocations_owned = true;
}

static void *m3ds_gc_begin_relocations(void *first) {
    const uintptr_t start = (uintptr_t)teavm_gc_gcStorageAddress;
    const uintptr_t address = (uintptr_t)first;
    const size_t bytes = (size_t)teavm_gc_gcStorageSize;
    if (address < start || address - start > bytes) {
        m3ds_gc_storage_failure("UPSTREAM_GC invalid relocation table start");
    }
    m3ds_gc_relocations = first;
    m3ds_gc_relocation_capacity = (bytes - (address - start)) / sizeof(*m3ds_gc_relocations);
    if (m3ds_gc_relocation_capacity == 0) m3ds_gc_grow_relocations(0);
    return m3ds_gc_relocations;
}

static void *m3ds_gc_append_relocation(void *tail, void **cursor) {
    const size_t used = (cls_otr_RelocationBlock *)tail - m3ds_gc_relocations + 1;
    if (used == m3ds_gc_relocation_capacity) {
        const size_t index = (cls_otr_RelocationBlock *)*cursor - m3ds_gc_relocations;
        m3ds_gc_grow_relocations(used);
        *cursor = m3ds_gc_relocations + index;
    }
    return m3ds_gc_relocations + used;
}

static void m3ds_gc_end_relocations(void) {
    if (m3ds_gc_relocations_owned) {
        char message[80];
        const int length = snprintf(message, sizeof(message),
            "UPSTREAM_GC_RELOCATION_SPILL bytes=%lu", (unsigned long)
            (m3ds_gc_relocation_capacity * sizeof(*m3ds_gc_relocations)));
        if (length > 0 && length < (int)sizeof(message)) svcOutputDebugString(message, length);
        free(m3ds_gc_relocations);
        m3ds_gc_relocations_owned = false;
    }
    m3ds_gc_relocations = NULL;
    m3ds_gc_relocation_capacity = 0;
}
