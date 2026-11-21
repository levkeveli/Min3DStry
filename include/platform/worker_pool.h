#ifndef MIN3DSTRY_PLATFORM_WORKER_POOL_H
#define MIN3DSTRY_PLATFORM_WORKER_POOL_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_WORKER_MAX 4u

/*
 * Deterministic chunk scheduler for independent work.
 *
 * Each callback receives one disjoint [begin, end) range. Callbacks must not
 * mutate shared state or reorder simulation ticks; the caller owns ordering
 * and combines per-chunk results in chunk order after the run completes.
 */
typedef void (*M3dsWorkerJob)(
    void *context,
    uint32_t chunk,
    uint32_t begin,
    uint32_t end);

typedef struct M3dsWorkerPool {
    uint32_t workers;
    uint32_t active_workers;
    bool initialized;
} M3dsWorkerPool;

bool m3ds_worker_pool_init(M3dsWorkerPool *pool, uint32_t workers);
void m3ds_worker_pool_shutdown(M3dsWorkerPool *pool);
bool m3ds_worker_pool_run(
    M3dsWorkerPool *pool,
    M3dsWorkerJob job,
    void *context,
    uint32_t item_count,
    uint32_t chunk_size);
uint32_t m3ds_worker_pool_workers(const M3dsWorkerPool *pool);

/* Workers that actually participated in the last run (1 means serial). */
uint32_t m3ds_worker_pool_active_workers(const M3dsWorkerPool *pool);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_WORKER_POOL_H */
