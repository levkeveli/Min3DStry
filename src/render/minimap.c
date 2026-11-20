#include "render/minimap.h"

#include <stddef.h>

static float clamp_float(float value, float min_value, float max_value) {
    if (value < min_value) {
        return min_value;
    }
    if (value > max_value) {
        return max_value;
    }
    return value;
}

static M3dsColor tile_color(uint8_t kind) {
    switch (kind) {
    case M3DS_TILE_COPPER_ORE:
        return m3ds_rgba(205, 125, 45, 255);
    case M3DS_TILE_LEAD_ORE:
        return m3ds_rgba(145, 155, 170, 255);
    case M3DS_TILE_CONVEYOR_RIGHT:
    case M3DS_TILE_CONVEYOR_DOWN:
        return m3ds_rgba(95, 100, 105, 255);
    case M3DS_TILE_WALL:
        return m3ds_rgba(175, 172, 160, 255);
    case M3DS_TILE_CORE:
        return m3ds_rgba(70, 220, 220, 255);
    case M3DS_TILE_TURRET:
        return m3ds_rgba(225, 80, 70, 255);
    case M3DS_TILE_STONE:
    default:
        return m3ds_rgba(48, 52, 56, 255);
    }
}

bool m3ds_minimap_render(
    const M3dsBoundedScene *scene,
    const M3dsParityState *factory,
    const M3dsRenderBackend *backend,
    float x,
    float y,
    float width,
    float height) {
    if (!scene || !backend || !backend->draw_rect ||
        width <= 0.0f || height <= 0.0f) {
        return false;
    }

    const M3dsColor border = m3ds_rgba(12, 14, 16, 255);
    const M3dsColor background = m3ds_rgba(22, 26, 30, 255);
    const M3dsColor camera = m3ds_rgba(245, 245, 230, 255);

    if (!backend->draw_rect(
            backend->user_data, x - 2.0f, y - 2.0f, 0.80f,
            width + 4.0f, height + 4.0f, border) ||
        !backend->draw_rect(
            backend->user_data, x, y, 0.81f, width, height, background)) {
        return false;
    }

    const float cell_w = width / (float)M3DS_SCENE_GRID_W;
    const float cell_h = height / (float)M3DS_SCENE_GRID_H;
    for (int gy = 0; gy < M3DS_SCENE_GRID_H; ++gy) {
        for (int gx = 0; gx < M3DS_SCENE_GRID_W; ++gx) {
            M3dsColor color = tile_color(scene->tiles[gy][gx]);
            if (!backend->draw_rect(
                    backend->user_data,
                    x + (float)gx * cell_w,
                    y + (float)gy * cell_h,
                    0.82f,
                    cell_w,
                    cell_h,
                    color)) {
                return false;
            }
        }
    }

    const float world_w = (float)M3DS_SCENE_GRID_W * M3DS_TILE_SIZE_PX;
    const float world_h = (float)M3DS_SCENE_GRID_H * M3DS_TILE_SIZE_PX;
    float cam_x = x + (scene->camera_x / world_w) * width;
    float cam_y = y + (scene->camera_y / world_h) * height;
    float cam_w = (scene->viewport.width / world_w) * width;
    float cam_h = (scene->viewport.height / world_h) * height;
    cam_x = clamp_float(cam_x, x, x + width);
    cam_y = clamp_float(cam_y, y, y + height);
    cam_w = clamp_float(cam_w, 1.0f, x + width - cam_x);
    cam_h = clamp_float(cam_h, 1.0f, y + height - cam_y);

    if (backend->draw_line) {
        if (!backend->draw_line(
                backend->user_data, cam_x, cam_y, cam_x + cam_w, cam_y,
                1.0f, 0.90f, camera) ||
            !backend->draw_line(
                backend->user_data, cam_x + cam_w, cam_y, cam_x + cam_w,
                cam_y + cam_h, 1.0f, 0.90f, camera) ||
            !backend->draw_line(
                backend->user_data, cam_x + cam_w, cam_y + cam_h, cam_x,
                cam_y + cam_h, 1.0f, 0.90f, camera) ||
            !backend->draw_line(
                backend->user_data, cam_x, cam_y + cam_h, cam_x, cam_y,
                1.0f, 0.90f, camera)) {
            return false;
        }
    }

    if (factory) {
        const M3dsColor enemy = m3ds_rgba(255, 90, 70, 255);
        for (uint32_t i = 0u; i < M3DS_PARITY_MAX_ENEMIES; ++i) {
            const M3dsParityEnemy *unit = &factory->enemies[i];
            if (!unit->alive) {
                continue;
            }
            int32_t gx = unit->x / M3DS_PARITY_SUBPIXELS_PER_TILE;
            int32_t gy = unit->y / M3DS_PARITY_SUBPIXELS_PER_TILE;
            gx = (int32_t)clamp_float(
                (float)gx, 0.0f, (float)(M3DS_SCENE_GRID_W - 1));
            gy = (int32_t)clamp_float(
                (float)gy, 0.0f, (float)(M3DS_SCENE_GRID_H - 1));
            if (!backend->draw_rect(
                    backend->user_data,
                    x + (float)gx * cell_w + cell_w * 0.25f,
                    y + (float)gy * cell_h + cell_h * 0.25f,
                    0.88f,
                    cell_w * 0.5f,
                    cell_h * 0.5f,
                    enemy)) {
                return false;
            }
        }

        const M3dsColor bar_bg = m3ds_rgba(8, 10, 12, 255);
        const M3dsColor hp_color = m3ds_rgba(95, 225, 120, 255);
        const M3dsColor copper_color = m3ds_rgba(220, 150, 55, 255);
        float hp_ratio = clamp_float((float)factory->core_hp / 200.0f, 0.0f, 1.0f);
        float copper_ratio =
            clamp_float((float)factory->core_copper / 100.0f, 0.0f, 1.0f);
        if (!backend->draw_rect(
                backend->user_data, x, y + height - 7.0f, 0.88f, width, 6.0f,
                bar_bg) ||
            !backend->draw_rect(
                backend->user_data, x, y + height - 7.0f, 0.89f,
                width * hp_ratio, 3.0f, hp_color) ||
            !backend->draw_rect(
                backend->user_data, x, y + height - 3.0f, 0.89f,
                width * copper_ratio, 2.0f, copper_color)) {
            return false;
        }
    }

    return true;
}
