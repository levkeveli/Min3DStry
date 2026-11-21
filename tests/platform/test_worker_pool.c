#include "platform/worker_pool.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define TEST_ITEMS 1000u
#define TEST_CHUNK_SIZE 37u
#define TEST_CHUNKS ((TEST_ITEMS + TEST_CHUNK_SIZE - 1u) / TEST_CHUNK_SIZE)

typedef struct TestContext {
    uint32_t parts[TEST_CHUNKS];
    uint32_t calls[TEST_CHUNKS];
} TestContext;

static void sum_job(
    void *context,
    uint32_t chunk,
    uint32_t begin,
    uint32_t end) {
    TestContext *test = (TestContext *)context;
    uint32_t sum = 0u;
    for (uint32_t i = begin; i < end; ++i) {
        sum += i;
    }
    test->parts[chunk] = sum;
    test->calls[chunk] += 1u;
}

static void reset_context(TestContext *test) {
    memset(test, 0, sizeof(*test));
}

static void run_and_check(uint32_t workers) {
    TestContext test;
    reset_context(&test);
    M3dsWorkerPool pool;
    assert(m3ds_worker_pool_init(&pool, workers));
    assert(m3ds_worker_pool_run(
        &pool, sum_job, &test, TEST_ITEMS, TEST_CHUNK_SIZE));
    assert(m3ds_worker_pool_active_workers(&pool) == workers);
    for (uint32_t chunk = 0u; chunk < TEST_CHUNKS; ++chunk) {
        assert(test.calls[chunk] == 1u);
        uint32_t begin = chunk * TEST_CHUNK_SIZE;
        uint32_t end = begin + TEST_CHUNK_SIZE;
        if (end > TEST_ITEMS) {
            end = TEST_ITEMS;
        }
        uint32_t expected = 0u;
        for (uint32_t i = begin; i < end; ++i) {
            expected += i;
        }
        assert(test.parts[chunk] == expected);
    }
    m3ds_worker_pool_shutdown(&pool);
}

int main(void) {
    assert(!m3ds_worker_pool_init(NULL, 2u));
    assert(m3ds_worker_pool_workers(NULL) == 0u);

    M3dsWorkerPool pool;
    assert(m3ds_worker_pool_init(&pool, 0u));
    assert(m3ds_worker_pool_workers(&pool) == 1u);
    assert(!m3ds_worker_pool_run(&pool, NULL, NULL, TEST_ITEMS, TEST_CHUNK_SIZE));
    assert(!m3ds_worker_pool_run(&pool, sum_job, NULL, TEST_ITEMS, 0u));
    m3ds_worker_pool_shutdown(&pool);

    assert(m3ds_worker_pool_init(&pool, 99u));
    assert(m3ds_worker_pool_workers(&pool) == M3DS_WORKER_MAX);
    m3ds_worker_pool_shutdown(&pool);

    assert(m3ds_worker_pool_init(&pool, 1u));
    TestContext empty;
    reset_context(&empty);
    assert(m3ds_worker_pool_run(&pool, sum_job, &empty, 0u, TEST_CHUNK_SIZE));
    assert(m3ds_worker_pool_active_workers(&pool) == 1u);
    for (uint32_t chunk = 0u; chunk < TEST_CHUNKS; ++chunk) {
        assert(empty.calls[chunk] == 0u);
    }
    m3ds_worker_pool_shutdown(&pool);

    run_and_check(1u);
    run_and_check(2u);
    run_and_check(4u);

    puts("test_worker_pool: all assertions passed.");
    return 0;
}
