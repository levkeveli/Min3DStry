#include "ui/schematics_catalog.h"

#include "ui/schematics_catalog_data.h"

size_t m3ds_schematic_count(void) {
    return (size_t)M3DS_SCHEMATIC_COUNT;
}

const M3dsSchematicRow *m3ds_schematic_at(size_t index) {
    if (index >= m3ds_schematic_count()) {
        return NULL;
    }
    return &M3DS_SCHEMATIC_TABLE[index];
}
