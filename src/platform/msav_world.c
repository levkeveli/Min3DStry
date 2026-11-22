#include "platform/msav_world.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "platform/world_rows.h"

static uint16_t read_be16(const uint8_t *data) {
    return (uint16_t)(((uint16_t)data[0] << 8) | data[1]);
}

static uint32_t read_be32(const uint8_t *data) {
    return ((uint32_t)data[0] << 24) | ((uint32_t)data[1] << 16)
        | ((uint32_t)data[2] << 8) | (uint32_t)data[3];
}

static float read_float_be(const uint8_t *data) {
    uint32_t bits = read_be32(data);
    float value;
    memcpy(&value, &bits, sizeof(value));
    return value;
}

#define MSAV_HASH_SEED UINT64_C(1469598103934665603)
#define MSAV_HASH_PRIME UINT64_C(1099511628211)

static uint64_t mix_hash(uint64_t hash, uint32_t value) {
    hash ^= value;
    return hash * MSAV_HASH_PRIME;
}

static uint64_t mix_hash64(uint64_t hash, uint64_t value) {
    hash ^= value;
    return hash * MSAV_HASH_PRIME;
}

static uint32_t float_bits(float value) {
    uint32_t bits;
    memcpy(&bits, &value, sizeof(bits));
    return bits;
}

/*
 * Mirrors UpstreamSaveDecodeBaseline so the parity baseline and the native
 * reader hash exactly the same decoded fields.
 */
static uint64_t building_state_hash(
    const M3dsWorldBuildingRow *building, uint32_t item_total,
    uint64_t item_hash, uint32_t power_links, uint64_t link_hash,
    float power_status, uint32_t liquid_count, uint64_t liquid_hash) {
    uint64_t state = MSAV_HASH_SEED;
    state = mix_hash(state, building->x);
    state = mix_hash(state, building->y);
    state = mix_hash(state, building->block_id);
    state = mix_hash(state, building->team);
    state = mix_hash(state, building->rotation);
    state = mix_hash(state, float_bits(building->health));
    state = mix_hash(state, building->enabled);
    state = mix_hash(state, building->item_count);
    state = mix_hash(state, item_total);
    state = mix_hash64(state, item_hash);
    state = mix_hash(state, power_links);
    state = mix_hash64(state, link_hash);
    state = mix_hash(state, float_bits(power_status));
    state = mix_hash(state, liquid_count);
    return mix_hash64(state, liquid_hash);
}

static void parse_building_row(M3dsWorldBuildingRow *building) {
    building->health = 0.0f;
    building->item_count = 0u;
    building->extra_size = building->payload_size;
    building->team = 0u;
    building->rotation = 0u;
    building->enabled = 1u;
    building->modules = 0u;
    building->partial = true;
    building->items_truncated = false;
    building->state_hash = 0u;
    memset(building->items, 0, sizeof(building->items));
    if (building->payload_size < 6u) {
        return;
    }
    building->health = read_float_be(building->payload);
    uint8_t rotation = building->payload[4];
    building->team = building->payload[5];
    building->rotation = rotation & 0x7Fu;
    if ((rotation & 0x80u) == 0u) {
        // Legacy base layout needs the block's module mask; keep the raw bytes.
        return;
    }
    size_t position = 6u;
    uint8_t flags_version = building->payload[position++];
    if (flags_version >= 1u) {
        if (position >= building->payload_size) {
            return;
        }
        building->enabled = building->payload[position++] != 0u;
    }
    if (flags_version >= 2u) {
        if (position >= building->payload_size) {
            return;
        }
        building->modules = building->payload[position++];
    }
    uint64_t item_hash = MSAV_HASH_SEED;
    uint32_t item_total = 0u;
    if ((building->modules & 0x01u) != 0u) {
        if (position + 2u > building->payload_size) {
            return;
        }
        uint32_t count = read_be16(building->payload + position);
        position += 2u;
        if (count > (building->payload_size - position) / 6u) {
            return;
        }
        for (uint32_t i = 0; i < count; ++i) {
            uint16_t item_id = read_be16(building->payload + position);
            uint32_t amount = read_be32(building->payload + position + 2u);
            position += 6u;
            item_total += amount;
            item_hash = mix_hash(item_hash, item_id);
            item_hash = mix_hash(item_hash, amount);
            if (i < M3DS_BUILDING_ITEMS_MAX) {
                building->items[i].item_id = item_id;
                building->items[i].amount = amount;
            } else {
                building->items_truncated = true;
            }
        }
        building->item_count = count;
    }
    uint32_t links = 0u;
    uint64_t link_hash = MSAV_HASH_SEED;
    float power_status = 0.0f;
    if ((building->modules & 0x02u) != 0u) {
        if (position + 2u > building->payload_size) {
            return;
        }
        links = read_be16(building->payload + position);
        position += 2u;
        if (links > (building->payload_size - position) / 4u
                || position + links * 4u + 4u > building->payload_size) {
            return;
        }
        for (uint32_t i = 0; i < links; ++i) {
            link_hash = mix_hash(
                link_hash, read_be32(building->payload + position));
            position += 4u;
        }
        power_status = read_float_be(building->payload + position);
        position += 4u;
        building->power_links = links;
        building->power_status = power_status;
    }
    uint32_t liquids = 0u;
    uint64_t liquid_hash = MSAV_HASH_SEED;
    if ((building->modules & 0x04u) != 0u) {
        if (position + 2u > building->payload_size) {
            return;
        }
        liquids = read_be16(building->payload + position);
        position += 2u;
        if (liquids > (building->payload_size - position) / 6u) {
            return;
        }
        for (uint32_t i = 0; i < liquids; ++i) {
            uint16_t liquid_id = read_be16(building->payload + position);
            float amount = read_float_be(building->payload + position + 2u);
            position += 6u;
            liquid_hash = mix_hash(liquid_hash, liquid_id);
            liquid_hash = mix_hash(liquid_hash, float_bits(amount));
            if (i == 0u) {
                building->liquid0_id = liquid_id;
                building->liquid0_amount = amount;
            }
        }
        building->liquid_count = liquids;
    }
    if ((building->modules & 0x10u) != 0u) {
        if (position + 8u > building->payload_size) {
            return;
        }
        position += 8u;
    }
    if ((building->modules & 0x20u) != 0u) {
        if (position + 4u > building->payload_size) {
            return;
        }
        position += 4u;
    }
    // Unknown bits are not decoded yet.
    if ((building->modules & (uint8_t)~0x3Fu) != 0u) {
        return;
    }
    if (flags_version <= 2u) {
        if (position >= building->payload_size) {
            return;
        }
        ++position;
    }
    if (flags_version >= 3u) {
        if (position + 2u > building->payload_size) {
            return;
        }
        position += 2u;
    }
    if (flags_version >= 4u) {
        if (position + 8u > building->payload_size) {
            return;
        }
        position += 8u;
    }
    building->extra_size = (uint32_t)(building->payload_size - position);
    building->state_hash = building_state_hash(
        building, item_total, item_hash, links, link_hash, power_status,
        liquids, liquid_hash);
    building->partial = false;
}

static uint64_t hash_bytes(uint64_t hash, const uint8_t *data, size_t size) {
    for (size_t i = 0; i < size; ++i) {
        hash ^= data[i];
        hash *= UINT64_C(1099511628211);
    }
    return hash;
}

static bool skip_utf(const uint8_t *data, size_t end, size_t *position) {
    if (*position + 2u > end) {
        return false;
    }
    uint16_t length = read_be16(data + *position);
    *position += 2u;
    if (length > end - *position) {
        return false;
    }
    *position += length;
    return true;
}

static bool parse_entities(
    const uint8_t *data, size_t start, size_t end, M3dsMsavWorld *out) {
    size_t position = start;
    if (position + 2u > end) {
        return false;
    }
    uint16_t mapping = read_be16(data + position);
    position += 2u;
    for (uint16_t i = 0; i < mapping; ++i) {
        if (position + 2u > end) {
            return false;
        }
        position += 2u;
        if (!skip_utf(data, end, &position)) {
            return false;
        }
    }
    out->entity_mapping = mapping;
    if (position + 4u > end) {
        return false;
    }
    uint32_t teams = read_be32(data + position);
    position += 4u;
    // Upstream Team.all currently has 256 entries; keep a format-level bound
    // rather than the previous 64-entry heuristic.
    if (teams > 256u) {
        return false;
    }
    out->entity_teams = teams;
    for (uint32_t i = 0; i < teams; ++i) {
        if (position + 8u > end) {
            return false;
        }
        uint32_t blocks = read_be32(data + position + 4u);
        position += 8u;
        out->entity_team_blocks += blocks;
        if (blocks != 0u) {
            // Team-block configs use TypeIO objects and need the content map.
            out->entities_partial = true;
            out->entity_hash = hash_bytes(
                UINT64_C(1469598103934665603), data + start, end - start);
            return true;
        }
    }
    if (position + 4u > end) {
        return false;
    }
    uint32_t records = read_be32(data + position);
    position += 4u;
    if (records > 1000000u) {
        return false;
    }
    for (uint32_t i = 0; i < records; ++i) {
        if (position + 4u > end) {
            return false;
        }
        uint32_t length = read_be32(data + position);
        position += 4u;
        // Entity records are opaque here; only a zero or oversized chunk is
        // structurally invalid. Do not assume a minimum entity payload size.
        if (length == 0u || length > end - position) {
            return false;
        }
        position += length;
    }
    if (position != end) {
        return false;
    }
    out->entity_records = records;
    out->entity_hash = hash_bytes(
        UINT64_C(1469598103934665603), data + start, end - start);
    return true;
}

static bool locate_map(
    const uint8_t *data, size_t size, uint32_t *version,
    size_t *map_start, size_t *map_end) {
    if (!data || size < 8u || memcmp(data, "MSAV", 4) != 0) {
        return false;
    }
    *version = read_be32(data + 4);
    size_t position = 8u;
    // v12+ inserts the data-patches region before content; older saves do not.
    unsigned skip_regions = *version >= 12u ? 3u : 2u;
    for (unsigned i = 0; i < skip_regions; ++i) {
        if (position + 4u > size) {
            return false;
        }
        uint32_t length = read_be32(data + position);
        position += 4u;
        if (length > size - position) {
            return false;
        }
        position += length;
    }
    if (position + 4u > size) {
        return false;
    }
    uint32_t map_length = read_be32(data + position);
    position += 4u;
    if (map_length < 4u || map_length > size - position) {
        return false;
    }
    *map_start = position;
    *map_end = position + map_length;
    return true;
}

bool m3ds_msav_parse_world_header(
    const uint8_t *data, size_t size, M3dsMsavWorld *out) {
    if (!out) {
        return false;
    }
    memset(out, 0, sizeof(*out));
    size_t map_start;
    size_t map_end;
    if (!locate_map(data, size, &out->version, &map_start, &map_end)) {
        return false;
    }
    uint32_t width = read_be16(data + map_start);
    uint32_t height = read_be16(data + map_start + 2u);
    if (width == 0u || height == 0u || width > 4096u || height > 4096u) {
        return false;
    }
    out->width = width;
    out->height = height;
    out->valid = true;
    return true;
}

bool m3ds_msav_parse_world(
    const uint8_t *data, size_t size, M3dsMsavWorld *out) {
    if (!out) {
        return false;
    }
    memset(out, 0, sizeof(*out));
    size_t map_start;
    size_t map_end;
    if (!locate_map(data, size, &out->version, &map_start, &map_end)) {
        return false;
    }
    uint32_t width = read_be16(data + map_start);
    uint32_t height = read_be16(data + map_start + 2u);
    if (width == 0u || height == 0u || width > 4096u || height > 4096u) {
        return false;
    }
    uint64_t tiles = (uint64_t)width * (uint64_t)height;
    size_t position = map_start + 4u;

    uint64_t created = 0u;
    while (created < tiles) {
        if (position + 5u > map_end) {
            return false;
        }
        uint32_t run = 1u + data[position + 4u];
        position += 5u;
        if (run > tiles - created) {
            return false;
        }
        created += run;
    }
    out->floor_tiles = (uint32_t)created;

    uint64_t block_index = 0u;
    while (block_index < tiles) {
        if (position + 3u > map_end) {
            return false;
        }
        uint8_t packed = data[position + 2u];
        position += 3u;
        ++out->block_records;
        bool has_data = (packed & 4u) != 0u;
        bool has_entity = (packed & 1u) != 0u;
        if (has_data) {
            if (position + 7u > map_end) {
                return false;
            }
            position += 7u;
            ++out->data_tiles;
        }
        bool center = true;
        if (has_entity) {
            if (position + 1u > map_end) {
                return false;
            }
            center = data[position] != 0u;
            ++position;
        }
        if (has_entity && center) {
            if (position + 4u > map_end) {
                return false;
            }
            uint32_t length = read_be32(data + position);
            position += 4u;
            if (length > map_end - position) {
                return false;
            }
            position += length;
            ++out->entity_tiles;
        }
        if (!has_entity && !has_data) {
            if (position + 1u > map_end) {
                return false;
            }
            uint32_t run = 1u + data[position];
            ++position;
            if (run > tiles - block_index) {
                return false;
            }
            block_index += run;
        } else {
            ++block_index;
        }
    }
    if (position != map_end) {
        return false;
    }
    if (map_end + 4u > size) {
        return false;
    }
    uint32_t entity_bytes = read_be32(data + map_end);
    if (entity_bytes > size - map_end - 4u) {
        return false;
    }
    out->entity_region_bytes = entity_bytes;
    size_t entity_start = map_end + 4u;
    if (!parse_entities(data, entity_start, entity_start + entity_bytes, out)) {
        return false;
    }
    out->width = width;
    out->height = height;
    out->map_hash = hash_bytes(UINT64_C(1469598103934665603),
                               data + map_start, map_end - map_start);
    out->valid = true;
    return true;
}

void m3ds_msav_free_world(M3dsWorldTiles *tiles) {
    if (!tiles) {
        return;
    }
    if (tiles->buildings) {
        for (size_t i = 0; i < tiles->building_count; ++i) {
            free(tiles->buildings[i].payload);
        }
    }
    free(tiles->buildings);
    free(tiles->building_index);
    free(tiles->tile_states);
    free(tiles->floor_ids);
    free(tiles->overlay_ids);
    free(tiles->block_ids);
    free(tiles->flags);
    memset(tiles, 0, sizeof(*tiles));
}

size_t m3ds_msav_world_bytes(const M3dsWorldTiles *tiles) {
    if (!tiles) {
        return 0u;
    }
    size_t bytes = tiles->tile_capacity
        * (sizeof(uint16_t) * 3u + sizeof(uint8_t) + sizeof(uint32_t)
           + sizeof(M3dsTileState));
    bytes += tiles->building_capacity * sizeof(M3dsBuildingState);
    for (size_t i = 0; i < tiles->building_count; ++i) {
        bytes += tiles->buildings[i].payload_size;
    }
    return bytes;
}

bool m3ds_msav_alloc_world(
    const uint8_t *data, size_t size, M3dsWorldTiles *out) {
    if (!out) {
        return false;
    }
    memset(out, 0, sizeof(*out));
    M3dsMsavWorld world;
    if (!m3ds_msav_parse_world(data, size, &world)) {
        return false;
    }
    size_t map_start;
    size_t map_end;
    uint32_t version;
    if (!locate_map(data, size, &version, &map_start, &map_end)) {
        return false;
    }
    size_t count = (size_t)world.width * (size_t)world.height;
    if (count == 0u || count > 2u * 1024u * 1024u) {
        return false;
    }
    size_t building_capacity = world.entity_tiles > 0u
        ? (size_t)world.entity_tiles : 1u;
    M3dsWorldTileRow *rows = calloc(count, sizeof(M3dsWorldTileRow));
    M3dsWorldBuildingRow *buildings = calloc(
        building_capacity, sizeof(M3dsWorldBuildingRow));
    if (!rows || !buildings) {
        free(buildings);
        free(rows);
        return false;
    }
    for (size_t i = 0; i < count; ++i) {
        rows[i].x = (uint32_t)(i % world.width);
        rows[i].y = (uint32_t)(i / world.width);
        rows[i].building_index = UINT32_MAX;
    }

    size_t position = map_start + 4u;
    size_t created = 0u;
    while (created < count) {
        uint16_t floor_id = read_be16(data + position);
        uint16_t overlay_id = read_be16(data + position + 2u);
        uint32_t run = 1u + data[position + 4u];
        position += 5u;
        for (uint32_t i = 0; i < run && created < count; ++i) {
            rows[created].floor_id = floor_id;
            rows[created].overlay_id = overlay_id;
            ++created;
        }
    }

    size_t block_index = 0u;
    size_t building_count = 0u;
    bool ok = false;
    while (block_index < count) {
        uint16_t block_id = read_be16(data + position);
        uint8_t packed = data[position + 2u];
        position += 3u;
        bool has_data = (packed & 4u) != 0u;
        bool has_entity = (packed & 1u) != 0u;
        if (has_data) {
            position += 7u;
        }
        bool center = true;
        if (has_entity) {
            center = data[position] != 0u;
            ++position;
        }
        if (has_entity && center) {
            uint32_t length = read_be32(data + position);
            position += 4u;
            if (length < 1u || length > map_end - position
                    || building_count >= building_capacity) {
                goto done;
            }
            M3dsWorldBuildingRow *building = &buildings[building_count];
            building->tile_index = (uint32_t)block_index;
            building->x = (uint32_t)(block_index % world.width);
            building->y = (uint32_t)(block_index / world.width);
            building->block_id = block_id;
            building->revision = data[position];
            building->payload_size = length - 1u;
            if (building->payload_size > 0u) {
                uint8_t *payload = malloc(building->payload_size);
                if (!payload) {
                    goto done;
                }
                memcpy(payload, data + position + 1u, building->payload_size);
                building->payload = payload;
            }
            parse_building_row(building);
            rows[block_index].building_index = (uint32_t)building_count;
            ++building_count;
            position += length;
        }
        uint8_t flags = 0u;
        if (has_data) {
            flags |= 0x2u;
        }
        if (has_entity && center) {
            flags |= 0x4u;
        }
        if (!has_entity && !has_data) {
            uint32_t run = 1u + data[position];
            ++position;
            for (uint32_t i = 0; i < run && block_index < count; ++i) {
                rows[block_index].block_id = block_id;
                rows[block_index].flags = flags;
                ++block_index;
            }
        } else {
            rows[block_index].block_id = block_id;
            rows[block_index].flags = flags;
            ++block_index;
        }
    }

    ok = m3ds_world_rows_alloc(
        world.width, world.height, rows, count, buildings, building_count,
        out);
done:
    for (size_t i = 0; i < building_count; ++i) {
        free((void *)buildings[i].payload);
    }
    free(buildings);
    free(rows);
    return ok;
}
