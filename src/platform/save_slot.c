#include "platform/save_slot.h"

#include <stdio.h>
#include <string.h>

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
    fclose(file);
    if (read == sizeof(header) && memcmp(header, "MSAV", 4) == 0) {
        info->valid = true;
        info->version = (uint32_t)header[4]
            | ((uint32_t)header[5] << 8)
            | ((uint32_t)header[6] << 16)
            | ((uint32_t)header[7] << 24);
    } else if (read >= 2u && (header[0] & 0x0fu) == 8u
            && ((((uint32_t)header[0] << 8) | header[1]) % 31u) == 0u) {
        // Upstream SaveIO wraps the MSAV stream in zlib. Decoding the version
        // needs an inflater, which the 3DS build does not link yet.
        info->compressed = true;
    }
    return true;
}
