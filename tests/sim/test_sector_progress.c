#include "sim/sector_progress.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static void test_fixture_and_locking(void) {
  M3dsProgressState state;
  assert(m3ds_progress_init_fixture_v1(&state) == M3DS_PROGRESS_OK);
  assert(state.format_version == M3DS_PROGRESS_FORMAT_VERSION);
  assert(state.sector_count == 3U);
  assert(state.tech_count == 2U);
  assert(state.copper == 0U);
  assert(m3ds_progress_sector_unlocked(&state, 1U));
  assert(!m3ds_progress_sector_unlocked(&state, 2U));
  assert(!m3ds_progress_sector_unlocked(&state, 3U));
  assert(!m3ds_progress_tech_researched(&state, 1U));
  assert(!m3ds_progress_tech_researched(&state, 2U));

  assert(m3ds_progress_complete_sector(&state, 3U) == M3DS_PROGRESS_ERR_LOCKED);
  assert(m3ds_progress_research(&state, 1U) == M3DS_PROGRESS_ERR_LOCKED);
  assert(m3ds_progress_complete_sector(&state, 99U) == M3DS_PROGRESS_ERR_INVALID);
  assert(m3ds_progress_research(&state, 99U) == M3DS_PROGRESS_ERR_INVALID);
  assert(strcmp(m3ds_progress_status_name(M3DS_PROGRESS_ERR_LOCKED), "locked") == 0);
}

static void test_unlock_and_costs(void) {
  M3dsProgressState state;
  assert(m3ds_progress_init_fixture_v1(&state) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_add_copper(&state, 100U) == M3DS_PROGRESS_OK);
  assert(state.copper == 100U);

  assert(m3ds_progress_complete_sector(&state, 1U) == M3DS_PROGRESS_OK);
  assert(state.copper == 100U);
  assert(m3ds_progress_sector_completed(&state, 1U));
  assert(m3ds_progress_sector_unlocked(&state, 2U));
  assert(!m3ds_progress_sector_unlocked(&state, 3U));

  assert(m3ds_progress_complete_sector(&state, 2U) == M3DS_PROGRESS_OK);
  assert(state.copper == 60U);
  assert(m3ds_progress_sector_unlocked(&state, 3U));

  assert(m3ds_progress_complete_sector(&state, 3U) == M3DS_PROGRESS_ERR_INSUFFICIENT);
  assert(state.copper == 60U);
  assert(m3ds_progress_add_copper(&state, 20U) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_complete_sector(&state, 3U) == M3DS_PROGRESS_OK);
  assert(state.copper == 0U);
  assert(m3ds_progress_complete_sector(&state, 3U) == M3DS_PROGRESS_ERR_ALREADY);
  assert(state.copper == 0U);
}

static void test_research_gates(void) {
  M3dsProgressState state;
  assert(m3ds_progress_init_fixture_v1(&state) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_add_copper(&state, 100U) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_complete_sector(&state, 1U) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_research(&state, 1U) == M3DS_PROGRESS_OK);
  assert(state.copper == 70U);
  assert(m3ds_progress_tech_researched(&state, 1U));
  assert(m3ds_progress_research(&state, 1U) == M3DS_PROGRESS_ERR_ALREADY);
  assert(m3ds_progress_research(&state, 2U) == M3DS_PROGRESS_ERR_LOCKED);
  assert(m3ds_progress_complete_sector(&state, 2U) == M3DS_PROGRESS_OK);
  assert(state.copper == 30U);
  assert(m3ds_progress_research(&state, 2U) == M3DS_PROGRESS_ERR_INSUFFICIENT);
  assert(m3ds_progress_add_copper(&state, 30U) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_research(&state, 2U) == M3DS_PROGRESS_OK);
  assert(state.copper == 0U);
}

static void test_serialization_roundtrip(void) {
  M3dsProgressState state;
  M3dsProgressState loaded;
  uint8_t buffer[256];
  uint8_t second[256];

  assert(m3ds_progress_init_fixture_v1(&state) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_add_copper(&state, 100U) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_complete_sector(&state, 1U) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_complete_sector(&state, 2U) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_research(&state, 1U) == M3DS_PROGRESS_OK);

  size_t size = m3ds_progress_serialize(&state, buffer, sizeof(buffer));
  assert(size > 0U);
  assert(size < sizeof(buffer));
  assert(m3ds_progress_serialize(&state, buffer, size - 1U) == 0U);

  assert(m3ds_progress_deserialize(&loaded, buffer, size) == M3DS_PROGRESS_OK);
  assert(loaded.format_version == state.format_version);
  assert(loaded.copper == state.copper);
  assert(loaded.sector_count == state.sector_count);
  assert(loaded.tech_count == state.tech_count);
  assert(m3ds_progress_sector_completed(&loaded, 1U));
  assert(m3ds_progress_sector_completed(&loaded, 2U));
  assert(m3ds_progress_tech_researched(&loaded, 1U));

  size_t second_size = m3ds_progress_serialize(&loaded, second, sizeof(second));
  assert(second_size == size);
  assert(memcmp(buffer, second, size) == 0);
}

static void test_deserialize_rejects_bad_input(void) {
  M3dsProgressState state;
  M3dsProgressState destination;
  uint8_t buffer[256];
  uint8_t corrupt[256];

  assert(m3ds_progress_init_fixture_v1(&state) == M3DS_PROGRESS_OK);
  size_t size = m3ds_progress_serialize(&state, buffer, sizeof(buffer));
  assert(size > 0U);

  assert(m3ds_progress_init_fixture_v1(&destination) == M3DS_PROGRESS_OK);
  destination.copper = 7U;

  memcpy(corrupt, buffer, size);
  corrupt[0] ^= 0xFFU;
  assert(m3ds_progress_deserialize(&destination, corrupt, size) == M3DS_PROGRESS_ERR_INVALID);
  assert(destination.copper == 7U);

  memcpy(corrupt, buffer, size);
  corrupt[size - 1U] ^= 0x01U;
  assert(m3ds_progress_deserialize(&destination, corrupt, size) == M3DS_PROGRESS_ERR_INVALID);
  assert(destination.copper == 7U);

  memcpy(corrupt, buffer, size);
  corrupt[4] = 2U;
  assert(m3ds_progress_deserialize(&destination, corrupt, size) == M3DS_PROGRESS_ERR_INVALID);
  assert(m3ds_progress_deserialize(&destination, buffer, size - 1U) == M3DS_PROGRESS_ERR_INVALID);
  assert(m3ds_progress_deserialize(&destination, buffer, size + 1U) == M3DS_PROGRESS_ERR_INVALID);
  assert(m3ds_progress_deserialize(NULL, buffer, size) == M3DS_PROGRESS_ERR_IO);
  assert(m3ds_progress_deserialize(&destination, NULL, size) == M3DS_PROGRESS_ERR_IO);
  assert(destination.copper == 7U);
}

static void test_overflow_and_null_guards(void) {
  M3dsProgressState state;
  assert(m3ds_progress_init_fixture_v1(&state) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_add_copper(&state, UINT32_MAX) == M3DS_PROGRESS_OK);
  assert(m3ds_progress_add_copper(&state, 1U) == M3DS_PROGRESS_ERR_INVALID);
  assert(state.copper == UINT32_MAX);
  assert(m3ds_progress_init_fixture_v1(NULL) == M3DS_PROGRESS_ERR_INVALID);
  assert(m3ds_progress_add_copper(NULL, 1U) == M3DS_PROGRESS_ERR_INVALID);
  assert(m3ds_progress_complete_sector(NULL, 1U) == M3DS_PROGRESS_ERR_INVALID);
  assert(m3ds_progress_research(NULL, 1U) == M3DS_PROGRESS_ERR_INVALID);
  assert(!m3ds_progress_sector_unlocked(NULL, 1U));
  assert(!m3ds_progress_sector_completed(NULL, 1U));
  assert(!m3ds_progress_tech_researched(NULL, 1U));
  assert(m3ds_progress_serialize(NULL, NULL, 0U) == 0U);
  assert(strcmp(m3ds_progress_status_name(M3DS_PROGRESS_OK), "ok") == 0);
}

int main(void) {
  test_fixture_and_locking();
  test_unlock_and_costs();
  test_research_gates();
  test_serialization_roundtrip();
  test_deserialize_rejects_bad_input();
  test_overflow_and_null_guards();
  printf("test_sector_progress: all assertions passed.\n");
  return 0;
}
