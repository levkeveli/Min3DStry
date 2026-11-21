#ifndef MIN3DSTRY_PLATFORM_SAVE_SLOT_H
#define MIN3DSTRY_PLATFORM_SAVE_SLOT_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct M3dsSaveSlotInfo {
    bool present;
    bool valid;
    bool compressed;
    uint32_t version;
    uint32_t bytes;
} M3dsSaveSlotInfo;

/*
 * Reads the upstream Mindustry .msav header ("MSAV" magic and version) and the
 * file size without parsing or rewriting the save. A missing file is not an
 * error: present=false and valid=false. Returns false only for invalid
 * arguments or an I/O failure after the file was opened.
 */
bool m3ds_save_slot_probe(const char *path, M3dsSaveSlotInfo *info);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_SAVE_SLOT_H */
