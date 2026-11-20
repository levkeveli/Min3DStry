#ifndef MIN3DSTRY_SIM_SECTOR_PROGRESS_H
#define MIN3DSTRY_SIM_SECTOR_PROGRESS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define M3DS_PROGRESS_FORMAT_VERSION 1U
#define M3DS_PROGRESS_MAX_SECTORS 8U
#define M3DS_PROGRESS_MAX_TECH 8U
#define M3DS_PROGRESS_NONE 0xFFFFU

typedef enum M3dsProgressStatus {
  M3DS_PROGRESS_OK = 0,
  M3DS_PROGRESS_ERR_INVALID = 1,
  M3DS_PROGRESS_ERR_LOCKED = 2,
  M3DS_PROGRESS_ERR_INSUFFICIENT = 3,
  M3DS_PROGRESS_ERR_ALREADY = 4,
  M3DS_PROGRESS_ERR_IO = 5
} M3dsProgressStatus;

typedef struct M3dsSectorProgress {
  uint16_t id;
  uint16_t prerequisite_sector;
  uint32_t copper_cost;
  uint8_t unlocked;
  uint8_t completed;
} M3dsSectorProgress;

typedef struct M3dsTechProgress {
  uint16_t id;
  uint16_t prerequisite_sector;
  uint32_t copper_cost;
  uint8_t researched;
} M3dsTechProgress;

/*
 * Versioned, deterministic campaign-progression state. This is a host-side
 * design prototype for the original campaign; it is not the upstream .msav
 * save format yet.
 */
typedef struct M3dsProgressState {
  uint32_t format_version;
  uint32_t copper;
  uint16_t sector_count;
  uint16_t tech_count;
  M3dsSectorProgress sectors[M3DS_PROGRESS_MAX_SECTORS];
  M3dsTechProgress tech[M3DS_PROGRESS_MAX_TECH];
} M3dsProgressState;

/* Original small fixture: 3 sectors, 2 tech nodes, sector/tech copper costs. */
M3dsProgressStatus m3ds_progress_init_fixture_v1(M3dsProgressState *state);

M3dsProgressStatus m3ds_progress_add_copper(M3dsProgressState *state, uint32_t amount);
M3dsProgressStatus m3ds_progress_complete_sector(M3dsProgressState *state, uint16_t sector_id);
M3dsProgressStatus m3ds_progress_research(M3dsProgressState *state, uint16_t tech_id);

bool m3ds_progress_sector_unlocked(const M3dsProgressState *state, uint16_t sector_id);
bool m3ds_progress_sector_completed(const M3dsProgressState *state, uint16_t sector_id);
bool m3ds_progress_tech_researched(const M3dsProgressState *state, uint16_t tech_id);

const char *m3ds_progress_status_name(M3dsProgressStatus status);

/* Serialize into a caller buffer. Returns 0 when the buffer is too small. */
size_t m3ds_progress_serialize(
    const M3dsProgressState *state,
    uint8_t *buffer,
    size_t capacity);

/* Validate all fields/checksum before replacing the destination state. */
M3dsProgressStatus m3ds_progress_deserialize(
    M3dsProgressState *state,
    const uint8_t *buffer,
    size_t size);

#endif
