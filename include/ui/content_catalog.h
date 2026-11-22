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
    const char *description;
    const char *category;
} M3dsCatalogEntry;

/* Rows visible in the console catalog view. */
#define M3DS_CATALOG_ROWS 12u

#define M3DS_CATALOG_FILTER_ALL 0u

size_t m3ds_catalog_count(M3dsCatalogCategory category);

const M3dsCatalogEntry *m3ds_catalog_entry(M3dsCatalogCategory category, size_t index);

const char *m3ds_catalog_category_name(M3dsCatalogCategory category);

/*
 * Category filtering. Filter 0 is "All"; 1..N-1 select an upstream block
 * category (items have no categories, so only "All" exists). The filtered
 * accessors preserve the alphabetical order of the generated table.
 */
size_t m3ds_catalog_filter_count(M3dsCatalogCategory category);

const char *m3ds_catalog_filter_name(M3dsCatalogCategory category, size_t filter);

size_t m3ds_catalog_filtered_count(M3dsCatalogCategory category, size_t filter);

const M3dsCatalogEntry *m3ds_catalog_filtered_entry(M3dsCatalogCategory category,
                                                    size_t filter,
                                                    size_t index);

/*
 * Greedy word wrap for the console detail view. Writes at most `out_size - 1`
 * bytes into `out`, separates lines with '\n', and returns the number of
 * complete lines written (0 for empty text).
 */
size_t m3ds_catalog_wrap(const char *text, size_t width, char *out, size_t out_size);

#endif
