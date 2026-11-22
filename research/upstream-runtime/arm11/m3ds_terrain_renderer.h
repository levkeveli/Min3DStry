#pragma once

#include <stdint.h>

int32_t m3ds_terrain_initialize(void);
int32_t m3ds_terrain_mount_assets(void);
int32_t m3ds_terrain_system_language(void);
int32_t m3ds_terrain_build_labels(const char *labels);
int32_t m3ds_terrain_load_stock_font(int32_t japanese);
int32_t m3ds_terrain_begin(int32_t left, int32_t bottom, int32_t width, int32_t height,
                           int32_t tile_size);
int32_t m3ds_terrain_draw(int32_t page, int32_t left, int32_t top, int32_t width, int32_t height,
                          float x, float y, float origin_x, float origin_y, float draw_width,
                          float draw_height, float rotation, int32_t color, int32_t mix_color, int32_t blend_mode);
int32_t m3ds_terrain_draw_solid_quad(const float *vertices, int32_t offset, int32_t blend_mode);
int32_t m3ds_terrain_draw_textured_quad(int32_t page, int32_t width, int32_t height,
                                      const float *vertices, int32_t offset, int32_t blend_mode);
int32_t m3ds_terrain_draw_unit_build(int32_t page, int32_t left, int32_t top, int32_t width, int32_t height,
                                     float x, float y, float draw_width, float draw_height, float rotation,
                                     float progress, float r, float g, float b, float alpha, float phase,
                                     int32_t vertex_color);
int32_t m3ds_terrain_end(void);
int32_t m3ds_terrain_build_overlay(int32_t column, int32_t row, int32_t block, int32_t rotation,
                                  int32_t removing, int32_t tile_size, int32_t rotatable, int32_t catalog_open,
                                  int32_t configuring, int32_t selecting_mine);
int32_t m3ds_terrain_build_catalog(const char *labels, const int32_t *icons, int32_t selected,
                                    int32_t previous, int32_t next, int32_t catalog);
float m3ds_terrain_draw_objective(const char *label, const int32_t *characters, int32_t count,
                                 int32_t page, int32_t left, int32_t top,
                                 int32_t width, int32_t height, float y, int32_t *stock_font);
int32_t m3ds_terrain_draw_world_label(const char *label, const int32_t *characters, int32_t count,
                                     float x, float y, int32_t flags, float size,
                                     int32_t horizontal, int32_t vertical, int32_t lines);
int32_t m3ds_terrain_palette_active(void);
void m3ds_terrain_fini(void);
