#include "platform/save_slot.h"

#include "platform/msav_inflate.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MSAV_ZLIB_PREFIX_BYTES 65536u

static void read_version(const uint8_t *header, M3dsSaveSlotInfo *info) {
    info->valid = true;
    // Upstream SaveIO writes the version with DataOutputStream.writeInt, so the
    // MSAV header is big-endian despite the little-endian zlib wrapper.
    info->version = ((uint32_t)header[4] << 24)
        | ((uint32_t)header[5] << 16)
        | ((uint32_t)header[6] << 8)
        | (uint32_t)header[7];
}

static void decode_compressed(FILE *file, M3dsSaveSlotInfo *info) {
    uint8_t *compressed = malloc(MSAV_ZLIB_PREFIX_BYTES);
    if (!compressed) {
        return;
    }
    uint8_t decoded[8];
    size_t decoded_size = 0u;
    if (fseek(file, 0L, SEEK_SET) != 0) {
        free(compressed);
        return;
    }
    size_t read = fread(compressed, 1, MSAV_ZLIB_PREFIX_BYTES, file);
    if (read >= 2u && m3ds_msav_inflate_prefix(
            compressed, read, decoded, sizeof(decoded), &decoded_size)
            && decoded_size == sizeof(decoded)
            && memcmp(decoded, "MSAV", 4) == 0) {
        read_version(decoded, info);
    }
    free(compressed);
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
        // prefix: the version follows the four-byte magic.
        info->compressed = true;
        decode_compressed(file, info);
    }
    fclose(file);
    return true;
}
