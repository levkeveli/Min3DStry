/*
 * Host unit tests for the ARM11 lazy chunked TeaVM dynamic array class pool.
 */
#include <assert.h>
#include <setjmp.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct MockTeaVMClass {
    void *class_object;
    struct MockTeaVMClass *next;
    int32_t size;
    int32_t flags;
    int32_t tag;
    int32_t modifiers;
    struct MockTeaVMClass *item_type;
    struct MockTeaVMClass *array_type;
    struct MockTeaVMClass *superclass;
} TeaVM_Class;

typedef struct {
    TeaVM_Class parent;
    void *vtable[44];
} MockObjectVtable;

#define TEAVM_OBJECT_CLASS MockObjectVtable
#define TEAVM_DYNAMIC_CLASS_POOL_CAPACITY 24
#define M3DS_ARRAY_CLASS_CHUNK_SHIFT 2

static char *teavm_beforeClasses = NULL;

#define TEAVM_PACK_CLASS(cls) \
    ((int32_t)(((uintptr_t)((const char *)(cls) - teavm_beforeClasses)) >> 3))
#define TEAVM_UNPACK_CLASS(packed) \
    ((TeaVM_Class *)(teavm_beforeClasses + ((packed) << 3)))

static jmp_buf mock_fail_jmp;
static bool mock_fail_armed = false;
static int mock_fail_count = 0;
static const char *mock_last_fail = NULL;

static _Alignas(16) uint8_t mock_arena[65536];
static size_t mock_arena_used = 0U;
static int mock_alloc_calls = 0;
static void *mock_next_override = NULL;
static bool mock_use_override = false;

static void *mock_alloc_chunk(size_t bytes) {
    mock_alloc_calls++;
    if (mock_use_override) {
        mock_use_override = false;
        return mock_next_override;
    }
    const size_t aligned = (mock_arena_used + 7U) & ~(size_t)7U;
    if (aligned + bytes > sizeof(mock_arena)) {
        return NULL;
    }
    void *ptr = mock_arena + aligned;
    mock_arena_used = aligned + bytes;
    memset(ptr, 0, bytes);
    return ptr;
}

static void mock_fail(const char *message) {
    mock_fail_count++;
    mock_last_fail = message;
    if (mock_fail_armed) {
        longjmp(mock_fail_jmp, 1);
    }
    abort();
}

#define M3DS_ARRAY_CLASS_ALLOC_CHUNK(bytes) mock_alloc_chunk((bytes))
#define M3DS_ARRAY_CLASS_FAIL(message) mock_fail((message))

#include "m3ds_array_class_pool.h"

static void reset_mock_pool(void) {
    memset(m3ds_array_class_inline_chunk, 0, sizeof(m3ds_array_class_inline_chunk));
    memset(m3ds_array_class_chunks, 0, sizeof(m3ds_array_class_chunks));
    memset(mock_arena, 0, sizeof(mock_arena));
    /* Keep mock chunk allocations strictly above teavm_beforeClasses = mock_arena. */
    mock_arena_used = 64U;
    mock_alloc_calls = 0;
    mock_next_override = NULL;
    mock_use_override = false;
    mock_fail_count = 0;
    mock_last_fail = NULL;
    mock_fail_armed = false;
}

static void test_range_validation_edges(void) {
    const uintptr_t base = (uintptr_t)0x10000000U;
    assert(!m3ds_array_class_range_valid(NULL, 64U, (const void *)base));
    assert(!m3ds_array_class_range_valid((const void *)(base + 8U), 0U, (const void *)base));
    assert(!m3ds_array_class_range_valid((const void *)(base + 8U), 64U, NULL));
    assert(!m3ds_array_class_range_valid((const void *)(base + 4U), 64U, (const void *)base));
    assert(!m3ds_array_class_range_valid((const void *)base, 64U, (const void *)base));
    assert(!m3ds_array_class_range_valid((const void *)(base - 8U), 64U, (const void *)base));
    assert(m3ds_array_class_range_valid((const void *)(base + 8U), 64U, (const void *)base));

    /* Exact maximum signed 31-bit packed boundary vs overflow. */
    assert(m3ds_array_class_range_valid(
        (const void *)(base + 0x7FFFFFF8U - 64U), 64U, (const void *)base));
    assert(!m3ds_array_class_range_valid(
        (const void *)(base + 0x7FFFFFF8U - 56U), 64U, (const void *)base));

    /* Overflow of uintptr_t add must fail cleanly. */
    assert(!m3ds_array_class_range_valid(
        (const void *)(UINTPTR_MAX - 7U), 64U, (const void *)base));
}

static void test_lazy_multi_chunk_allocation_and_stability(void) {
    reset_mock_pool();
    teavm_beforeClasses = NULL;

    int32_t pool_size = 0;
    TEAVM_OBJECT_CLASS *slots[TEAVM_DYNAMIC_CLASS_POOL_CAPACITY - 1];

    /* Chunk 0 uses inline .bss storage with zero dynamic chunk allocations. */
    for (int32_t i = 0; i < M3DS_ARRAY_CLASS_CHUNK_SIZE; i++) {
        slots[i] = m3ds_array_class_alloc(&pool_size);
        assert(slots[i] != NULL);
        assert(((uintptr_t)slots[i] & 7U) == 0U);
        slots[i]->parent.tag = 100 + i;
    }
    assert(mock_alloc_calls == 0);
    assert(m3ds_array_class_chunks[0] == m3ds_array_class_inline_chunk);
    for (int32_t c = 1; c < M3DS_ARRAY_CLASS_CHUNK_COUNT; c++) {
        assert(m3ds_array_class_chunks[c] == NULL);
    }

    /* Chunks 1..5 allocate lazily above mock teavm_beforeClasses and verify packed IDs. */
    teavm_beforeClasses = (char *)mock_arena;
    for (int32_t i = M3DS_ARRAY_CLASS_CHUNK_SIZE; i < TEAVM_DYNAMIC_CLASS_POOL_CAPACITY - 1; i++) {
        const int32_t expected_chunk = i >> M3DS_ARRAY_CLASS_CHUNK_SHIFT;
        slots[i] = m3ds_array_class_alloc(&pool_size);
        assert(slots[i] != NULL);
        assert(((uintptr_t)slots[i] & 7U) == 0U);
        slots[i]->parent.tag = 100 + i;

        const int32_t packed = TEAVM_PACK_CLASS(&slots[i]->parent);
        assert(packed > 0);
        assert((packed & (int32_t)INT32_C(0x80000000)) == 0);
        assert(TEAVM_UNPACK_CLASS(packed) == &slots[i]->parent);
        assert(mock_alloc_calls == expected_chunk);
    }
    assert(pool_size == TEAVM_DYNAMIC_CLASS_POOL_CAPACITY - 1);
    assert(mock_alloc_calls == M3DS_ARRAY_CLASS_CHUNK_COUNT - 1);

    /* Prior descriptor addresses and tags stay invariant across all chunk allocations. */
    for (int32_t i = 0; i < pool_size; i++) {
        assert(m3ds_array_class_get(i) == slots[i]);
        assert(slots[i]->parent.tag == 100 + i);
    }

    /* Re-accessing chunk 1 makes no additional allocations. */
    assert(m3ds_array_class_get(M3DS_ARRAY_CLASS_CHUNK_SIZE) == slots[M3DS_ARRAY_CLASS_CHUNK_SIZE]);
    assert(mock_alloc_calls == M3DS_ARRAY_CLASS_CHUNK_COUNT - 1);
}

static void expect_fail_alloc(int32_t *pool_size) {
    mock_fail_armed = true;
    const int before = mock_fail_count;
    if (setjmp(mock_fail_jmp) == 0) {
        (void)m3ds_array_class_alloc(pool_size);
        assert(false && "expected m3ds_array_class_alloc failure");
    }
    mock_fail_armed = false;
    assert(mock_fail_count == before + 1);
}

static void expect_fail_get(int32_t index) {
    mock_fail_armed = true;
    const int before = mock_fail_count;
    if (setjmp(mock_fail_jmp) == 0) {
        (void)m3ds_array_class_get(index);
        assert(false && "expected m3ds_array_class_get failure");
    }
    mock_fail_armed = false;
    assert(mock_fail_count == before + 1);
}

static void test_failure_guards(void) {
    reset_mock_pool();
    teavm_beforeClasses = (char *)mock_arena;

    /* Out-of-bounds indices trigger fail handler. */
    expect_fail_get(-1);
    expect_fail_get(TEAVM_DYNAMIC_CLASS_POOL_CAPACITY);

    /* Capacity exhaustion triggers "Metaspace size\n". */
    int32_t full_size = TEAVM_DYNAMIC_CLASS_POOL_CAPACITY - 1;
    expect_fail_alloc(&full_size);
    assert(strcmp(mock_last_fail, "Metaspace size\n") == 0);

    /* OOM on chunk 1 triggers allocation failure. */
    int32_t chunk1_size = M3DS_ARRAY_CLASS_CHUNK_SIZE;
    mock_next_override = NULL;
    mock_use_override = true;
    expect_fail_alloc(&chunk1_size);

    /* Unaligned chunk address triggers packed range failure. */
    chunk1_size = M3DS_ARRAY_CLASS_CHUNK_SIZE;
    mock_next_override = (void *)(mock_arena + 68U);
    mock_use_override = true;
    expect_fail_alloc(&chunk1_size);

    /* Address <= teavm_beforeClasses triggers packed range failure. */
    chunk1_size = M3DS_ARRAY_CLASS_CHUNK_SIZE;
    teavm_beforeClasses = (char *)(mock_arena + 256U);
    mock_next_override = (void *)(mock_arena + 128U);
    mock_use_override = true;
    expect_fail_alloc(&chunk1_size);
}

int main(void) {
    test_range_validation_edges();
    test_lazy_multi_chunk_allocation_and_stability();
    test_failure_guards();
    printf("test_array_class_pool: all assertions passed.\n");
    return 0;
}
