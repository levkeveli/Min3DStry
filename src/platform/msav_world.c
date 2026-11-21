#include "platform/msav_world.h"

#include <string.h>

static uint16_t read_be16(const uint8_t *data) {
    return (uint16_t)(((uint16_t)data[0] << 8) | data[1]);
}

static uint32_t read_be32(const uint8_t *data) {
    return ((uint32_t)data[0] << 24) | ((uint32_t)data[1] << 16)
        | ((uint32_t)data[2] << 8) | (uint32_t)data[3];
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
    if (teams > 64u) {
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
        if (length < 5u || length > end - position) {
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
