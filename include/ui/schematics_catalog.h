#ifndef MIN3DSTRY_UI_SCHEMATICS_CATALOG_H
#define MIN3DSTRY_UI_SCHEMATICS_CATALOG_H

#include <stddef.h>

/*
 * Read-only list of the pinned upstream base-part schematics
 * (the core/assets/baseparts .msch files at 19a0f40d4451). The generator extracts the
 * dimensions and block types from the compressed `.msch` payload; importing,
 * exporting or editing schematics is not implemented.
 */

typedef struct {
    const char *id;
    const char *name;
    unsigned width;
    unsigned height;
    unsigned blocks;
    const char *summary;
} M3dsSchematicRow;

#define M3DS_SCHEMATIC_ROWS 12u

size_t m3ds_schematic_count(void);

const M3dsSchematicRow *m3ds_schematic_at(size_t index);

#endif
