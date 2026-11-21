#ifndef MIN3DSTRY_PLATFORM_SETTINGS_STORE_H
#define MIN3DSTRY_PLATFORM_SETTINGS_STORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Opaque byte storage for upstream-format settings/saves. These functions do
 * not parse or invent a format; Arc/SaveIO owns the bytes.
 */
bool m3ds_settings_store_write(
    const char *root,
    const void *data,
    size_t size);

bool m3ds_settings_store_read(
    const char *root,
    void *out,
    size_t capacity,
    size_t *out_size);

bool m3ds_sector_store_write(
    const char *root,
    const char *planet,
    uint32_t sector_id,
    const void *data,
    size_t size);

bool m3ds_sector_store_read(
    const char *root,
    const char *planet,
    uint32_t sector_id,
    void *out,
    size_t capacity,
    size_t *out_size);

/*
 * Writes, reads back and removes a tiny probe file under root. This verifies
 * the target filesystem/atomic-rename path without touching upstream settings
 * or save files.
 */
bool m3ds_storage_probe_roundtrip(
    const char *root,
    size_t *out_size);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_SETTINGS_STORE_H */
