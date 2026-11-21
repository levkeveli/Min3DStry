#if !defined(_WIN32) && !defined(__3DS__)
#define _POSIX_C_SOURCE 200809L
#endif

#include "platform/worker_benchmark.h"

#include <string.h>

#include "platform/worker_pool.h"

#if defined(__3DS__)
#include <3ds.h>
#elif defined(_WIN32)
#include <windows.h>
#else
#include <time.h>
#endif

#define M3DS_WORKER_BENCH_MAX_CHUNKS 64u

typedef struct M3dsWorkerBenchmarkContext {
    uint32_t *parts;
} M3dsWorkerBenchmarkContext;

static uint32_t g_grid[M3DS_WORKER_BENCH_ITEMS];

static uint64_t benchmark_now_micros(void) {
#if defined(__3DS__)
    uint64_t ticks = (uint64_t)svcGetSystemTick();
    return ticks / ((uint64_t)SYSCLOCK_ARM11 / 1000000u);
#elif defined(_WIN32)
    LARGE_INTEGER frequency;
    LARGE_INTEGER counter;
    QueryPerformanceFrequency(&frequency);
    QueryPerformanceCounter(&counter);
    return (uint64_t)((counter.QuadPart * 1000000) / frequency.QuadPart);
#else
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) {
        return 0u;
    }
    return (uint64_t)value.tv_sec * 1000000u + (uint64_t)value.tv_nsec / 1000u;
#endif
}

static void benchmark_init_grid(void) {
    for (uint32_t i = 0u; i < M3DS_WORKER_BENCH_ITEMS; ++i) {
        g_grid[i] = (i * 2654435761u) ^ (i >> 7) ^ 0x9E3779B9u;
    }
}

static uint32_t benchmark_hash(const uint32_t *values, uint32_t begin, uint32_t end) {
    uint32_t hash = 2166136261u;
    for (uint32_t i = begin; i < end; ++i) {
        hash ^= values[i];
        hash *= 16777619u;
    }
    return hash;
}

static void benchmark_job(
    void *context,
    uint32_t chunk,
    uint32_t begin,
    uint32_t end) {
    M3dsWorkerBenchmarkContext *job = (M3dsWorkerBenchmarkContext *)context;
    job->parts[chunk] = benchmark_hash(g_grid, begin, end);
}

static uint32_t benchmark_combine(const uint32_t *parts, uint32_t count) {
    uint32_t hash = 2166136261u;
    for (uint32_t i = 0u; i < count; ++i) {
        hash ^= parts[i];
        hash *= 16777619u;
    }
    return hash;
}

static bool benchmark_run_mode(
    uint32_t workers,
    uint32_t iterations,
    uint32_t *out_checksum,
    uint32_t *out_active_workers,
    uint64_t *out_micros) {
    M3dsWorkerPool pool;
    if (!m3ds_worker_pool_init(&pool, workers)) {
        return false;
    }
    M3dsWorkerBenchmarkContext context;
    uint32_t parts[M3DS_WORKER_BENCH_MAX_CHUNKS];
    uint32_t chunks =
        (M3DS_WORKER_BENCH_ITEMS + M3DS_WORKER_BENCH_CHUNK_SIZE - 1u) /
        M3DS_WORKER_BENCH_CHUNK_SIZE;
    if (chunks > M3DS_WORKER_BENCH_MAX_CHUNKS) {
        m3ds_worker_pool_shutdown(&pool);
        return false;
    }
    context.parts = parts;
    memset(parts, 0, sizeof(parts));

    if (!m3ds_worker_pool_run(
            &pool,
            benchmark_job,
            &context,
            M3DS_WORKER_BENCH_ITEMS,
            M3DS_WORKER_BENCH_CHUNK_SIZE)) {
        m3ds_worker_pool_shutdown(&pool);
        return false;
    }

    uint64_t start = benchmark_now_micros();
    for (uint32_t i = 0u; i < iterations; ++i) {
        if (!m3ds_worker_pool_run(
                &pool,
                benchmark_job,
                &context,
                M3DS_WORKER_BENCH_ITEMS,
                M3DS_WORKER_BENCH_CHUNK_SIZE)) {
            m3ds_worker_pool_shutdown(&pool);
            return false;
        }
    }
    uint64_t elapsed = benchmark_now_micros() - start;
    uint32_t active = m3ds_worker_pool_active_workers(&pool);
    m3ds_worker_pool_shutdown(&pool);

    *out_checksum = benchmark_combine(parts, chunks);
    *out_active_workers = active;
    *out_micros = elapsed / iterations;
    return true;
}

bool m3ds_worker_benchmark_run(
    uint32_t workers,
    uint32_t iterations,
    M3dsWorkerBenchmarkResult *out_result) {
    if (!out_result || iterations == 0u) {
        return false;
    }
    if (workers == 0u) {
        workers = 1u;
    }
    if (workers > M3DS_WORKER_MAX) {
        workers = M3DS_WORKER_MAX;
    }

    memset(out_result, 0, sizeof(*out_result));
    benchmark_init_grid();

    uint32_t serial_checksum = 0u;
    uint32_t parallel_checksum = 0u;
    uint32_t serial_active = 0u;
    uint32_t parallel_active = 0u;
    uint64_t serial_micros = 0u;
    uint64_t parallel_micros = 0u;
    if (!benchmark_run_mode(
            1u, iterations, &serial_checksum, &serial_active, &serial_micros) ||
        !benchmark_run_mode(
            workers, iterations, &parallel_checksum, &parallel_active, &parallel_micros) ||
        serial_active != 1u) {
        return false;
    }

    out_result->items = M3DS_WORKER_BENCH_ITEMS;
    out_result->chunks =
        (M3DS_WORKER_BENCH_ITEMS + M3DS_WORKER_BENCH_CHUNK_SIZE - 1u) /
        M3DS_WORKER_BENCH_CHUNK_SIZE;
    out_result->workers = workers;
    out_result->active_workers = parallel_active;
    out_result->serial_checksum = serial_checksum;
    out_result->parallel_checksum = parallel_checksum;
    out_result->serial_micros = serial_micros;
    out_result->parallel_micros = parallel_micros;
    out_result->match = serial_checksum == parallel_checksum;
    return true;
}
