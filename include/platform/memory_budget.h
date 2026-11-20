#ifndef MIN3DSTRY_PLATFORM_MEMORY_BUDGET_H
#define MIN3DSTRY_PLATFORM_MEMORY_BUDGET_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define M3DS_MEMORY_CATEGORY_COUNT 3U
#define M3DS_MEMORY_ALIGNMENT 8U

typedef enum M3dsMemoryCategory {
  M3DS_MEMORY_WORLD = 0,
  M3DS_MEMORY_TEXTURES = 1,
  M3DS_MEMORY_TRANSIENT = 2
} M3dsMemoryCategory;

typedef enum M3dsMemoryStatus {
  M3DS_MEMORY_OK = 0,
  M3DS_MEMORY_ERR_INVALID = 1,
  M3DS_MEMORY_ERR_CAPACITY = 2,
  M3DS_MEMORY_ERR_CATEGORY = 3
} M3dsMemoryStatus;

/*
 * Bounded bump allocator over a caller-provided arena. It is deliberately
 * simple and deterministic: no malloc, no fragmentation, no reclamation
 * inside a scene. Reset reuses the arena for the next scene.
 */
typedef struct M3dsMemoryBudget {
  uint8_t *arena;
  size_t capacity;
  size_t used;
  size_t peak;
  size_t category_limit[M3DS_MEMORY_CATEGORY_COUNT];
  size_t category_used[M3DS_MEMORY_CATEGORY_COUNT];
  uint32_t failed_allocations;
  M3dsMemoryStatus last_status;
} M3dsMemoryBudget;

/* Bytes available to the application after reserving code/OS/GPU overhead. */
size_t m3ds_memory_budget_available(uint32_t app_mem_bytes, size_t reserve_bytes);

/* Initialize an arena and category caps. Returns M3DS_MEMORY_ERR_INVALID on bad input. */
M3dsMemoryStatus m3ds_memory_budget_init(
    M3dsMemoryBudget *budget,
    void *arena,
    size_t capacity,
    size_t world_limit,
    size_t texture_limit,
    size_t transient_limit);

/* Allocate aligned bytes. On failure returns NULL and records the category/capacity cause. */
void *m3ds_memory_budget_alloc(
    M3dsMemoryBudget *budget,
    M3dsMemoryCategory category,
    size_t bytes);

/* Reclaim the arena for the next scene while retaining peak/failure telemetry. */
void m3ds_memory_budget_reset(M3dsMemoryBudget *budget);

size_t m3ds_memory_budget_remaining(const M3dsMemoryBudget *budget);
size_t m3ds_memory_budget_category_remaining(
    const M3dsMemoryBudget *budget,
    M3dsMemoryCategory category);
const char *m3ds_memory_category_name(M3dsMemoryCategory category);
const char *m3ds_memory_status_name(M3dsMemoryStatus status);

/* Format one compact diagnostic line. Returns the number of bytes written. */
size_t m3ds_memory_budget_format(
    const M3dsMemoryBudget *budget,
    char *out_buf,
    size_t out_buf_size);

#endif
