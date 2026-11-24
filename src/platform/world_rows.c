#include "platform/world_rows.h"

#include <stdlib.h>
#include <string.h>

#define WORLD_HASH_SEED UINT64_C(1469598103934665603)
#define WORLD_HASH_PRIME UINT64_C(1099511628211)

static uint64_t hash_bytes(uint64_t hash, const uint8_t *data, size_t size) {
    for (size_t i = 0; i < size; ++i) {
        hash ^= data[i];
        hash *= WORLD_HASH_PRIME;
    }
    return hash;
}

static uint64_t mix_hash64(uint64_t hash, uint64_t value) {
    hash ^= value;
    return hash * WORLD_HASH_PRIME;
}

bool m3ds_world_rows_alloc(
    uint32_t width,
    uint32_t height,
    const M3dsWorldTileRow *rows,
    size_t row_count,
    const M3dsWorldBuildingRow *buildings,
    size_t building_count,
    M3dsWorldTiles *out) {
    if (!rows || !out || width == 0u || height == 0u) {
        return false;
    }
    uint64_t total = (uint64_t)width * (uint64_t)height;
    if (total == 0u || total > 2u * 1024u * 1024u
            || row_count != (size_t)total) {
        return false;
    }
    if (building_count > 0u && !buildings) {
        return false;
    }
    memset(out, 0, sizeof(*out));

    size_t count = (size_t)total;
    size_t building_capacity = building_count > 0u ? building_count : 1u;
    out->floor_ids = calloc(count, sizeof(uint16_t));
    out->overlay_ids = calloc(count, sizeof(uint16_t));
    out->block_ids = calloc(count, sizeof(uint16_t));
    out->flags = calloc(count, sizeof(uint8_t));
    /* Every slot is filled below; zeroing keeps the save-state hash safe if
       the row loop is ever changed to skip an entry. */
    out->building_index = calloc(count, sizeof(uint32_t));
    out->tile_states = calloc(count, sizeof(M3dsTileState));
    out->buildings = calloc(building_capacity, sizeof(M3dsBuildingState));
    if (!out->floor_ids || !out->overlay_ids || !out->block_ids
            || !out->flags || !out->building_index || !out->tile_states
            || !out->buildings) {
        m3ds_msav_free_world(out);
        return false;
    }

    out->count = count;
    out->tile_capacity = count;
    out->building_count = building_count;
    out->building_capacity = building_capacity;
    out->width = width;
    out->height = height;

    for (size_t i = 0; i < count; ++i) {
        const M3dsWorldTileRow *row = &rows[i];
        if (row->x != (uint32_t)(i % width)
                || row->y != (uint32_t)(i / width)) {
            m3ds_msav_free_world(out);
            return false;
        }
        if (row->building_index != UINT32_MAX
                && (size_t)row->building_index >= building_count) {
            m3ds_msav_free_world(out);
            return false;
        }
        out->floor_ids[i] = row->floor_id;
        out->overlay_ids[i] = row->overlay_id;
        out->block_ids[i] = row->block_id;
        out->flags[i] = row->flags;
        out->building_index[i] = row->building_index;

        out->tile_states[i].floor_id = row->floor_id;
        out->tile_states[i].overlay_id = row->overlay_id;
        out->tile_states[i].block_id = row->block_id;
        out->tile_states[i].x = row->x;
        out->tile_states[i].y = row->y;
        out->tile_states[i].flags = row->flags;
        out->tile_states[i].building_index = row->building_index;
    }

    for (size_t i = 0; i < building_count; ++i) {
        const M3dsWorldBuildingRow *row = &buildings[i];
        if ((size_t)row->tile_index >= count
                || out->tile_states[row->tile_index].building_index != i
                || row->x != (uint32_t)(row->tile_index % width)
                || row->y != (uint32_t)(row->tile_index / width)
                || (row->payload_size > 0u && !row->payload)) {
            m3ds_msav_free_world(out);
            return false;
        }
        M3dsBuildingState *building = &out->buildings[i];
        building->tile_index = row->tile_index;
        building->x = row->x;
        building->y = row->y;
        building->block_id = row->block_id;
        building->tile = &out->tile_states[row->tile_index];
        building->health = row->health;
        building->item_count = row->item_count;
        building->extra_size = row->extra_size;
        building->team = row->team;
        building->rotation = row->rotation;
        building->enabled = row->enabled;
        building->modules = row->modules;
        building->partial = row->partial != 0u;
        building->revision = row->revision;
        building->payload_size = row->payload_size;
        if (row->payload_size > 0u) {
            building->payload = malloc(row->payload_size);
            if (!building->payload) {
                m3ds_msav_free_world(out);
                return false;
            }
            memcpy(building->payload, row->payload, row->payload_size);
        }
        building->items_truncated = row->items_truncated
            || row->item_count > M3DS_BUILDING_ITEMS_MAX;
        memcpy(building->items, row->items, sizeof(building->items));
        building->power_links = row->power_links;
        building->power_status = row->power_status;
        building->liquid_count = row->liquid_count;
        building->liquid0_id = row->liquid0_id;
        building->liquid0_amount = row->liquid0_amount;
        building->state_hash = row->state_hash;
    }

    out->tile_state_hash = hash_bytes(
        WORLD_HASH_SEED, (const uint8_t *)out->tile_states,
        count * sizeof(M3dsTileState));

    uint64_t building_hash = WORLD_HASH_SEED;
    for (size_t i = 0; i < building_count; ++i) {
        uint8_t revision = out->buildings[i].revision;
        building_hash = hash_bytes(building_hash, &revision, 1u);
        if (out->buildings[i].payload_size > 0u) {
            building_hash = hash_bytes(
                building_hash, out->buildings[i].payload,
                out->buildings[i].payload_size);
        }
    }
    out->building_hash = building_hash;

    uint64_t save_state = WORLD_HASH_SEED;
    for (size_t i = 0; i < count; ++i) {
        uint32_t index = out->building_index[i];
        if (index != UINT32_MAX && index < out->building_count
                && !out->buildings[index].partial) {
            save_state = mix_hash64(
                save_state, out->buildings[index].state_hash);
        }
    }
    out->save_state_hash = save_state;

    uint64_t hash = WORLD_HASH_SEED;
    hash = hash_bytes(
        hash, (const uint8_t *)out->floor_ids, count * sizeof(uint16_t));
    hash = hash_bytes(
        hash, (const uint8_t *)out->overlay_ids, count * sizeof(uint16_t));
    hash = hash_bytes(
        hash, (const uint8_t *)out->block_ids, count * sizeof(uint16_t));
    hash = hash_bytes(hash, out->flags, count);
    out->hash = hash;
    return true;
}
