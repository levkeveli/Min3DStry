#include "platform/save_slot.h"

#include "platform/msav_inflate.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MSAV_ZLIB_PREFIX_BYTES 65536u
#define MSAV_META_PREFIX_BYTES 8192u
#define MSAV_META_ENTRY_LIMIT 64u

static uint16_t read_be16(const uint8_t *data) {
    return (uint16_t)(((uint16_t)data[0] << 8) | data[1]);
}

static uint32_t read_be32(const uint8_t *data) {
    return ((uint32_t)data[0] << 24) | ((uint32_t)data[1] << 16)
        | ((uint32_t)data[2] << 8) | (uint32_t)data[3];
}

static void read_version(const uint8_t *header, M3dsSaveSlotInfo *info) {
    info->valid = true;
    // Upstream SaveIO writes the version with DataOutputStream.writeInt, so the
    // MSAV header is big-endian despite the little-endian zlib wrapper.
    info->version = read_be32(header + 4);
}

static bool read_utf(
    const uint8_t *data, size_t end, size_t *position, char *out,
    size_t out_capacity) {
    if (*position + 2u > end) {
        return false;
    }
    uint16_t length = read_be16(data + *position);
    *position += 2u;
    if (*position + length > end) {
        return false;
    }
    size_t copy = length;
    if (copy + 1u > out_capacity) {
        copy = out_capacity - 1u;
    }
    memcpy(out, data + *position, copy);
    out[copy] = '\0';
    *position += length;
    return true;
}

static uint32_t parse_decimal(const char *text) {
    uint32_t value = 0u;
    for (const char *cursor = text; *cursor >= '0' && *cursor <= '9'; ++cursor) {
        if (value > (UINT32_MAX - 9u) / 10u) {
            return 0u;
        }
        value = value * 10u + (uint32_t)(*cursor - '0');
    }
    return value;
}

static bool parse_entries(
    const uint8_t *data, size_t end, size_t position, uint32_t entries,
    M3dsSaveSlotInfo *info) {
    if (entries > MSAV_META_ENTRY_LIMIT) {
        return false;
    }
    bool has_map = false;
    bool has_wave = false;
    for (uint32_t i = 0; i < entries && position < end; ++i) {
        char key[32];
        char value[64];
        if (!read_utf(data, end, &position, key, sizeof(key))
                || !read_utf(data, end, &position, value, sizeof(value))) {
            return false;
        }
        if (strcmp(key, "mapname") == 0) {
            snprintf(info->map_name, sizeof(info->map_name), "%.*s",
                     (int)(sizeof(info->map_name) - 1u), value);
            has_map = true;
        } else if (strcmp(key, "wave") == 0) {
            info->wave = parse_decimal(value);
            has_wave = true;
        }
    }
    info->has_meta = has_map;
    if (!has_wave) {
        info->wave = 0u;
    }
    return has_map;
}

/*
 * Upstream SaveVersion.getMeta reads a 4-byte meta-region length, then
 * SaveFileReader.readStringMap reads a 16-bit entry count followed by
 * DataOutputStream.writeUTF key/value pairs. Some external tooling writes the
 * entry count as a 4-byte integer instead; accept that layout as a fallback
 * only when the upstream framing does not yield a map name.
 */
static void parse_metadata(
    const uint8_t *data, size_t size, M3dsSaveSlotInfo *info) {
    if (size < 12u) {
        return;
    }
    uint32_t metadata_length = read_be32(data + 8);
    if (metadata_length <= size - 12u && metadata_length >= 2u) {
        size_t end = 12u + metadata_length;
        uint16_t entries = read_be16(data + 12);
        if (parse_entries(data, end, 14u, entries, info)) {
            return;
        }
    }
    uint32_t entries = read_be32(data + 8);
    (void)parse_entries(data, size, 12u, entries, info);
}

static void decode_compressed(FILE *file, M3dsSaveSlotInfo *info) {
    uint8_t *compressed = malloc(MSAV_ZLIB_PREFIX_BYTES);
    uint8_t *decoded = malloc(MSAV_META_PREFIX_BYTES);
    if (!compressed || !decoded) {
        free(compressed);
        free(decoded);
        return;
    }
    size_t decoded_size = 0u;
    if (fseek(file, 0L, SEEK_SET) == 0) {
        size_t read = fread(compressed, 1, MSAV_ZLIB_PREFIX_BYTES, file);
        if (read >= 2u && m3ds_msav_inflate_prefix(
                compressed, read, decoded, MSAV_META_PREFIX_BYTES,
                &decoded_size)
                && decoded_size >= 8u
                && memcmp(decoded, "MSAV", 4) == 0) {
            read_version(decoded, info);
            parse_metadata(decoded, decoded_size, info);
        }
    }
    free(compressed);
    free(decoded);
}

bool m3ds_save_slot_probe(const char *path, M3dsSaveSlotInfo *info) {
    if (!path || !info) {
        return false;
    }
    memset(info, 0, sizeof(*info));
    FILE *file = fopen(path, "rb");
    if (!file) {
        return true;
    }
    info->present = true;
    uint8_t header[8];
    size_t read = fread(header, 1, sizeof(header), file);
    if (fseek(file, 0L, SEEK_END) == 0) {
        long size = ftell(file);
        info->bytes = size > 0 ? (uint32_t)size : 0u;
    }
    if (read == sizeof(header) && memcmp(header, "MSAV", 4) == 0) {
        read_version(header, info);
    } else if (read >= 2u && (header[0] & 0x0fu) == 8u
            && ((((uint32_t)header[0] << 8) | header[1]) % 31u) == 0u) {
        // Upstream SaveIO wraps the MSAV stream in zlib. Decode only the
        // version and metadata prefix; the world body stays compressed.
        info->compressed = true;
        decode_compressed(file, info);
    }
    fclose(file);
    return true;
}
