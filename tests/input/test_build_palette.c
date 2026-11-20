#include "input/build_palette.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static void test_selection_and_mapping(void) {
    M3dsBuildPalette palette;
    m3ds_build_palette_init(&palette);
    assert(palette.selected == M3DS_BUILD_CONVEYOR);
    assert(strcmp(m3ds_build_choice_name(M3DS_BUILD_DRILL), "Drill") == 0);
    assert(m3ds_build_choice_block(M3DS_BUILD_TURRET) == M3DS_PBLOCK_TURRET);
    assert(m3ds_build_choice_block(M3DS_BUILD_REMOVE) == M3DS_PBLOCK_EMPTY);

    /* Bottom 40 px are the five palette slots (64 px each at 320 wide). */
    assert(m3ds_build_palette_touch(&palette, 10, 230, 320, 240, NULL, NULL) ==
           M3DS_BUILD_TOUCH_SELECT);
    assert(palette.selected == M3DS_BUILD_DRILL);
    assert(m3ds_build_palette_touch(&palette, 70, 230, 320, 240, NULL, NULL) ==
           M3DS_BUILD_TOUCH_SELECT);
    assert(palette.selected == M3DS_BUILD_CONVEYOR);
    assert(m3ds_build_palette_touch(&palette, 300, 230, 320, 240, NULL, NULL) ==
           M3DS_BUILD_TOUCH_SELECT);
    assert(palette.selected == M3DS_BUILD_REMOVE);

    uint8_t x = 0u;
    uint8_t y = 0u;
    assert(m3ds_build_palette_touch(&palette, 160, 100, 320, 240, &x, &y) ==
           M3DS_BUILD_TOUCH_REMOVE);
    assert(x == 8u && y == 6u);

    palette.selected = M3DS_BUILD_WALL;
    assert(m3ds_build_palette_touch(&palette, 160, 100, 320, 240, &x, &y) ==
           M3DS_BUILD_TOUCH_PLACE);
    assert(x == 8u && y == 6u);

    assert(m3ds_build_palette_touch(&palette, 2, 100, 320, 240, &x, &y) ==
           M3DS_BUILD_TOUCH_NONE);
    assert(m3ds_build_palette_touch(&palette, 100, 196, 320, 240, &x, &y) ==
           M3DS_BUILD_TOUCH_NONE);
    assert(m3ds_build_palette_touch(NULL, 100, 100, 320, 240, &x, &y) ==
           M3DS_BUILD_TOUCH_NONE);
    assert(m3ds_build_palette_touch(&palette, 100, 100, 0, 240, &x, &y) ==
           M3DS_BUILD_TOUCH_NONE);
}

static void test_grid_format(void) {
    M3dsParityState state;
    memset(&state, 0, sizeof(state));
    state.grid[0][0].block = M3DS_PBLOCK_CORE;
    state.grid[0][1].block = M3DS_PBLOCK_DRILL;
    state.grid[0][2].block = M3DS_PBLOCK_CONVEYOR;
    state.grid[0][3].block = M3DS_PBLOCK_TURRET;
    state.grid[0][4].block = M3DS_PBLOCK_WALL;
    state.grid[1][0].ore = M3DS_PORE_COPPER;

    char grid[256];
    m3ds_build_palette_format_grid(&state, grid, sizeof(grid));
    assert(grid[0] == '@');
    assert(grid[1] == 'D');
    assert(grid[2] == 'C');
    assert(grid[3] == 'T');
    assert(grid[4] == 'W');
    assert(grid[17] == 'o');
    assert(strlen(grid) ==
           (size_t)M3DS_BUILD_PALETTE_ROWS * (M3DS_BUILD_PALETTE_COLS + 1u));

    char too_small[8];
    m3ds_build_palette_format_grid(&state, too_small, sizeof(too_small));
    assert(too_small[0] == '\0');
    m3ds_build_palette_format_grid(NULL, grid, sizeof(grid));
    assert(grid[0] == '.');
}

int main(void) {
    test_selection_and_mapping();
    test_grid_format();
    puts("build palette tests passed");
    return 0;
}
