#ifndef MIN3DSTRY_PLATFORM_WORKER_BENCHMARK_H
#define MIN3DSTRY_PLATFORM_WORKER_BENCHMARK_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_WORKER_BENCH_GRID_W 200u
#define M3DS_WORKER_BENCH_GRID_H 200u
#define M3DS_WORKER_BENCH_ITEMS \
    (M3DS_WORKER_BENCH_GRID_W * M3DS_WORKER_BENCH_GRID_H)
#define M3DS_WORKER_BENCH_CHUNK_SIZE 1024u

typedef struct M3dsWorkerBenchmarkResult {
    uint32_t items;
    uint32_t chunks;
    uint32_t workers;
    uint32_t active_workers;
    uint32_t serial_checksum;
    uint32_t parallel_checksum;
    uint64_t serial_micros;
    uint64_t parallel_micros;
    bool match;
} M3dsWorkerBenchmarkResult;

/*
 * Runs a read-only 200x200 grid hash once on one thread and once with the
 * requested worker count. The returned checksums must match; timings are
 * wall-clock and include thread creation, so they are an evaluation aid, not
 * a frame-time promise.
 */
bool m3ds_worker_benchmark_run(
    uint32_t workers,
    uint32_t iterations,
    M3dsWorkerBenchmarkResult *out_result);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_WORKER_BENCHMARK_H */
