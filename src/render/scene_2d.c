#include "render/scene_2d.h"

#include <math.h>
#include <stddef.h>
#include <string.h>

typedef struct CircleCmd {
    float cx;
    float cy;
    float z;
    float radius;
    M3dsColor color;
} CircleCmd;

static float clampf(float v, float lo, float hi) {
    if (hi < lo) {
        return lo;
    }
    if (v < lo) {
        return lo;
    }
    if (v > hi) {
        return hi;
    }
    return v;
}

static bool rect_in_viewport(
    float x, float y, float w, float h, const M3dsViewport *vp) {
    if (w <= 0.0f || h <= 0.0f) {
        return false;
    }
    if (x + w <= 0.0f || x >= vp->width) {
        return false;
    }
    if (y + h <= 0.0f || y >= vp->height) {
        return false;
    }
    return true;
}

static bool circle_in_viewport(
    float cx, float cy, float r, const M3dsViewport *vp) {
    return rect_in_viewport(cx - r, cy - r, r * 2.0f, r * 2.0f, vp);
}

static uint32_t total_drawn(const M3dsDrawStats *s) {
    return s->drawn_rects + s->drawn_lines + s->drawn_triangles + s->drawn_circles;
}

static bool emit_rect(
    const M3dsRenderBackend *backend,
    const M3dsViewport *vp,
    M3dsDrawStats *stats,
    float x, float y, float z, float w, float h, M3dsColor color) {
    if (!rect_in_viewport(x, y, w, h, vp)) {
        stats->culled_primitives++;
        return true;
    }
    if (total_drawn(stats) >= M3DS_SCENE_MAX_PRIMITIVES) {
        stats->budget_dropped++;
        return false;
    }
    if (stats->circle_mode_active) {
        stats->state_switches++;
        stats->circle_mode_active = false;
    }
    if (!backend->draw_rect(backend->user_data, x, y, z, w, h, color)) {
        return false;
    }
    stats->drawn_rects++;
    return true;
}

static bool emit_line(
    const M3dsRenderBackend *backend,
    const M3dsViewport *vp,
    M3dsDrawStats *stats,
    float x0, float y0, float x1, float y1,
    float thickness, float z, M3dsColor color) {
    float min_x = (x0 < x1 ? x0 : x1) - thickness;
    float max_x = (x0 > x1 ? x0 : x1) + thickness;
    float min_y = (y0 < y1 ? y0 : y1) - thickness;
    float max_y = (y0 > y1 ? y0 : y1) + thickness;
    if (!rect_in_viewport(min_x, min_y, max_x - min_x, max_y - min_y, vp)) {
        stats->culled_primitives++;
        return true;
    }
    if (total_drawn(stats) >= M3DS_SCENE_MAX_PRIMITIVES) {
        stats->budget_dropped++;
        return false;
    }
    if (stats->circle_mode_active) {
        stats->state_switches++;
        stats->circle_mode_active = false;
    }
    if (!backend->draw_line(
            backend->user_data, x0, y0, x1, y1, thickness, z, color)) {
        return false;
    }
    stats->drawn_lines++;
    return true;
}

static bool emit_triangle(
    const M3dsRenderBackend *backend,
    const M3dsViewport *vp,
    M3dsDrawStats *stats,
    float x0, float y0, float x1, float y1, float x2, float y2,
    float z, M3dsColor color) {
    float min_x = x0 < x1 ? (x0 < x2 ? x0 : x2) : (x1 < x2 ? x1 : x2);
    float max_x = x0 > x1 ? (x0 > x2 ? x0 : x2) : (x1 > x2 ? x1 : x2);
    float min_y = y0 < y1 ? (y0 < y2 ? y0 : y2) : (y1 < y2 ? y1 : y2);
    float max_y = y0 > y1 ? (y0 > y2 ? y0 : y2) : (y1 > y2 ? y1 : y2);
    if (!rect_in_viewport(min_x, min_y, max_x - min_x + 1.0f, max_y - min_y + 1.0f, vp)) {
        stats->culled_primitives++;
        return true;
    }
    if (total_drawn(stats) >= M3DS_SCENE_MAX_PRIMITIVES) {
        stats->budget_dropped++;
        return false;
    }
    if (stats->circle_mode_active) {
        stats->state_switches++;
        stats->circle_mode_active = false;
    }
    if (!backend->draw_triangle(
            backend->user_data, x0, y0, x1, y1, x2, y2, z, color)) {
        return false;
    }
    stats->drawn_triangles++;
    return true;
}

static bool emit_circle(
    const M3dsRenderBackend *backend,
    const M3dsViewport *vp,
    M3dsDrawStats *stats,
    float cx, float cy, float z, float radius, M3dsColor color) {
    if (!circle_in_viewport(cx, cy, radius, vp)) {
        stats->culled_primitives++;
        return true;
    }
    if (total_drawn(stats) >= M3DS_SCENE_MAX_PRIMITIVES) {
        stats->budget_dropped++;
        return false;
    }
    if (!stats->circle_mode_active) {
        stats->state_switches++;
        stats->circle_mode_active = true;
    }
    if (!backend->draw_circle(backend->user_data, cx, cy, z, radius, color)) {
        return false;
    }
    stats->drawn_circles++;
    return true;
}

static void populate_prototype_map(M3dsBoundedScene *scene) {
    memset(scene->tiles, M3DS_TILE_STONE, sizeof(scene->tiles));

    /* Copper ore vein on the left */
    for (int y = 4; y <= 9; ++y) {
        for (int x = 3; x <= 6; ++x) {
            if (((x + y) % 3) != 0) {
                scene->tiles[y][x] = M3DS_TILE_COPPER_ORE;
            }
        }
    }

    /* Lead ore patch on the lower-right */
    for (int y = 14; y <= 18; ++y) {
        for (int x = 22; x <= 26; ++x) {
            if (((x * 3 + y) % 2) == 0) {
                scene->tiles[y][x] = M3DS_TILE_LEAD_ORE;
            }
        }
    }

    /* Conveyor belt feeding right then down into the 3x3 Core */
    for (int x = 6; x <= 17; ++x) {
        scene->tiles[7][x] = M3DS_TILE_CONVEYOR_RIGHT;
    }
    for (int y = 8; y <= 10; ++y) {
        scene->tiles[y][17] = M3DS_TILE_CONVEYOR_DOWN;
    }

    /* Defensive copper wall + Duo turret cluster */
    for (int y = 9; y <= 15; ++y) {
        scene->tiles[y][12] = M3DS_TILE_WALL;
    }
    scene->tiles[10][13] = M3DS_TILE_TURRET;
    scene->tiles[14][13] = M3DS_TILE_TURRET;

    /* 3x3 Core shard at center (16..18, 11..13) */
    for (int y = 11; y <= 13; ++y) {
        for (int x = 16; x <= 18; ++x) {
            scene->tiles[y][x] = M3DS_TILE_CORE;
        }
    }
}

void m3ds_scene_init(M3dsBoundedScene *scene, float viewport_w, float viewport_h) {
    if (!scene) {
        return;
    }
    memset(scene, 0, sizeof(*scene));
    scene->viewport.width = viewport_w > 0.0f ? viewport_w : M3DS_TOP_SCREEN_WIDTH;
    scene->viewport.height = viewport_h > 0.0f ? viewport_h : M3DS_TOP_SCREEN_HEIGHT;
    scene->tick = 0;
    scene->paused = false;
    populate_prototype_map(scene);
    m3ds_scene_reset_camera(scene);
}

void m3ds_scene_reset_camera(M3dsBoundedScene *scene) {
    if (!scene) {
        return;
    }
    float world_w = (float)M3DS_SCENE_GRID_W * M3DS_TILE_SIZE_PX;
    float world_h = (float)M3DS_SCENE_GRID_H * M3DS_TILE_SIZE_PX;
    float max_x = world_w - scene->viewport.width;
    float max_y = world_h - scene->viewport.height;
    /* Center initially on the core/conveyor/turret cluster */
    scene->camera_x = clampf((world_w - scene->viewport.width) * 0.5f, 0.0f, max_x);
    scene->camera_y = clampf((world_h - scene->viewport.height) * 0.5f, 0.0f, max_y);
}

void m3ds_scene_pan(M3dsBoundedScene *scene, float dx, float dy) {
    if (!scene) {
        return;
    }
    float world_w = (float)M3DS_SCENE_GRID_W * M3DS_TILE_SIZE_PX;
    float world_h = (float)M3DS_SCENE_GRID_H * M3DS_TILE_SIZE_PX;
    float max_x = world_w - scene->viewport.width;
    float max_y = world_h - scene->viewport.height;
    scene->camera_x = clampf(scene->camera_x + dx, 0.0f, max_x);
    scene->camera_y = clampf(scene->camera_y + dy, 0.0f, max_y);
}

void m3ds_scene_step(M3dsBoundedScene *scene, uint32_t ticks) {
    if (!scene || scene->paused) {
        return;
    }
    scene->tick += ticks;
}

bool m3ds_scene_render(
    const M3dsBoundedScene *scene,
    const M3dsRenderBackend *backend,
    M3dsDrawStats *out_stats) {
    M3dsDrawStats local_stats;
    memset(&local_stats, 0, sizeof(local_stats));

    if (!scene || !backend ||
        !backend->clear || !backend->draw_rect || !backend->draw_line ||
        !backend->draw_triangle || !backend->draw_circle) {
        if (out_stats) {
            *out_stats = local_stats;
        }
        return false;
    }
    if (scene->viewport.width <= 0.0f || scene->viewport.height <= 0.0f) {
        if (out_stats) {
            *out_stats = local_stats;
        }
        return false;
    }

    const M3dsViewport *vp = &scene->viewport;
    const M3dsColor clr_bg = m3ds_rgba(0x1C, 0x20, 0x28, 0xFF);
    const M3dsColor clr_tile_even = m3ds_rgba(0x28, 0x2E, 0x39, 0xFF);
    const M3dsColor clr_tile_odd = m3ds_rgba(0x23, 0x28, 0x32, 0xFF);
    const M3dsColor clr_copper = m3ds_rgba(0xD9, 0x9D, 0x73, 0xFF);
    const M3dsColor clr_lead = m3ds_rgba(0x8C, 0x7F, 0xA8, 0xFF);
    const M3dsColor clr_belt = m3ds_rgba(0x45, 0x4D, 0x5E, 0xFF);
    const M3dsColor clr_belt_chev = m3ds_rgba(0xF5, 0xB8, 0x41, 0xFF);
    const M3dsColor clr_wall = m3ds_rgba(0xB8, 0x7D, 0x56, 0xFF);
    const M3dsColor clr_wall_hi = m3ds_rgba(0xE0, 0xA8, 0x7E, 0xFF);
    const M3dsColor clr_core = m3ds_rgba(0x3B, 0x46, 0x59, 0xFF);
    const M3dsColor clr_core_glow = m3ds_rgba(0xFF, 0xD3, 0x7F, 0xFF);
    const M3dsColor clr_turret = m3ds_rgba(0x70, 0x7C, 0x90, 0xFF);
    const M3dsColor clr_barrel = m3ds_rgba(0xE6, 0xED, 0xF3, 0xFF);
    const M3dsColor clr_scan_line = m3ds_rgba(0x63, 0xD2, 0xFF, 0x80);

    if (!backend->clear(backend->user_data, clr_bg)) {
        if (out_stats) {
            *out_stats = local_stats;
        }
        return false;
    }

    #define M3DS_MAX_QUEUED_CIRCLES 128u
    CircleCmd queued_circles[M3DS_MAX_QUEUED_CIRCLES];
    uint32_t queued_circle_count = 0;

    float phase = (float)(scene->tick % 16u) / 16.0f;
    float angle = (float)(scene->tick % 360u) * (3.14159265f / 180.0f);

    /* Pass 1: all non-circular primitives (rects, triangles, lines) */
    for (int gy = 0; gy < M3DS_SCENE_GRID_H; ++gy) {
        for (int gx = 0; gx < M3DS_SCENE_GRID_W; ++gx) {
            float sx = (float)gx * M3DS_TILE_SIZE_PX - scene->camera_x;
            float sy = (float)gy * M3DS_TILE_SIZE_PX - scene->camera_y;
            uint8_t kind = scene->tiles[gy][gx];

            M3dsColor base = ((gx + gy) & 1) == 0 ? clr_tile_even : clr_tile_odd;
            if (!emit_rect(backend, vp, &local_stats, sx, sy, 0.0f,
                           M3DS_TILE_SIZE_PX, M3DS_TILE_SIZE_PX, base)) {
                goto finish;
            }

            if (kind == M3DS_TILE_COPPER_ORE) {
                if (queued_circle_count < M3DS_MAX_QUEUED_CIRCLES) {
                    queued_circles[queued_circle_count++] = (CircleCmd){
                        sx + 8.0f, sy + 8.0f, 0.2f, 4.5f, clr_copper
                    };
                } else {
                    local_stats.budget_dropped++;
                }
            } else if (kind == M3DS_TILE_LEAD_ORE) {
                if (queued_circle_count < M3DS_MAX_QUEUED_CIRCLES) {
                    queued_circles[queued_circle_count++] = (CircleCmd){
                        sx + 8.0f, sy + 8.0f, 0.2f, 4.5f, clr_lead
                    };
                } else {
                    local_stats.budget_dropped++;
                }
            } else if (kind == M3DS_TILE_CONVEYOR_RIGHT) {
                if (!emit_rect(backend, vp, &local_stats, sx + 1.0f, sy + 2.0f, 0.1f,
                               14.0f, 12.0f, clr_belt)) {
                    goto finish;
                }
                float cx = sx + 4.0f + phase * 8.0f;
                if (!emit_triangle(backend, vp, &local_stats,
                                   cx - 2.0f, sy + 4.0f,
                                   cx - 2.0f, sy + 12.0f,
                                   cx + 3.0f, sy + 8.0f,
                                   0.2f, clr_belt_chev)) {
                    goto finish;
                }
                uint8_t item = scene->belt_items[gy][gx];
                if (scene->show_sim_items ? item != 0u : (gx + (int)(scene->tick / 16u)) % 2 == 0) {
                    float position = scene->show_sim_items ? (float)(item - 1u) / 254.0f : phase;
                    if (queued_circle_count < M3DS_MAX_QUEUED_CIRCLES) {
                        queued_circles[queued_circle_count++] = (CircleCmd){
                            sx + 2.0f + position * 12.0f, sy + 8.0f, 0.3f, 2.5f, clr_copper
                        };
                    } else {
                        local_stats.budget_dropped++;
                    }
                }
            } else if (kind == M3DS_TILE_CONVEYOR_DOWN) {
                if (!emit_rect(backend, vp, &local_stats, sx + 2.0f, sy + 1.0f, 0.1f,
                               12.0f, 14.0f, clr_belt)) {
                    goto finish;
                }
                float cy = sy + 4.0f + phase * 8.0f;
                if (!emit_triangle(backend, vp, &local_stats,
                                   sx + 4.0f, cy - 2.0f,
                                   sx + 12.0f, cy - 2.0f,
                                   sx + 8.0f, cy + 3.0f,
                                   0.2f, clr_belt_chev)) {
                    goto finish;
                }
                if (!scene->show_sim_items && (gy + (int)(scene->tick / 16u)) % 2 == 0) {
                    if (queued_circle_count < M3DS_MAX_QUEUED_CIRCLES) {
                        queued_circles[queued_circle_count++] = (CircleCmd){
                            sx + 8.0f, sy + 2.0f + phase * 12.0f, 0.3f, 2.5f, clr_copper
                        };
                    } else {
                        local_stats.budget_dropped++;
                    }
                }
            } else if (kind == M3DS_TILE_WALL) {
                if (!emit_rect(backend, vp, &local_stats, sx + 1.0f, sy + 1.0f, 0.2f,
                               14.0f, 14.0f, clr_wall) ||
                    !emit_rect(backend, vp, &local_stats, sx + 4.0f, sy + 4.0f, 0.25f,
                               8.0f, 8.0f, clr_wall_hi)) {
                    goto finish;
                }
            } else if (kind == M3DS_TILE_CORE) {
                if (!emit_rect(backend, vp, &local_stats, sx + 1.0f, sy + 1.0f, 0.2f,
                               14.0f, 14.0f, scene->show_sim_items ? clr_core_glow : clr_core)) {
                    goto finish;
                }
            } else if (kind == M3DS_TILE_TURRET) {
                float tx = sx + 8.0f;
                float ty = sy + 8.0f;
                float dir_x = cosf(angle);
                float dir_y = sinf(angle);
                if (!emit_rect(backend, vp, &local_stats, sx + 2.0f, sy + 2.0f, 0.2f,
                               12.0f, 12.0f, clr_turret) ||
                    !emit_line(backend, vp, &local_stats,
                               tx, ty, tx + dir_x * 10.0f, ty + dir_y * 10.0f,
                               2.5f, 0.35f, clr_barrel) ||
                    !emit_line(backend, vp, &local_stats,
                               tx + dir_x * 10.0f, ty + dir_y * 10.0f,
                               tx + dir_x * 36.0f, ty + dir_y * 36.0f,
                               1.0f, 0.35f, clr_scan_line)) {
                    goto finish;
                }
                if (queued_circle_count < M3DS_MAX_QUEUED_CIRCLES) {
                    queued_circles[queued_circle_count++] = (CircleCmd){
                        tx, ty, 0.3f, 4.0f, clr_belt_chev
                    };
                } else {
                    local_stats.budget_dropped++;
                }
            }
        }
    }

    /* Core center emblem (diamond + pulse circle queued for Pass 2) */
    if (!scene->show_sim_items) {
        float core_cx = 17.5f * M3DS_TILE_SIZE_PX - scene->camera_x;
        float core_cy = 12.5f * M3DS_TILE_SIZE_PX - scene->camera_y;
        float pulse = 4.0f + 1.5f * sinf(angle * 2.0f);
        if (!emit_triangle(backend, vp, &local_stats,
                           core_cx, core_cy - 12.0f,
                           core_cx - 12.0f, core_cy,
                           core_cx + 12.0f, core_cy,
                           0.3f, clr_core_glow) ||
            !emit_triangle(backend, vp, &local_stats,
                           core_cx - 12.0f, core_cy,
                           core_cx + 12.0f, core_cy,
                           core_cx, core_cy + 12.0f,
                           0.3f, clr_core_glow)) {
            goto finish;
        }
        if (queued_circle_count < 128u) {
            queued_circles[queued_circle_count++] = (CircleCmd){
                core_cx, core_cy, 0.4f, pulse, clr_belt_chev
            };
        }
    }

    /* Pass 2: all circular primitives batched together to minimize Citro2D state switches */
    for (uint32_t i = 0; i < queued_circle_count; ++i) {
        const CircleCmd *c = &queued_circles[i];
        if (!emit_circle(backend, vp, &local_stats, c->cx, c->cy, c->z, c->radius, c->color)) {
            break;
        }
    }

finish:
    if (out_stats) {
        *out_stats = local_stats;
    }
    return local_stats.budget_dropped == 0;
}
