#pragma once

#include <stdint.h>
#include <stdbool.h>

int32_t m3ds_terrain_begin(int32_t left, int32_t bottom, int32_t width, int32_t height,
                           int32_t tile_size);
int32_t m3ds_terrain_draw(int32_t page, int32_t left, int32_t top, int32_t width, int32_t height,
                          float x, float y, float origin_x, float origin_y, float draw_width,
                          float draw_height, float rotation, int32_t color);
int32_t m3ds_terrain_end(void);
int32_t m3ds_terrain_input(void);
int64_t m3ds_terrain_clock_ms(void);
void m3ds_terrain_wait(void);
bool m3ds_terrain_session_finished(void);
void m3ds_terrain_fini(void);
