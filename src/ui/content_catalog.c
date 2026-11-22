#include "ui/content_catalog.h"

#include <stdbool.h>

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

size_t m3ds_catalog_wrap(const char *text, size_t width, char *out, size_t out_size) {
    if (!text || !out || out_size == 0u || width == 0u) {
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
