#include "ui/content_catalog.h"

#include "ui/content_catalog_data.h"

size_t m3ds_catalog_count(M3dsCatalogCategory category) {
    switch (category) {
    case M3DS_CATALOG_BLOCKS:
        return (size_t)M3DS_CATALOG_BLOCK_COUNT;
    case M3DS_CATALOG_ITEMS:
    default:
        return (size_t)M3DS_CATALOG_ITEM_COUNT;
    }
}

const M3dsCatalogEntry *m3ds_catalog_entry(M3dsCatalogCategory category, size_t index) {
    if (index >= m3ds_catalog_count(category)) {
        return NULL;
    }
    switch (category) {
    case M3DS_CATALOG_BLOCKS:
        return &M3DS_CATALOG_BLOCK_ROWS[index];
    case M3DS_CATALOG_ITEMS:
    default:
        return &M3DS_CATALOG_ITEM_ROWS[index];
    }
}

const char *m3ds_catalog_category_name(M3dsCatalogCategory category) {
    switch (category) {
    case M3DS_CATALOG_BLOCKS:
        return "Blocks";
    case M3DS_CATALOG_ITEMS:
    default:
        return "Items";
    }
}
