#ifndef MIN3DSTRY_PLATFORM_SECTOR_CATALOG_H
#define MIN3DSTRY_PLATFORM_SECTOR_CATALOG_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_SECTOR_PLANET_MAX 16u
#define M3DS_SECTOR_MAP_MAX 48u
#define M3DS_SECTOR_CATALOG_MAX 8u

typedef struct M3dsSectorSlot {
    char planet[M3DS_SECTOR_PLANET_MAX];
    char map_name[M3DS_SECTOR_MAP_MAX];
    uint32_t id;
    uint32_t wave;
    bool valid;
    bool compressed;
    bool has_meta;
    uint32_t bytes;
} M3dsSectorSlot;

/*
 * Scans a directory for upstream sector slot names of the form
 * "sector-<planet>-<id>.msav", inspects each container with
 * m3ds_save_slot_probe and sorts the result by planet then id. Returns the
 * number of slots stored (at most capacity). Missing directories are empty,
 * not errors.
 */
size_t m3ds_sector_catalog_scan(
    const char *dir, M3dsSectorSlot *slots, size_t capacity);

/*
 * Formats one catalog row for the HUD, using the decoded map name and wave
 * when available and falling back to version/bytes otherwise.
 */
bool m3ds_sector_catalog_label(
    const M3dsSectorSlot *slot, char *out, size_t capacity);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_SECTOR_CATALOG_H */
