#ifndef MIN3DSTRY_PLATFORM_SECTOR_CATALOG_H
#define MIN3DSTRY_PLATFORM_SECTOR_CATALOG_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_SECTOR_PLANET_MAX 16u
#define M3DS_SECTOR_CATALOG_MAX 8u

typedef struct M3dsSectorSlot {
    char planet[M3DS_SECTOR_PLANET_MAX];
    uint32_t id;
    bool valid;
    bool compressed;
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

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_SECTOR_CATALOG_H */
