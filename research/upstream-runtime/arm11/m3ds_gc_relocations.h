/* Included by TeaVM's generated GC.c after the relocation record declaration. */
#include <3ds.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>

static cls_otr_RelocationBlock *m3ds_gc_relocations;
static size_t m3ds_gc_relocation_capacity;
static bool m3ds_gc_relocations_owned;

static void m3ds_gc_storage_failure(const char *message) {
    svcOutputDebugString(message, strlen(message));
    abort();
}

TeaVM_Class* m3ds_gc_request_class;

static void m3ds_gc_log_failed_allocation(int32_t requested, int32_t free_bytes) {
    char name[104] = "<unnamed>";
    TeaVM_Class *cls = m3ds_gc_request_class;
    unsigned array_depth = 0;
    while (cls && cls->itemType) {
        cls = cls->itemType;
        ++array_depth;
    }
#if TEAVM_CLASS_NAME_USED
    TeaVM_String *class_name = cls && cls->name ? (TeaVM_String*)*cls->name : NULL;
    if (class_name && class_name->characters) {
        TeaVM_Array *chars = class_name->characters;
        const uint16_t *units = TEAVM_ARRAY_DATA(chars, uint16_t);
        int i = 0;
        for (; i < chars->size && i < (int)sizeof(name) - 1; ++i) {
            name[i] = units[i] < 128 ? (char)units[i] : '?';
        }
        name[i] = '\0';
    }
#endif
    char message[256];
    const int length = snprintf(message, sizeof(message),
        "UPSTREAM_MEMORY allocation-failed requested=%ld free=%ld committed=%lu cap=%lu class=%s arrayDepth=%u tag=%ld",
        (long)requested, (long)free_bytes,
        (unsigned long)teavm_gc_availableBytes, (unsigned long)teavm_gc_maxAvailableBytes,
        name, array_depth, cls ? (long)cls->tag : -1L);
    if (length > 0 && length < (int)sizeof(message)) svcOutputDebugString(message, length);
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
