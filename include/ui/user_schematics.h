#ifndef MIN3DSTRY_UI_USER_SCHEMATICS_H
#define MIN3DSTRY_UI_USER_SCHEMATICS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/*
 * Read-only import of user schematics from a directory of upstream `.msch`
 * files (for example sd:/3ds/min3dstry/schematics). Parsing reuses the save
 * inflater's prefix decoder and reads only the metadata block, so large
 * schematics are not fully inflated.
 */

#define M3DS_USER_SCHEMATIC_MAX 64
#define M3DS_USER_SCHEMATIC_NAME 48
#define M3DS_USER_SCHEMATIC_SUMMARY 96

typedef struct {
    char name[M3DS_USER_SCHEMATIC_NAME];
    unsigned width;
    unsigned height;
    unsigned blocks;
    char summary[M3DS_USER_SCHEMATIC_SUMMARY];
} M3dsUserSchematic;

/* Parse one in-memory `.msch` file. Returns false for malformed or truncated data. */
bool m3ds_msch_parse(const uint8_t *data, size_t size, M3dsUserSchematic *out);

/*
 * Scan `directory` for `*.msch` files, parse their metadata and sort by name.
 * Missing directories and unreadable/malformed files are skipped. Returns the
 * number of entries written (at most `max`).
 */
size_t m3ds_user_schematics_scan(const char *directory, M3dsUserSchematic *out,
                                 size_t max);

#endif
