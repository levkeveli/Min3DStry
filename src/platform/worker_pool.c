#include "platform/worker_pool.h"

#include <stddef.h>

#if defined(__3DS__)
#include <3ds.h>
#else
#include <pthread.h>
#endif

#define M3DS_WORKER_STACK_BYTES (16u * 1024u)

typedef struct M3dsWorkerLane {
    M3dsWorkerJob job;
    void *context;
    uint32_t item_count;
    uint32_t chunk_size;
    uint32_t chunk_count;
    uint32_t workers;
    uint32_t lane;
} M3dsWorkerLane;

static void run_chunk(
    const M3dsWorkerLane *lane,
    uint32_t chunk,
    uint32_t item_count,
    uint32_t chunk_size) {
    uint64_t begin = (uint64_t)chunk * (uint64_t)chunk_size;
    uint64_t end = begin + (uint64_t)chunk_size;
    if (end > (uint64_t)item_count) {
        end = (uint64_t)item_count;
    }
    lane->job(lane->context, chunk, (uint32_t)begin, (uint32_t)end);
}

static void run_lane(const M3dsWorkerLane *lane) {
    for (uint32_t chunk = lane->lane; chunk < lane->chunk_count; chunk += lane->workers) {
        run_chunk(lane, chunk, lane->item_count, lane->chunk_size);
    }
}

#if defined(__3DS__)
static void worker_entry(void *raw) {
    run_lane((const M3dsWorkerLane *)raw);
}
#else
static void *worker_entry(void *raw) {
    run_lane((const M3dsWorkerLane *)raw);
    return NULL;
}
#endif

bool m3ds_worker_pool_init(M3dsWorkerPool *pool, uint32_t workers) {
    if (!pool) {
        return false;
    }
    if (workers == 0u) {
        workers = 1u;
    }
    if (workers > M3DS_WORKER_MAX) {
        workers = M3DS_WORKER_MAX;
    }
    pool->workers = workers;
    pool->active_workers = 1u;
    pool->initialized = true;
    return true;
}

void m3ds_worker_pool_shutdown(M3dsWorkerPool *pool) {
    if (!pool) {
        return;
    }
    pool->workers = 0u;
    pool->active_workers = 0u;
    pool->initialized = false;
}

uint32_t m3ds_worker_pool_workers(const M3dsWorkerPool *pool) {
    return pool ? pool->workers : 0u;
}

uint32_t m3ds_worker_pool_active_workers(const M3dsWorkerPool *pool) {
    return pool ? pool->active_workers : 0u;
}

bool m3ds_worker_pool_run(
    M3dsWorkerPool *pool,
    M3dsWorkerJob job,
    void *context,
    uint32_t item_count,
    uint32_t chunk_size) {
    if (!pool || !pool->initialized || !job || chunk_size == 0u) {
        return false;
    }
    if (item_count == 0u) {
        pool->active_workers = 1u;
        return true;
    }

    uint64_t total = (uint64_t)item_count + (uint64_t)chunk_size - 1u;
    uint64_t chunk_count_64 = total / (uint64_t)chunk_size;
    if (chunk_count_64 == 0u || chunk_count_64 > UINT32_MAX) {
        return false;
    }
    uint32_t chunk_count = (uint32_t)chunk_count_64;
    uint32_t workers = pool->workers;
    if (workers > chunk_count) {
        workers = chunk_count;
    }
    if (workers <= 1u) {
        M3dsWorkerLane lane = {job, context, item_count, chunk_size, chunk_count, 1u, 0u};
        run_lane(&lane);
        pool->active_workers = 1u;
        return true;
    }

    M3dsWorkerLane lanes[M3DS_WORKER_MAX];
    for (uint32_t lane = 0u; lane < workers; ++lane) {
        lanes[lane].job = job;
        lanes[lane].context = context;
        lanes[lane].item_count = item_count;
        lanes[lane].chunk_size = chunk_size;
        lanes[lane].chunk_count = chunk_count;
        lanes[lane].workers = workers;
        lanes[lane].lane = lane;
    }

    uint32_t created = 0u;
#if defined(__3DS__)
    Thread threads[M3DS_WORKER_MAX - 1u];
    for (uint32_t lane = 1u; lane < workers; ++lane) {
        threads[created] = threadCreate(
            worker_entry,
            &lanes[lane],
            M3DS_WORKER_STACK_BYTES,
            0x30,
            -2,
            false);
        if (!threads[created]) {
            break;
        }
        created++;
    }
#else
    pthread_t threads[M3DS_WORKER_MAX - 1u];
    for (uint32_t lane = 1u; lane < workers; ++lane) {
        if (pthread_create(&threads[created], NULL, worker_entry, &lanes[lane]) != 0) {
            break;
        }
        created++;
    }
#endif

    run_lane(&lanes[0]);

#if defined(__3DS__)
    for (uint32_t i = 0u; i < created; ++i) {
        (void)threadJoin(threads[i], UINT64_MAX);
        threadFree(threads[i]);
    }
#else
    for (uint32_t i = 0u; i < created; ++i) {
        (void)pthread_join(threads[i], NULL);
    }
#endif

    for (uint32_t lane = created + 1u; lane < workers; ++lane) {
        run_lane(&lanes[lane]);
    }
    pool->active_workers = created + 1u;
    return true;
}
