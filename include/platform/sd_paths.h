#ifndef MIN3DSTRY_PLATFORM_SD_PATHS_H
#define MIN3DSTRY_PLATFORM_SD_PATHS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_SD_ROOT "/3ds/min3dstry"

bool m3ds_sd_join(
    const char *root,
    const char *name,
    char *out,
    size_t out_size);

bool m3ds_sd_settings_path(const char *root, char *out, size_t out_size);
bool m3ds_sd_settings_backup_path(const char *root, char *out, size_t out_size);
bool m3ds_sd_settings_backups_dir(const char *root, char *out, size_t out_size);

bool m3ds_sd_sector_save_path(
    const char *root,
    const char *planet,
    uint32_t sector_id,
    char *out,
    size_t out_size);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_SD_PATHS_H */
