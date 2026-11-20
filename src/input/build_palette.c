#include "input/build_palette.h"

#include <stddef.h>
#include <string.h>

static const char *const m3ds_build_names[M3DS_BUILD_CHOICE_COUNT] = {
    "Drill",
    "Conveyor",
    "Turret",
    "Wall",
    "Remove",
};

static const uint8_t m3ds_build_blocks[M3DS_BUILD_CHOICE_COUNT] = {
    M3DS_PBLOCK_DRILL,
    M3DS_PBLOCK_CONVEYOR,
    M3DS_PBLOCK_TURRET,
    M3DS_PBLOCK_WALL,
    M3DS_PBLOCK_EMPTY,
};

void m3ds_build_palette_init(M3dsBuildPalette *palette) {
    if (!palette) {
        return;
    }
    palette->selected = M3DS_BUILD_CONVEYOR;
}

const char *m3ds_build_choice_name(M3dsBuildChoice choice) {
    if ((unsigned)choice >= (unsigned)M3DS_BUILD_CHOICE_COUNT) {
        return "";
    }
    return m3ds_build_names[choice];
}

uint8_t m3ds_build_choice_block(M3dsBuildChoice choice) {
    if ((unsigned)choice >= (unsigned)M3DS_BUILD_CHOICE_COUNT) {
        return M3DS_PBLOCK_EMPTY;
    }
    return m3ds_build_blocks[choice];
}

M3dsBuildTouchAction m3ds_build_palette_touch(
    M3dsBuildPalette *palette,
    uint16_t touch_x,
    uint16_t touch_y,
    uint16_t screen_w,
    uint16_t screen_h,
    uint8_t *out_map_x,
    uint8_t *out_map_y) {
    if (!palette || screen_w == 0u || screen_h == 0u) {
        return M3DS_BUILD_TOUCH_NONE;
    }

    uint16_t palette_top = (uint16_t)(screen_h - screen_h / 6u);
    if (touch_y >= palette_top) {
        uint16_t slot_w = (uint16_t)(screen_w / M3DS_BUILD_CHOICE_COUNT);
        if (slot_w == 0u) {
            return M3DS_BUILD_TOUCH_NONE;
        }
        uint16_t slot = (uint16_t)(touch_x / slot_w);
        if (slot >= M3DS_BUILD_CHOICE_COUNT) {
            slot = M3DS_BUILD_CHOICE_COUNT - 1u;
        }
        palette->selected = (M3dsBuildChoice)slot;
        return M3DS_BUILD_TOUCH_SELECT;
    }

    const uint16_t margin = 8u;
    uint16_t map_bottom = (uint16_t)(palette_top - margin);
    if (touch_x < margin || touch_y < margin ||
        touch_x >= (uint16_t)(screen_w - margin) || touch_y >= map_bottom) {
        return M3DS_BUILD_TOUCH_NONE;
    }

    uint16_t map_w = (uint16_t)(screen_w - margin * 2u);
    uint16_t map_h = (uint16_t)(map_bottom - margin);
    if (map_w == 0u || map_h == 0u) {
        return M3DS_BUILD_TOUCH_NONE;
    }

    uint32_t gx =
        (uint32_t)(touch_x - margin) * M3DS_BUILD_PALETTE_COLS / map_w;
    uint32_t gy =
        (uint32_t)(touch_y - margin) * M3DS_BUILD_PALETTE_ROWS / map_h;
    if (gx >= M3DS_BUILD_PALETTE_COLS) {
        gx = M3DS_BUILD_PALETTE_COLS - 1u;
    }
    if (gy >= M3DS_BUILD_PALETTE_ROWS) {
        gy = M3DS_BUILD_PALETTE_ROWS - 1u;
    }
    if (out_map_x) {
        *out_map_x = (uint8_t)gx;
    }
    if (out_map_y) {
        *out_map_y = (uint8_t)gy;
    }
    return palette->selected == M3DS_BUILD_REMOVE
               ? M3DS_BUILD_TOUCH_REMOVE
               : M3DS_BUILD_TOUCH_PLACE;
}

void m3ds_build_palette_format_grid(
    const M3dsParityState *state,
    char *out,
    size_t out_size) {
    if (!out || out_size == 0u) {
        return;
    }
    size_t need =
        (size_t)M3DS_BUILD_PALETTE_ROWS * (M3DS_BUILD_PALETTE_COLS + 1u) + 1u;
    if (out_size < need) {
        out[0] = '\0';
        return;
    }

    size_t pos = 0u;
    for (uint32_t y = 0u; y < M3DS_BUILD_PALETTE_ROWS; ++y) {
        for (uint32_t x = 0u; x < M3DS_BUILD_PALETTE_COLS; ++x) {
            char c = '.';
            if (state) {
                const M3dsParityCell *cell = &state->grid[y][x];
                switch (cell->block) {
                case M3DS_PBLOCK_DRILL:
                    c = 'D';
                    break;
                case M3DS_PBLOCK_CONVEYOR:
                    c = 'C';
                    break;
                case M3DS_PBLOCK_TURRET:
                    c = 'T';
                    break;
                case M3DS_PBLOCK_CORE:
                    c = '@';
                    break;
                case M3DS_PBLOCK_WALL:
                    c = 'W';
                    break;
                default:
                    if (cell->ore == M3DS_PORE_COPPER) {
                        c = 'o';
                    }
                    break;
                }
            }
            out[pos++] = c;
        }
        out[pos++] = '\n';
    }
    out[pos] = '\0';
}
