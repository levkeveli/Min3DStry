#include "platform/worker_pool.h"

#include <stddef.h>

#if defined(__3DS__)
#include <3ds.h>
#include <malloc.h>
#else
#include <pthread.h>
#endif

#define M3DS_WORKER_STACK_BYTES (16u * 1024u)

typedef struct M3dsWorkerTask {
    M3dsWorkerJob job;
    void *context;
    uint32_t chunk;
    uint32_t begin;
    uint32_t end;
} M3dsWorkerTask;

static void worker_execute(const M3dsWorkerTask *task) {
    task->job(task->context, task->chunk, task->begin, task->end);
}

#if defined(__3DS__)
static void worker_entry(void *raw) {
    worker_execute((const M3dsWorkerTask *)raw);
}
#else
static void *worker_entry(void *raw) {
    worker_execute((const M3dsWorkerTask *)raw);
    return NULL;
}
#endif

static void fill_task(
    M3dsWorkerTask *task,
    M3dsWorkerJob job,
    void *context,
    uint32_t chunk,
    uint32_t item_count,
    uint32_t chunk_size) {
    uint64_t begin = (uint64_t)chunk * (uint64_t)chunk_size;
    uint64_t end = begin + (uint64_t)chunk_size;
    if (end > (uint64_t)item_count) {
        end = (uint64_t)item_count;
    }
    task->job = job;
    task->context = context;
    task->chunk = chunk;
    task->begin = (uint32_t)begin;
    task->end = (uint32_t)end;
}

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
    pool->initialized = true;
    return true;
}

void m3ds_worker_pool_shutdown(M3dsWorkerPool *pool) {
    if (!pool) {
        return;
    }
    pool->workers = 0u;
    pool->initialized = false;
}

uint32_t m3ds_worker_pool_workers(const M3dsWorkerPool *pool) {
    return pool ? pool->workers : 0u;
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
        for (uint32_t chunk = 0u; chunk < chunk_count; ++chunk) {
            M3dsWorkerTask task;
            fill_task(&task, job, context, chunk, item_count, chunk_size);
            worker_execute(&task);
        }
        return true;
    }

    uint32_t parallel = workers - 1u;
    M3dsWorkerTask tasks[M3DS_WORKER_MAX - 1u];
    uint32_t created = 0u;

#if defined(__3DS__)
    Thread threads[M3DS_WORKER_MAX - 1u];
    void *stacks[M3DS_WORKER_MAX - 1u];
    for (uint32_t i = 0u; i < parallel && i + 1u < chunk_count; ++i) {
        stacks[i] = memalign(8u, M3DS_WORKER_STACK_BYTES);
        if (!stacks[i]) {
            break;
        }
        fill_task(&tasks[i], job, context, i + 1u, item_count, chunk_size);
        threads[i] = threadCreate(
            worker_entry,
            &tasks[i],
            M3DS_WORKER_STACK_BYTES,
            0x30,
            -2,
            false);
        if (!threads[i]) {
            free(stacks[i]);
            stacks[i] = NULL;
            break;
        }
        created++;
    }

    M3dsWorkerTask first;
    fill_task(&first, job, context, 0u, item_count, chunk_size);
    worker_execute(&first);

    for (uint32_t i = 0u; i < created; ++i) {
        (void)threadJoin(threads[i], UINT64_MAX);
        threadFree(threads[i]);
        free(stacks[i]);
    }
#else
    pthread_t threads[M3DS_WORKER_MAX - 1u];
    for (uint32_t i = 0u; i < parallel && i + 1u < chunk_count; ++i) {
        fill_task(&tasks[i], job, context, i + 1u, item_count, chunk_size);
        if (pthread_create(&threads[i], NULL, worker_entry, &tasks[i]) != 0) {
            break;
        }
        created++;
    }

    M3dsWorkerTask first;
    fill_task(&first, job, context, 0u, item_count, chunk_size);
    worker_execute(&first);

    for (uint32_t i = 0u; i < created; ++i) {
        (void)pthread_join(threads[i], NULL);
    }
#endif

    for (uint32_t chunk = created + 1u; chunk < chunk_count; ++chunk) {
        M3dsWorkerTask task;
        fill_task(&task, job, context, chunk, item_count, chunk_size);
        worker_execute(&task);
    }
    return true;
}
