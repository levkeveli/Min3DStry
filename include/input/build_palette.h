#ifndef MIN3DSTRY_INPUT_BUILD_PALETTE_H
#define MIN3DSTRY_INPUT_BUILD_PALETTE_H

#include "sim/parity_scenario.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_BUILD_PALETTE_COLS M3DS_PARITY_GRID_W
#define M3DS_BUILD_PALETTE_ROWS M3DS_PARITY_GRID_H

typedef enum M3dsBuildChoice {
    M3DS_BUILD_DRILL = 0,
    M3DS_BUILD_CONVEYOR = 1,
    M3DS_BUILD_TURRET = 2,
    M3DS_BUILD_WALL = 3,
    M3DS_BUILD_REMOVE = 4,
    M3DS_BUILD_CHOICE_COUNT = 5
} M3dsBuildChoice;

typedef struct M3dsBuildPalette {
    M3dsBuildChoice selected;
} M3dsBuildPalette;

typedef enum M3dsBuildTouchAction {
    M3DS_BUILD_TOUCH_NONE = 0,
    M3DS_BUILD_TOUCH_SELECT = 1,
    M3DS_BUILD_TOUCH_PLACE = 2,
    M3DS_BUILD_TOUCH_REMOVE = 3
} M3dsBuildTouchAction;

void m3ds_build_palette_init(M3dsBuildPalette *palette);
const char *m3ds_build_choice_name(M3dsBuildChoice choice);
uint8_t m3ds_build_choice_block(M3dsBuildChoice choice);

/*
 * Maps a bottom-screen touch to palette selection or a 16x12 map cell.
 * The bottom region is the palette strip; the remaining area is the build map.
 */
M3dsBuildTouchAction m3ds_build_palette_touch(
    M3dsBuildPalette *palette,
    uint16_t touch_x,
    uint16_t touch_y,
    uint16_t screen_w,
    uint16_t screen_h,
    uint8_t *out_map_x,
    uint8_t *out_map_y);

/* ASCII grid for the bottom-screen console prototype. */
void m3ds_build_palette_format_grid(
    const M3dsParityState *state,
    char *out,
    size_t out_size);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_INPUT_BUILD_PALETTE_H */
