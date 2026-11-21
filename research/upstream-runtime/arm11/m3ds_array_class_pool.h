/*
 * Lazily allocated chunked pool for TeaVM dynamic array class descriptors.
 *
 * TeaVM 0.16.0 emits a monolithic static array:
 *   TEAVM_OBJECT_CLASS teavm_dynamicClassPool[TEAVM_DYNAMIC_CLASS_POOL_CAPACITY];
 * which reserves ~16-18 MiB of .bss in full upstream Mindustry ARM11 builds even
 * when only a small fraction of array types is allocated at runtime. Replacing
 * it with fixed-address 8-byte-aligned chunks keeps chunk 0 inline in .bss for
 * small probes and allocates higher chunks on demand without ever relocating
 * existing TeaVM_Class descriptors.
 *
 * Included by TeaVM's generated arrayclass.c after arrayclass.h and arrayclass_gen.h.
 */
#ifndef M3DS_ARRAY_CLASS_POOL_H
#define M3DS_ARRAY_CLASS_POOL_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifdef __3DS__
#include <3ds.h>
#include <malloc.h>
#endif

#ifndef M3DS_ARRAY_CLASS_CHUNK_SHIFT
#define M3DS_ARRAY_CLASS_CHUNK_SHIFT 6
#endif

_Static_assert(M3DS_ARRAY_CLASS_CHUNK_SHIFT >= 1 && M3DS_ARRAY_CLASS_CHUNK_SHIFT <= 12,
    "Array class chunk shift out of supported range");

#define M3DS_ARRAY_CLASS_CHUNK_SIZE (INT32_C(1) << M3DS_ARRAY_CLASS_CHUNK_SHIFT)
#define M3DS_ARRAY_CLASS_CHUNK_MASK (M3DS_ARRAY_CLASS_CHUNK_SIZE - 1)
#define M3DS_ARRAY_CLASS_CHUNK_COUNT \
    ((TEAVM_DYNAMIC_CLASS_POOL_CAPACITY + M3DS_ARRAY_CLASS_CHUNK_SIZE - 1) \
        / M3DS_ARRAY_CLASS_CHUNK_SIZE)

typedef struct {
    _Alignas(8) TEAVM_OBJECT_CLASS value;
} M3dsArrayClassSlot;

_Static_assert(_Alignof(M3dsArrayClassSlot) >= 8,
    "Dynamic array class slots must stay 8-byte aligned for TeaVM packed class headers");
_Static_assert((sizeof(M3dsArrayClassSlot) % 8U) == 0U,
    "Dynamic array class slot stride must preserve 8-byte alignment");

#ifndef M3DS_ARRAY_CLASS_ALLOC_CHUNK
static inline void *m3ds_array_class_default_alloc(size_t bytes) {
#ifdef __3DS__
    void *memory = memalign(8U, bytes);
    if (memory != NULL) {
        memset(memory, 0, bytes);
    }
    return memory;
#else
    return calloc(1U, bytes);
#endif
}
#define M3DS_ARRAY_CLASS_ALLOC_CHUNK(bytes) m3ds_array_class_default_alloc((bytes))
#endif

#ifndef M3DS_ARRAY_CLASS_FAIL
static inline void m3ds_array_class_default_fail(const char *message) {
#ifdef __3DS__
    if (message != NULL) {
        svcOutputDebugString(message, (s32)strlen(message));
    }
#else
    (void)message;
#endif
    abort();
}
#define M3DS_ARRAY_CLASS_FAIL(message) m3ds_array_class_default_fail((message))
#endif

static M3dsArrayClassSlot m3ds_array_class_inline_chunk[M3DS_ARRAY_CLASS_CHUNK_SIZE];
static M3dsArrayClassSlot *m3ds_array_class_chunks[M3DS_ARRAY_CLASS_CHUNK_COUNT];

/*
 * TeaVM packs a class descriptor pointer into bits 0..30 of object headers via:
 *   TEAVM_PACK_CLASS(cls) = (int32_t)(((uintptr_t)((char*)(cls) - teavm_beforeClasses)) >> 3)
 * and reserves bit 31 (0x80000000) for GC marking. Every slot in a chunk must
 * therefore sit on an 8-byte boundary strictly above teavm_beforeClasses and keep
 * all byte offsets <= 0x7FFFFFF8 so packed IDs stay in [1, 0x0FFFFFFF].
 */
static inline bool m3ds_array_class_range_valid(
        const void *chunk, size_t chunk_bytes, const void *before_classes) {
    if (chunk == NULL || chunk_bytes == 0U || before_classes == NULL) {
        return false;
    }
    const uintptr_t start = (uintptr_t)chunk;
    const uintptr_t base = (uintptr_t)before_classes;
    if ((start & 7U) != 0U) {
        return false;
    }
    if (chunk_bytes > UINTPTR_MAX - start) {
        return false;
    }
    const uintptr_t end = start + chunk_bytes;
    if (start <= base) {
        return false;
    }
    return (end - base) <= (uintptr_t)0x7FFFFFF8U;
}

static inline M3dsArrayClassSlot *m3ds_array_class_ensure_chunk(int32_t chunk_index) {
    M3dsArrayClassSlot *chunk = m3ds_array_class_chunks[chunk_index];
    if (chunk != NULL) {
        return chunk;
    }

    const size_t chunk_bytes = (size_t)M3DS_ARRAY_CLASS_CHUNK_SIZE * sizeof(M3dsArrayClassSlot);
    if (chunk_index == 0) {
        chunk = m3ds_array_class_inline_chunk;
    } else {
        chunk = (M3dsArrayClassSlot *)M3DS_ARRAY_CLASS_ALLOC_CHUNK(chunk_bytes);
        if (chunk == NULL) {
            M3DS_ARRAY_CLASS_FAIL("UPSTREAM_META array-class chunk allocation failed\n");
            return NULL;
        }
    }

    if (teavm_beforeClasses != NULL) {
        if (!m3ds_array_class_range_valid(chunk, chunk_bytes, teavm_beforeClasses)) {
            M3DS_ARRAY_CLASS_FAIL("UPSTREAM_META array-class chunk out of packed range\n");
            return NULL;
        }
        TeaVM_Class *first = &chunk[0].value.parent;
        TeaVM_Class *last = &chunk[M3DS_ARRAY_CLASS_CHUNK_SIZE - 1].value.parent;
        if (TEAVM_UNPACK_CLASS(TEAVM_PACK_CLASS(first)) != first
                || TEAVM_UNPACK_CLASS(TEAVM_PACK_CLASS(last)) != last) {
            M3DS_ARRAY_CLASS_FAIL("UPSTREAM_META packed class round-trip failed\n");
            return NULL;
        }
    }

    m3ds_array_class_chunks[chunk_index] = chunk;
    return chunk;
}

static inline TEAVM_OBJECT_CLASS *m3ds_array_class_get(int32_t index) {
    if (index < 0 || index >= TEAVM_DYNAMIC_CLASS_POOL_CAPACITY) {
        M3DS_ARRAY_CLASS_FAIL("UPSTREAM_META array-class index out of bounds\n");
        return NULL;
    }
    const int32_t chunk_index = index >> M3DS_ARRAY_CLASS_CHUNK_SHIFT;
    M3dsArrayClassSlot *chunk = m3ds_array_class_ensure_chunk(chunk_index);
    if (chunk == NULL) {
        return NULL;
    }
    return &chunk[index & M3DS_ARRAY_CLASS_CHUNK_MASK].value;
}

static inline TEAVM_OBJECT_CLASS *m3ds_array_class_alloc(int32_t *size_ptr) {
    if (size_ptr == NULL || *size_ptr < 0 || *size_ptr + 1 >= TEAVM_DYNAMIC_CLASS_POOL_CAPACITY) {
        M3DS_ARRAY_CLASS_FAIL("Metaspace size\n");
        return NULL;
    }
    const int32_t index = (*size_ptr)++;
    return m3ds_array_class_get(index);
}

#endif
