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
int32_t m3ds_terrain_draw_canvas(int32_t id, int32_t revision, int32_t width, int32_t height,
                                const uint32_t *pixels, float x, float y, float origin_x, float origin_y,
                                float draw_width, float draw_height, float rotation,
                                int32_t color, int32_t mix_color, int32_t blend_mode);
int32_t m3ds_terrain_draw_canvas_quad(int32_t id, int32_t revision, int32_t width, int32_t height,
                                     const uint32_t *pixels, const float *vertices, int32_t offset, int32_t blend_mode);
int32_t m3ds_terrain_draw_logic_display(int32_t id, int32_t revision, int32_t width, int32_t height,
                                       const uint32_t *pixels, const float *vertices, int32_t offset, int32_t blend_mode);
int32_t m3ds_terrain_draw_weather_noise(int32_t index, float x, float y, float width, float height,
                                       float u, float v, float u2, float v2,
                                       int32_t tint, int32_t mix, int32_t blend_mode);
void m3ds_terrain_finish_terrain(void);
int32_t m3ds_terrain_end(void);
void m3ds_terrain_minimap_upload(const uint8_t *pixels, int32_t width, int32_t height);
void m3ds_terrain_minimap_draw(float x, float y, float size);
int32_t m3ds_terrain_build_overlay(int32_t column, int32_t row, int32_t block, int32_t rotation,
                                  int32_t removing, int32_t tile_size, int32_t rotatable, int32_t catalog_open,
                                  int32_t configuring, int32_t selecting_mine, int32_t inventory_action,
                                  int32_t command_mode, int32_t selecting_control, int32_t placing_schematic);
float m3ds_terrain_draw_chat_line(const char *label, const int32_t *characters, int32_t count, float bottom);
float m3ds_terrain_draw_objective_plain(const char *label, const int32_t *characters, int32_t count,
                                        int32_t page, int32_t left, int32_t top,
                                        int32_t width, int32_t height, float y,
                                        int32_t *stock_font);
void m3ds_terrain_draw_hud_label(const char *label, const int32_t *characters, int32_t count,
    float x, float y, float width, float height);
int32_t m3ds_terrain_draw_hud_sprite(int32_t page, int32_t left, int32_t top,
    int32_t width, int32_t height, float x, float y, float draw_width, float draw_height, int32_t color);
int32_t m3ds_terrain_draw_hud_panel(float x, float y, float width, float height, int32_t color);
float m3ds_terrain_draw_objective_at(const char *label, const int32_t *characters, int32_t count,
                                     int32_t page, int32_t left, int32_t top,
                                     int32_t width, int32_t height, float x, float y,
                                     int32_t *stock_font);
int32_t m3ds_terrain_draw_chat_keyboard(const char *labels);
int32_t m3ds_terrain_schematic_prompt(char *text, int32_t capacity, const char *hint, int32_t confirm);
int32_t m3ds_terrain_build_catalog(const char *labels, const int32_t *icons, int32_t active_rows,
                                    int32_t previous, int32_t next, int32_t catalog);
float m3ds_terrain_draw_objective(const char *label, const int32_t *characters, int32_t count,
                                 int32_t page, int32_t left, int32_t top,
                                 int32_t width, int32_t height, float y, int32_t *stock_font);
int32_t m3ds_terrain_draw_world_label(const char *label, const int32_t *characters, int32_t count,
                                     float x, float y, int32_t flags, float size,
                                     int32_t horizontal, int32_t vertical, int32_t lines);
int32_t m3ds_terrain_campaign_planet_available(void);
int32_t m3ds_terrain_draw_campaign_planet(int32_t planet, const float *matrix, const float *lighting,
    const float *overlay, int32_t vertices, int32_t sector_vertices, const int32_t *surface, int32_t faces);
void m3ds_terrain_release_campaign_planet(void);
int32_t m3ds_terrain_palette_active(void);
void m3ds_terrain_fini(void);
