#include "render/minimap.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct MockStats {
    int rects;
    int lines;
    int core_rects;
    int turret_rects;
    int ore_rects;
} MockStats;

static bool mock_draw_rect(
    void *raw,
    float x,
    float y,
    float z,
    float w,
    float h,
    M3dsColor color) {
    (void)x;
    (void)y;
    (void)z;
    (void)w;
    (void)h;
    MockStats *stats = (MockStats *)raw;
    stats->rects += 1;
    if (color.g > 200 && color.b > 200 && color.r < 100) {
        stats->core_rects += 1;
    } else if (color.r > 200 && color.g < 120) {
        stats->turret_rects += 1;
    } else if (color.r > 180 && color.g > 100 && color.g < 160) {
        stats->ore_rects += 1;
    }
    return true;
}

static bool mock_draw_line(
    void *raw,
    float x0,
    float y0,
    float x1,
    float y1,
    float thickness,
    float z,
    M3dsColor color) {
    (void)x0;
    (void)y0;
    (void)x1;
    (void)y1;
    (void)thickness;
    (void)z;
    (void)color;
    MockStats *stats = (MockStats *)raw;
    stats->lines += 1;
    return true;
}

static void test_render_grid_and_camera(void) {
    M3dsBoundedScene scene;
    memset(&scene, 0, sizeof(scene));
    scene.viewport.width = 160.0f;
    scene.viewport.height = 120.0f;
    scene.camera_x = 16.0f;
    scene.camera_y = 16.0f;
    scene.tiles[0][0] = M3DS_TILE_CORE;
    scene.tiles[0][1] = M3DS_TILE_TURRET;
    scene.tiles[1][0] = M3DS_TILE_COPPER_ORE;

    MockStats stats;
    memset(&stats, 0, sizeof(stats));
    M3dsRenderBackend backend;
    memset(&backend, 0, sizeof(backend));
    backend.user_data = &stats;
    backend.draw_rect = mock_draw_rect;
    backend.draw_line = mock_draw_line;

    assert(m3ds_minimap_render(&scene, &backend, 300.0f, 8.0f, 96.0f, 64.0f));
    assert(stats.rects == 2 + (int)(M3DS_SCENE_GRID_W * M3DS_SCENE_GRID_H));
    assert(stats.lines == 4);
    assert(stats.core_rects == 1);
    assert(stats.turret_rects == 1);
    assert(stats.ore_rects == 1);
}

static void test_invalid_arguments(void) {
    M3dsBoundedScene scene;
    memset(&scene, 0, sizeof(scene));
    M3dsRenderBackend backend;
    memset(&backend, 0, sizeof(backend));
    assert(!m3ds_minimap_render(NULL, &backend, 0.0f, 0.0f, 10.0f, 10.0f));
    assert(!m3ds_minimap_render(&scene, NULL, 0.0f, 0.0f, 10.0f, 10.0f));
    assert(!m3ds_minimap_render(&scene, &backend, 0.0f, 0.0f, 10.0f, 10.0f));
    backend.draw_rect = mock_draw_rect;
    assert(!m3ds_minimap_render(&scene, &backend, 0.0f, 0.0f, 0.0f, 10.0f));
}

int main(void) {
    test_render_grid_and_camera();
    test_invalid_arguments();
    puts("minimap tests passed");
    return 0;
}
