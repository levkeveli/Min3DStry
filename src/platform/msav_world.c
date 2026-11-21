#include "platform/msav_world.h"

#include <string.h>

static uint16_t read_be16(const uint8_t *data) {
    return (uint16_t)(((uint16_t)data[0] << 8) | data[1]);
}

static uint32_t read_be32(const uint8_t *data) {
    return ((uint32_t)data[0] << 24) | ((uint32_t)data[1] << 16)
        | ((uint32_t)data[2] << 8) | (uint32_t)data[3];
}

bool m3ds_msav_parse_world_header(
    const uint8_t *data, size_t size, M3dsMsavWorld *out) {
    if (!data || !out || size < 8u || memcmp(data, "MSAV", 4) != 0) {
        return false;
    }
    memset(out, 0, sizeof(*out));
    out->version = read_be32(data + 4);
    size_t position = 8u;
    // v12+ inserts the data-patches region before content; older saves do not.
    unsigned skip_regions = out->version >= 12u ? 3u : 2u;
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
    if (position + 6u > size) {
        return false;
    }
    uint32_t map_length = read_be32(data + position);
    position += 4u;
    if (map_length < 4u || map_length > size - position) {
        return false;
    }
    uint32_t width = read_be16(data + position);
    uint32_t height = read_be16(data + position + 2u);
    if (width == 0u || height == 0u || width > 4096u || height > 4096u) {
        return false;
    }
    out->width = width;
    out->height = height;
    out->valid = true;
    return true;
}
