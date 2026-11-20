#include "platform/memory_budget.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static uint8_t test_arena[4096] __attribute__((aligned(M3DS_MEMORY_ALIGNMENT)));

static void test_available_budget(void) {
  const size_t mib = 1024U * 1024U;
  assert(m3ds_memory_budget_available(64U * 1024U * 1024U, 16U * mib) == 48U * mib);
  assert(m3ds_memory_budget_available(64U * 1024U * 1024U, 64U * mib) == 0U);
  assert(m3ds_memory_budget_available(64U * 1024U * 1024U, 96U * mib) == 0U);
  assert(m3ds_memory_budget_available(0U, 0U) == 0U);
}

static void test_alignment_and_accounting(void) {
  M3dsMemoryBudget budget;
  assert(
      m3ds_memory_budget_init(&budget, test_arena, 1024U, 512U, 256U, 256U) ==
      M3DS_MEMORY_OK);

  void *world = m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_WORLD, 1U);
  void *textures = m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_TEXTURES, 1U);
  void *transient = m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_TRANSIENT, 1U);
  assert(world && textures && transient);
  assert(((uintptr_t)world % M3DS_MEMORY_ALIGNMENT) == 0U);
  assert(((uintptr_t)textures % M3DS_MEMORY_ALIGNMENT) == 0U);
  assert(((uintptr_t)transient % M3DS_MEMORY_ALIGNMENT) == 0U);
  assert(budget.used == 17U);
  assert(budget.peak == 17U);
  assert(m3ds_memory_budget_remaining(&budget) == 1007U);
  assert(m3ds_memory_budget_category_remaining(&budget, M3DS_MEMORY_WORLD) == 511U);

  m3ds_memory_budget_reset(&budget);
  assert(budget.used == 0U);
  assert(budget.peak == 17U);
  assert(m3ds_memory_budget_remaining(&budget) == 1024U);

  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_WORLD, 512U) != NULL);
  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_TEXTURES, 256U) != NULL);
  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_TRANSIENT, 256U) != NULL);
  assert(m3ds_memory_budget_remaining(&budget) == 0U);
  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_WORLD, 1U) == NULL);
  assert(budget.last_status == M3DS_MEMORY_ERR_CAPACITY);
}

static void test_category_failure_and_recovery(void) {
  M3dsMemoryBudget budget;
  assert(
      m3ds_memory_budget_init(&budget, test_arena, 1024U, 16U, 16U, 16U) ==
      M3DS_MEMORY_OK);

  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_WORLD, 17U) == NULL);
  assert(budget.used == 0U);
  assert(budget.failed_allocations == 1U);
  assert(budget.last_status == M3DS_MEMORY_ERR_CATEGORY);

  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_WORLD, 16U) != NULL);
  assert(budget.used == 16U);
  assert(m3ds_memory_budget_category_remaining(&budget, M3DS_MEMORY_WORLD) == 0U);

  m3ds_memory_budget_reset(&budget);
  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_WORLD, 16U) != NULL);
  assert(budget.failed_allocations == 1U);
  assert(budget.last_status == M3DS_MEMORY_OK);
}

static void test_capacity_failure_and_recovery(void) {
  M3dsMemoryBudget budget;
  assert(
      m3ds_memory_budget_init(&budget, test_arena, 64U, 64U, 64U, 64U) ==
      M3DS_MEMORY_OK);

  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_WORLD, 56U) != NULL);
  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_TRANSIENT, 16U) == NULL);
  assert(budget.used == 56U);
  assert(budget.failed_allocations == 1U);
  assert(budget.last_status == M3DS_MEMORY_ERR_CAPACITY);

  m3ds_memory_budget_reset(&budget);
  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_WORLD, 32U) != NULL);
  assert(budget.used == 32U);
  assert(budget.last_status == M3DS_MEMORY_OK);
}

static void test_invalid_inputs_and_diagnostics(void) {
  M3dsMemoryBudget budget;
  assert(m3ds_memory_budget_init(NULL, test_arena, 32U, 8U, 8U, 8U) == M3DS_MEMORY_ERR_INVALID);
  assert(m3ds_memory_budget_init(&budget, NULL, 32U, 8U, 8U, 8U) == M3DS_MEMORY_ERR_INVALID);
  assert(m3ds_memory_budget_init(&budget, test_arena, 0U, 8U, 8U, 8U) == M3DS_MEMORY_ERR_INVALID);
  assert(m3ds_memory_budget_available(64U, 64U) == 0U);

  assert(
      m3ds_memory_budget_init(&budget, test_arena, 128U, 128U, 128U, 128U) ==
      M3DS_MEMORY_OK);
  assert(m3ds_memory_budget_alloc(NULL, M3DS_MEMORY_WORLD, 1U) == NULL);
  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_WORLD, 0U) == NULL);
  assert(m3ds_memory_budget_alloc(
             &budget, (M3dsMemoryCategory)M3DS_MEMORY_CATEGORY_COUNT, 1U) == NULL);
  assert(budget.failed_allocations == 2U);
  assert(budget.last_status == M3DS_MEMORY_ERR_INVALID);
  assert(m3ds_memory_budget_category_remaining(&budget, M3DS_MEMORY_CATEGORY_COUNT) == 0U);

  assert(m3ds_memory_budget_alloc(&budget, M3DS_MEMORY_WORLD, 8U) != NULL);
  char line[256];
  size_t length = m3ds_memory_budget_format(&budget, line, sizeof(line));
  assert(length > 0U);
  assert(strstr(line, "budget=128") != NULL);
  assert(strstr(line, "world=8/128") != NULL);
  assert(strstr(line, "failures=2") != NULL);
  assert(strstr(line, "status=ok") != NULL);
  assert(m3ds_memory_budget_format(NULL, line, sizeof(line)) == 0U);
  assert(m3ds_memory_budget_format(&budget, NULL, 0U) == 0U);
  assert(strcmp(m3ds_memory_category_name(M3DS_MEMORY_WORLD), "world") == 0);
  assert(strcmp(m3ds_memory_status_name(M3DS_MEMORY_ERR_CAPACITY), "capacity") == 0);
}

int main(void) {
  test_available_budget();
  test_alignment_and_accounting();
  test_category_failure_and_recovery();
  test_capacity_failure_and_recovery();
  test_invalid_inputs_and_diagnostics();
  printf("test_memory_budget: all assertions passed.\n");
  return 0;
}
