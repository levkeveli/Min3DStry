#include "sim/sector_progress.h"

#include <string.h>

#define M3DS_PROGRESS_HEADER_BYTES 16U
#define M3DS_PROGRESS_RECORD_BYTES 12U
#define M3DS_PROGRESS_CHECKSUM_BYTES 4U

static const uint8_t k_magic[4] = {'M', '3', 'P', 'R'};

static void put_u16(uint8_t *out, uint16_t value) {
  out[0] = (uint8_t)(value & 0xFFU);
  out[1] = (uint8_t)((value >> 8) & 0xFFU);
}

static void put_u32(uint8_t *out, uint32_t value) {
  out[0] = (uint8_t)(value & 0xFFU);
  out[1] = (uint8_t)((value >> 8) & 0xFFU);
  out[2] = (uint8_t)((value >> 16) & 0xFFU);
  out[3] = (uint8_t)((value >> 24) & 0xFFU);
}

static uint16_t get_u16(const uint8_t *in) {
  return (uint16_t)((uint16_t)in[0] | ((uint16_t)in[1] << 8));
}

static uint32_t get_u32(const uint8_t *in) {
  return (uint32_t)in[0] | ((uint32_t)in[1] << 8) |
         ((uint32_t)in[2] << 16) | ((uint32_t)in[3] << 24);
}

static uint32_t checksum_fnv1a(const uint8_t *data, size_t size) {
  uint32_t hash = 2166136261U;
  for (size_t i = 0U; i < size; ++i) {
    hash ^= (uint32_t)data[i];
    hash *= 16777619U;
  }
  return hash;
}

static int find_sector(const M3dsProgressState *state, uint16_t id) {
  if (!state) {
    return -1;
  }
  for (uint16_t i = 0U; i < state->sector_count; ++i) {
    if (state->sectors[i].id == id) {
      return (int)i;
    }
  }
  return -1;
}

static int find_tech(const M3dsProgressState *state, uint16_t id) {
  if (!state) {
    return -1;
  }
  for (uint16_t i = 0U; i < state->tech_count; ++i) {
    if (state->tech[i].id == id) {
      return (int)i;
    }
  }
  return -1;
}

static bool valid_state(const M3dsProgressState *state) {
  return state != NULL &&
         state->format_version == M3DS_PROGRESS_FORMAT_VERSION &&
         state->sector_count > 0U &&
         state->sector_count <= M3DS_PROGRESS_MAX_SECTORS &&
         state->tech_count <= M3DS_PROGRESS_MAX_TECH;
}

M3dsProgressStatus m3ds_progress_init_fixture_v1(M3dsProgressState *state) {
  if (!state) {
    return M3DS_PROGRESS_ERR_INVALID;
  }

  memset(state, 0, sizeof(*state));
  state->format_version = M3DS_PROGRESS_FORMAT_VERSION;
  state->copper = 0U;
  state->sector_count = 3U;
  state->tech_count = 2U;

  state->sectors[0] = (M3dsSectorProgress){
      .id = 1U,
      .prerequisite_sector = M3DS_PROGRESS_NONE,
      .copper_cost = 0U,
      .unlocked = 1U,
      .completed = 0U,
  };
  state->sectors[1] = (M3dsSectorProgress){
      .id = 2U,
      .prerequisite_sector = 1U,
      .copper_cost = 40U,
      .unlocked = 0U,
      .completed = 0U,
  };
  state->sectors[2] = (M3dsSectorProgress){
      .id = 3U,
      .prerequisite_sector = 2U,
      .copper_cost = 80U,
      .unlocked = 0U,
      .completed = 0U,
  };

  state->tech[0] = (M3dsTechProgress){
      .id = 1U,
      .prerequisite_sector = 1U,
      .copper_cost = 30U,
      .researched = 0U,
  };
  state->tech[1] = (M3dsTechProgress){
      .id = 2U,
      .prerequisite_sector = 2U,
      .copper_cost = 60U,
      .researched = 0U,
  };
  return M3DS_PROGRESS_OK;
}

M3dsProgressStatus m3ds_progress_add_copper(M3dsProgressState *state, uint32_t amount) {
  if (!valid_state(state)) {
    return M3DS_PROGRESS_ERR_INVALID;
  }
  if (UINT32_MAX - state->copper < amount) {
    return M3DS_PROGRESS_ERR_INVALID;
  }
  state->copper += amount;
  return M3DS_PROGRESS_OK;
}

M3dsProgressStatus m3ds_progress_complete_sector(
    M3dsProgressState *state,
    uint16_t sector_id) {
  if (!valid_state(state)) {
    return M3DS_PROGRESS_ERR_INVALID;
  }
  int index = find_sector(state, sector_id);
  if (index < 0) {
    return M3DS_PROGRESS_ERR_INVALID;
  }
  M3dsSectorProgress *sector = &state->sectors[index];
  if (sector->completed) {
    return M3DS_PROGRESS_ERR_ALREADY;
  }
  if (!sector->unlocked) {
    return M3DS_PROGRESS_ERR_LOCKED;
  }
  if (sector->prerequisite_sector != M3DS_PROGRESS_NONE) {
    int prerequisite = find_sector(state, sector->prerequisite_sector);
    if (prerequisite < 0) {
      return M3DS_PROGRESS_ERR_INVALID;
    }
    if (!state->sectors[prerequisite].completed) {
      return M3DS_PROGRESS_ERR_LOCKED;
    }
  }
  if (state->copper < sector->copper_cost) {
    return M3DS_PROGRESS_ERR_INSUFFICIENT;
  }

  state->copper -= sector->copper_cost;
  sector->completed = 1U;

  for (uint16_t i = 0U; i < state->sector_count; ++i) {
    if (!state->sectors[i].completed &&
        state->sectors[i].prerequisite_sector == sector_id) {
      state->sectors[i].unlocked = 1U;
    }
  }
  return M3DS_PROGRESS_OK;
}

M3dsProgressStatus m3ds_progress_research(
    M3dsProgressState *state,
    uint16_t tech_id) {
  if (!valid_state(state)) {
    return M3DS_PROGRESS_ERR_INVALID;
  }
  int index = find_tech(state, tech_id);
  if (index < 0) {
    return M3DS_PROGRESS_ERR_INVALID;
  }
  M3dsTechProgress *tech = &state->tech[index];
  if (tech->researched) {
    return M3DS_PROGRESS_ERR_ALREADY;
  }
  int prerequisite = find_sector(state, tech->prerequisite_sector);
  if (prerequisite < 0) {
    return M3DS_PROGRESS_ERR_INVALID;
  }
  if (!state->sectors[prerequisite].completed) {
    return M3DS_PROGRESS_ERR_LOCKED;
  }
  if (state->copper < tech->copper_cost) {
    return M3DS_PROGRESS_ERR_INSUFFICIENT;
  }

  state->copper -= tech->copper_cost;
  tech->researched = 1U;
  return M3DS_PROGRESS_OK;
}

bool m3ds_progress_sector_unlocked(const M3dsProgressState *state, uint16_t sector_id) {
  int index = find_sector(state, sector_id);
  return index >= 0 && state->sectors[index].unlocked != 0U;
}

bool m3ds_progress_sector_completed(const M3dsProgressState *state, uint16_t sector_id) {
  int index = find_sector(state, sector_id);
  return index >= 0 && state->sectors[index].completed != 0U;
}

bool m3ds_progress_tech_researched(const M3dsProgressState *state, uint16_t tech_id) {
  int index = find_tech(state, tech_id);
  return index >= 0 && state->tech[index].researched != 0U;
}

const char *m3ds_progress_status_name(M3dsProgressStatus status) {
  switch (status) {
    case M3DS_PROGRESS_OK:
      return "ok";
    case M3DS_PROGRESS_ERR_INVALID:
      return "invalid";
    case M3DS_PROGRESS_ERR_LOCKED:
      return "locked";
    case M3DS_PROGRESS_ERR_INSUFFICIENT:
      return "insufficient";
    case M3DS_PROGRESS_ERR_ALREADY:
      return "already";
    case M3DS_PROGRESS_ERR_IO:
      return "io";
    default:
      return "unknown";
  }
}

size_t m3ds_progress_serialize(
    const M3dsProgressState *state,
    uint8_t *buffer,
    size_t capacity) {
  if (!valid_state(state) || !buffer) {
    return 0U;
  }
  size_t required = M3DS_PROGRESS_HEADER_BYTES +
                    ((size_t)state->sector_count + (size_t)state->tech_count) *
                        M3DS_PROGRESS_RECORD_BYTES +
                    M3DS_PROGRESS_CHECKSUM_BYTES;
  if (capacity < required) {
    return 0U;
  }

  memset(buffer, 0, required);
  memcpy(buffer, k_magic, sizeof(k_magic));
  put_u16(buffer + 4U, (uint16_t)state->format_version);
  put_u16(buffer + 6U, state->sector_count);
  put_u16(buffer + 8U, state->tech_count);
  put_u16(buffer + 10U, 0U);
  put_u32(buffer + 12U, state->copper);

  size_t offset = M3DS_PROGRESS_HEADER_BYTES;
  for (uint16_t i = 0U; i < state->sector_count; ++i) {
    const M3dsSectorProgress *sector = &state->sectors[i];
    put_u16(buffer + offset, sector->id);
    put_u16(buffer + offset + 2U, sector->prerequisite_sector);
    put_u32(buffer + offset + 4U, sector->copper_cost);
    buffer[offset + 8U] = (uint8_t)((sector->unlocked ? 1U : 0U) |
                                     (sector->completed ? 2U : 0U));
    offset += M3DS_PROGRESS_RECORD_BYTES;
  }
  for (uint16_t i = 0U; i < state->tech_count; ++i) {
    const M3dsTechProgress *tech = &state->tech[i];
    put_u16(buffer + offset, tech->id);
    put_u16(buffer + offset + 2U, tech->prerequisite_sector);
    put_u32(buffer + offset + 4U, tech->copper_cost);
    buffer[offset + 8U] = tech->researched ? 1U : 0U;
    offset += M3DS_PROGRESS_RECORD_BYTES;
  }

  put_u32(buffer + offset, checksum_fnv1a(buffer, offset));
  return required;
}

M3dsProgressStatus m3ds_progress_deserialize(
    M3dsProgressState *state,
    const uint8_t *buffer,
    size_t size) {
  if (!state || !buffer || size < M3DS_PROGRESS_HEADER_BYTES + M3DS_PROGRESS_CHECKSUM_BYTES) {
    return M3DS_PROGRESS_ERR_IO;
  }
  if (memcmp(buffer, k_magic, sizeof(k_magic)) != 0) {
    return M3DS_PROGRESS_ERR_INVALID;
  }
  uint16_t version = get_u16(buffer + 4U);
  uint16_t sectors = get_u16(buffer + 6U);
  uint16_t tech = get_u16(buffer + 8U);
  if (version != M3DS_PROGRESS_FORMAT_VERSION || sectors == 0U ||
      sectors > M3DS_PROGRESS_MAX_SECTORS || tech > M3DS_PROGRESS_MAX_TECH) {
    return M3DS_PROGRESS_ERR_INVALID;
  }
  size_t required = M3DS_PROGRESS_HEADER_BYTES +
                    ((size_t)sectors + (size_t)tech) * M3DS_PROGRESS_RECORD_BYTES +
                    M3DS_PROGRESS_CHECKSUM_BYTES;
  if (size != required) {
    return M3DS_PROGRESS_ERR_INVALID;
  }
  size_t payload = required - M3DS_PROGRESS_CHECKSUM_BYTES;
  if (get_u32(buffer + payload) != checksum_fnv1a(buffer, payload)) {
    return M3DS_PROGRESS_ERR_INVALID;
  }

  M3dsProgressState loaded;
  memset(&loaded, 0, sizeof(loaded));
  loaded.format_version = version;
  loaded.copper = get_u32(buffer + 12U);
  loaded.sector_count = sectors;
  loaded.tech_count = tech;

  size_t offset = M3DS_PROGRESS_HEADER_BYTES;
  for (uint16_t i = 0U; i < sectors; ++i) {
    M3dsSectorProgress *sector = &loaded.sectors[i];
    sector->id = get_u16(buffer + offset);
    sector->prerequisite_sector = get_u16(buffer + offset + 2U);
    sector->copper_cost = get_u32(buffer + offset + 4U);
    uint8_t flags = buffer[offset + 8U];
    if (flags & 0xFCU) {
      return M3DS_PROGRESS_ERR_INVALID;
    }
    sector->unlocked = (uint8_t)(flags & 1U);
    sector->completed = (uint8_t)((flags >> 1) & 1U);
    if (sector->id == 0U ||
        (sector->prerequisite_sector != M3DS_PROGRESS_NONE &&
         sector->prerequisite_sector == sector->id)) {
      return M3DS_PROGRESS_ERR_INVALID;
    }
    offset += M3DS_PROGRESS_RECORD_BYTES;
  }
  for (uint16_t i = 0U; i < tech; ++i) {
    M3dsTechProgress *node = &loaded.tech[i];
    node->id = get_u16(buffer + offset);
    node->prerequisite_sector = get_u16(buffer + offset + 2U);
    node->copper_cost = get_u32(buffer + offset + 4U);
    uint8_t flags = buffer[offset + 8U];
    if ((flags & 0xFEU) != 0U || node->id == 0U) {
      return M3DS_PROGRESS_ERR_INVALID;
    }
    node->researched = (uint8_t)(flags & 1U);
    offset += M3DS_PROGRESS_RECORD_BYTES;
  }

  for (uint16_t i = 0U; i < sectors; ++i) {
    uint16_t prerequisite = loaded.sectors[i].prerequisite_sector;
    if (prerequisite != M3DS_PROGRESS_NONE &&
        find_sector(&loaded, prerequisite) < 0) {
      return M3DS_PROGRESS_ERR_INVALID;
    }
  }
  for (uint16_t i = 0U; i < tech; ++i) {
    if (find_sector(&loaded, loaded.tech[i].prerequisite_sector) < 0) {
      return M3DS_PROGRESS_ERR_INVALID;
    }
  }

  *state = loaded;
  return M3DS_PROGRESS_OK;
}
