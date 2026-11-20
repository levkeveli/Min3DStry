#include "platform/memory_budget.h"

#include <stdio.h>
#include <string.h>

static size_t align_up(size_t value, size_t alignment) {
  if (alignment == 0U) {
    return value;
  }
  size_t mask = alignment - 1U;
  if (value > SIZE_MAX - mask) {
    return 0U;
  }
  return (value + mask) & ~mask;
}

static bool valid_category(M3dsMemoryCategory category) {
  return (unsigned int)category < M3DS_MEMORY_CATEGORY_COUNT;
}

static void record_failure(M3dsMemoryBudget *budget, M3dsMemoryStatus status) {
  budget->last_status = status;
  budget->failed_allocations++;
}

size_t m3ds_memory_budget_available(uint32_t app_mem_bytes, size_t reserve_bytes) {
  if (app_mem_bytes == 0U) {
    return 0U;
  }
  size_t total = (size_t)app_mem_bytes;
  if (reserve_bytes >= total) {
    return 0U;
  }
  return total - reserve_bytes;
}

M3dsMemoryStatus m3ds_memory_budget_init(
    M3dsMemoryBudget *budget,
    void *arena,
    size_t capacity,
    size_t world_limit,
    size_t texture_limit,
    size_t transient_limit) {
  if (!budget || !arena || capacity == 0U) {
    return M3DS_MEMORY_ERR_INVALID;
  }

  memset(budget, 0, sizeof(*budget));
  budget->arena = (uint8_t *)arena;
  budget->capacity = capacity;
  budget->category_limit[M3DS_MEMORY_WORLD] = world_limit;
  budget->category_limit[M3DS_MEMORY_TEXTURES] = texture_limit;
  budget->category_limit[M3DS_MEMORY_TRANSIENT] = transient_limit;
  budget->last_status = M3DS_MEMORY_OK;
  return M3DS_MEMORY_OK;
}

void *m3ds_memory_budget_alloc(
    M3dsMemoryBudget *budget,
    M3dsMemoryCategory category,
    size_t bytes) {
  if (!budget || !budget->arena || bytes == 0U || !valid_category(category)) {
    if (budget) {
      record_failure(budget, M3DS_MEMORY_ERR_INVALID);
    }
    return NULL;
  }

  size_t start = align_up(budget->used, M3DS_MEMORY_ALIGNMENT);
  if (start < budget->used || start > budget->capacity ||
      bytes > budget->capacity - start) {
    record_failure(budget, M3DS_MEMORY_ERR_CAPACITY);
    return NULL;
  }

  size_t category_used = budget->category_used[category];
  size_t category_limit = budget->category_limit[category];
  if (category_used > category_limit || bytes > category_limit - category_used) {
    record_failure(budget, M3DS_MEMORY_ERR_CATEGORY);
    return NULL;
  }

  void *result = budget->arena + start;
  budget->used = start + bytes;
  budget->category_used[category] = category_used + bytes;
  if (budget->used > budget->peak) {
    budget->peak = budget->used;
  }
  budget->last_status = M3DS_MEMORY_OK;
  return result;
}

void m3ds_memory_budget_reset(M3dsMemoryBudget *budget) {
  if (!budget) {
    return;
  }
  budget->used = 0U;
  for (size_t i = 0U; i < M3DS_MEMORY_CATEGORY_COUNT; ++i) {
    budget->category_used[i] = 0U;
  }
  budget->last_status = M3DS_MEMORY_OK;
}

size_t m3ds_memory_budget_remaining(const M3dsMemoryBudget *budget) {
  if (!budget || budget->used > budget->capacity) {
    return 0U;
  }
  return budget->capacity - budget->used;
}

size_t m3ds_memory_budget_category_remaining(
    const M3dsMemoryBudget *budget,
    M3dsMemoryCategory category) {
  if (!budget || !valid_category(category)) {
    return 0U;
  }
  size_t used = budget->category_used[category];
  size_t limit = budget->category_limit[category];
  return used >= limit ? 0U : limit - used;
}

const char *m3ds_memory_category_name(M3dsMemoryCategory category) {
  switch (category) {
    case M3DS_MEMORY_WORLD:
      return "world";
    case M3DS_MEMORY_TEXTURES:
      return "textures";
    case M3DS_MEMORY_TRANSIENT:
      return "transient";
    default:
      return "unknown";
  }
}

const char *m3ds_memory_status_name(M3dsMemoryStatus status) {
  switch (status) {
    case M3DS_MEMORY_OK:
      return "ok";
    case M3DS_MEMORY_ERR_INVALID:
      return "invalid";
    case M3DS_MEMORY_ERR_CAPACITY:
      return "capacity";
    case M3DS_MEMORY_ERR_CATEGORY:
      return "category";
    default:
      return "unknown";
  }
}

size_t m3ds_memory_budget_format(
    const M3dsMemoryBudget *budget,
    char *out_buf,
    size_t out_buf_size) {
  if (!budget || !out_buf || out_buf_size == 0U) {
    return 0U;
  }

  int written = snprintf(
      out_buf,
      out_buf_size,
      "budget=%lu used=%lu peak=%lu remaining=%lu "
      "world=%lu/%lu textures=%lu/%lu transient=%lu/%lu failures=%lu status=%s",
      (unsigned long)budget->capacity,
      (unsigned long)budget->used,
      (unsigned long)budget->peak,
      (unsigned long)m3ds_memory_budget_remaining(budget),
      (unsigned long)budget->category_used[M3DS_MEMORY_WORLD],
      (unsigned long)budget->category_limit[M3DS_MEMORY_WORLD],
      (unsigned long)budget->category_used[M3DS_MEMORY_TEXTURES],
      (unsigned long)budget->category_limit[M3DS_MEMORY_TEXTURES],
      (unsigned long)budget->category_used[M3DS_MEMORY_TRANSIENT],
      (unsigned long)budget->category_limit[M3DS_MEMORY_TRANSIENT],
      (unsigned long)budget->failed_allocations,
      m3ds_memory_status_name(budget->last_status));
  if (written < 0) {
    out_buf[0] = '\0';
    return 0U;
  }
  return (size_t)written;
}
