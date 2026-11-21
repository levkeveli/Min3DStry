#include "render/save_world_preview.h"

#include <stddef.h>

#define PREVIEW_COLUMNS 24u
#define PREVIEW_ROWS 16u

static M3dsColor sample_color(const M3dsWorldTiles *tiles, size_t index) {
    if (tiles->block_ids[index] != 0u) {
        return m3ds_rgba(214, 126, 72, 255);
    }
    if (tiles->overlay_ids[index] != 0u) {
        return m3ds_rgba(176, 112, 78, 255);
    }
    return tiles->floor_ids[index] % 2u == 0u
        ? m3ds_rgba(62, 82, 102, 255)
        : m3ds_rgba(52, 70, 88, 255);
}

bool m3ds_save_world_preview_render(
    const M3dsWorldTiles *tiles,
    const M3dsRenderBackend *backend,
    float x,
    float y,
    float width,
    float height) {
    if (!tiles || !backend || !backend->draw_rect || tiles->count == 0u
            || tiles->width == 0u || tiles->height == 0u
            || width <= 0.0f || height <= 0.0f) {
        return false;
    }
    float cell_w = width / (float)PREVIEW_COLUMNS;
    float cell_h = height / (float)PREVIEW_ROWS;
    for (unsigned row = 0; row < PREVIEW_ROWS; ++row) {
        unsigned source_y = (row * tiles->height) / PREVIEW_ROWS;
        if (source_y >= tiles->height) {
            source_y = tiles->height - 1u;
        }
        for (unsigned column = 0; column < PREVIEW_COLUMNS; ++column) {
            unsigned source_x = (column * tiles->width) / PREVIEW_COLUMNS;
            if (source_x >= tiles->width) {
                source_x = tiles->width - 1u;
            }
            size_t index = (size_t)source_y * tiles->width + source_x;
            if (index >= tiles->count) {
                continue;
            }
            M3dsColor color = sample_color(tiles, index);
            if (!backend->draw_rect(
                    backend->user_data,
                    x + (float)column * cell_w,
                    y + (float)row * cell_h,
                    0.1f,
                    cell_w - 1.0f,
                    cell_h - 1.0f,
                    color)) {
                return false;
            }
        }
    }
    return true;
}
