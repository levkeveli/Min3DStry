#ifndef MIN3DSTRY_UI_CONTENT_CATALOG_H
#define MIN3DSTRY_UI_CONTENT_CATALOG_H

#include <stddef.h>

/*
 * Core Database content slice, generated from the pinned Mindustry v160.8
 * bundle (19a0f40d4451). IDs and display names come from
 * `item.<id>.name` / `block.<id>.name`; blocks without a description entry
 * (environment tiles) are excluded. Upstream category tabs and unlock state
 * are not modelled yet.
 */

typedef enum {
    M3DS_CATALOG_ITEMS = 0,
    M3DS_CATALOG_BLOCKS = 1,
} M3dsCatalogCategory;

typedef struct {
    const char *id;
    const char *name;
} M3dsCatalogEntry;

/* Rows visible in the console catalog view. */
#define M3DS_CATALOG_ROWS 12u

size_t m3ds_catalog_count(M3dsCatalogCategory category);

const M3dsCatalogEntry *m3ds_catalog_entry(M3dsCatalogCategory category, size_t index);

const char *m3ds_catalog_category_name(M3dsCatalogCategory category);

#endif
