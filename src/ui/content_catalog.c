#include "ui/content_catalog.h"

#include <stdbool.h>
#include <string.h>

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

static const char *const CATEGORY_ORDER[] = {
    "distribution", "production", "storage", "liquid", "power", "defense",
    "turret",       "units",      "crafting", "logic", "effect",
};

static const size_t CATEGORY_ORDER_COUNT =
    sizeof(CATEGORY_ORDER) / sizeof(CATEGORY_ORDER[0]);

static bool category_present(const char *name) {
    size_t count = (size_t)M3DS_CATALOG_BLOCK_COUNT;
    for (size_t i = 0; i < count; ++i) {
        if (strcmp(M3DS_CATALOG_BLOCK_ROWS[i].category, name) == 0) {
            return true;
        }
    }
    return false;
}

static const char *filter_name_raw(M3dsCatalogCategory category, size_t filter) {
    if (filter == M3DS_CATALOG_FILTER_ALL || category != M3DS_CATALOG_BLOCKS) {
        return "All";
    }
    size_t seen = 0u;
    for (size_t i = 0; i < CATEGORY_ORDER_COUNT; ++i) {
        if (!category_present(CATEGORY_ORDER[i])) {
            continue;
        }
        ++seen;
        if (seen == filter) {
            return CATEGORY_ORDER[i];
        }
    }
    return NULL;
}

size_t m3ds_catalog_filter_count(M3dsCatalogCategory category) {
    if (category != M3DS_CATALOG_BLOCKS) {
        return 1u;
    }
    size_t count = 1u;
    for (size_t i = 0; i < CATEGORY_ORDER_COUNT; ++i) {
        if (category_present(CATEGORY_ORDER[i])) {
            ++count;
        }
    }
    return count;
}

const char *m3ds_catalog_filter_name(M3dsCatalogCategory category, size_t filter) {
    const char *name = filter_name_raw(category, filter);
    return name ? name : "";
}

size_t m3ds_catalog_filtered_count(M3dsCatalogCategory category, size_t filter) {
    const char *name = filter_name_raw(category, filter);
    if (!name) {
        return 0u;
    }
    if (filter == M3DS_CATALOG_FILTER_ALL ||
        category != M3DS_CATALOG_BLOCKS) {
        return m3ds_catalog_count(category);
    }
    size_t matches = 0u;
    size_t count = m3ds_catalog_count(category);
    for (size_t i = 0; i < count; ++i) {
        const M3dsCatalogEntry *entry = m3ds_catalog_entry(category, i);
        if (entry && strcmp(entry->category, name) == 0) {
            ++matches;
        }
    }
    return matches;
}

const M3dsCatalogEntry *m3ds_catalog_filtered_entry(M3dsCatalogCategory category,
                                                    size_t filter,
                                                    size_t index) {
    const char *name = filter_name_raw(category, filter);
    if (!name) {
        return NULL;
    }
    if (filter == M3DS_CATALOG_FILTER_ALL ||
        category != M3DS_CATALOG_BLOCKS) {
        return m3ds_catalog_entry(category, index);
    }
    size_t seen = 0u;
    size_t count = m3ds_catalog_count(category);
    for (size_t i = 0; i < count; ++i) {
        const M3dsCatalogEntry *entry = m3ds_catalog_entry(category, i);
        if (!entry || strcmp(entry->category, name) != 0) {
            continue;
        }
        if (seen == index) {
            return entry;
        }
        ++seen;
    }
    return NULL;
}

size_t m3ds_catalog_wrap(const char *text, size_t width, char *out, size_t out_size) {    if (!text || !out || out_size == 0u || width == 0u) {
        return 0u;
    }
    size_t written = 0u;
    size_t lines = 0u;
    size_t column = 0u;
    const char *cursor = text;
    bool line_open = false;

    while (*cursor && written + 1u < out_size) {
        while (*cursor == ' ') {
            ++cursor;
        }
        if (!*cursor) {
            break;
        }
        const char *word = cursor;
        size_t word_len = 0u;
        while (word[word_len] && word[word_len] != ' ') {
            ++word_len;
        }
        size_t needed = word_len + (column > 0u ? 1u : 0u);
        if (column > 0u && column + needed > width) {
            out[written++] = '\n';
            ++lines;
            column = 0u;
            line_open = false;
            if (written + 1u >= out_size) {
                break;
            }
        }
        if (column > 0u) {
            out[written++] = ' ';
            ++column;
        }
        size_t take = word_len;
        while (take > 0u && written + 1u < out_size) {
            out[written++] = *word++;
            --take;
            ++column;
            line_open = true;
            if (column == width && written + 1u < out_size) {
                out[written++] = '\n';
                ++lines;
                column = 0u;
                line_open = false;
            }
        }
        cursor += word_len;
    }
    if (line_open) {
        ++lines;
    }
    out[written] = '\0';
    return lines;
}
