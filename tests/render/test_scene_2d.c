#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "render/scene_2d.h"
#include "render/factory_demo.h"

typedef enum MockCmdKind {
    CMD_CLEAR = 0,
    CMD_RECT,
    CMD_LINE,
    CMD_TRIANGLE,
    CMD_CIRCLE
} MockCmdKind;

typedef struct MockBackend {
    MockCmdKind history[M3DS_SCENE_MAX_PRIMITIVES + 8u];
    uint32_t count;
    uint32_t clears;
    bool seen_circle;
    bool non_circle_after_circle;
    unsigned sprites[M3DS_SPRITE_ITEM + 1];
    float sprite_w[M3DS_SPRITE_ITEM + 1];
    float sprite_h[M3DS_SPRITE_ITEM + 1];
} MockBackend;

static bool mock_clear(void *user_data, M3dsColor color) {
    (void)color;
    MockBackend *m = (MockBackend *)user_data;
    m->clears++;
    if (m->count < (sizeof(m->history) / sizeof(m->history[0]))) {
        m->history[m->count++] = CMD_CLEAR;
    }
    return true;
}

static bool mock_rect(
    void *user_data, float x, float y, float z, float w, float h, M3dsColor color) {
    (void)x; (void)y; (void)z; (void)w; (void)h; (void)color;
    MockBackend *m = (MockBackend *)user_data;
    if (m->seen_circle) {
        m->non_circle_after_circle = true;
    }
    if (m->count < (sizeof(m->history) / sizeof(m->history[0]))) {
        m->history[m->count++] = CMD_RECT;
    }
    return true;
}

static bool mock_line(
    void *user_data, float x0, float y0, float x1, float y1,
    float thickness, float z, M3dsColor color) {
    (void)x0; (void)y0; (void)x1; (void)y1; (void)thickness; (void)z; (void)color;
    MockBackend *m = (MockBackend *)user_data;
    if (m->seen_circle) {
        m->non_circle_after_circle = true;
    }
    if (m->count < (sizeof(m->history) / sizeof(m->history[0]))) {
        m->history[m->count++] = CMD_LINE;
    }
    return true;
}

static bool mock_triangle(
    void *user_data, float x0, float y0, float x1, float y1,
    float x2, float y2, float z, M3dsColor color) {
    (void)x0; (void)y0; (void)x1; (void)y1; (void)x2; (void)y2; (void)z; (void)color;
    MockBackend *m = (MockBackend *)user_data;
    if (m->seen_circle) {
        m->non_circle_after_circle = true;
    }
    if (m->count < (sizeof(m->history) / sizeof(m->history[0]))) {
        m->history[m->count++] = CMD_TRIANGLE;
    }
    return true;
}

static bool mock_circle(
    void *user_data, float cx, float cy, float z, float radius, M3dsColor color) {
    (void)cx; (void)cy; (void)z; (void)radius; (void)color;
    MockBackend *m = (MockBackend *)user_data;
    m->seen_circle = true;
    if (m->count < (sizeof(m->history) / sizeof(m->history[0]))) {
        m->history[m->count++] = CMD_CIRCLE;
    }
    return true;
}

static bool mock_sprite(
    void *user_data, M3dsSceneSprite sprite, float x, float y, float z,
    float width, float height) {
    MockBackend *mock = user_data;
    mock->sprites[sprite]++;
    mock->sprite_w[sprite] = width;
    mock->sprite_h[sprite] = height;
    return mock_rect(user_data, x, y, z, 0, 0, m3ds_rgba(0, 0, 0, 0));
}

static M3dsRenderBackend make_mock_backend(MockBackend *ctx) {
    memset(ctx, 0, sizeof(*ctx));
    M3dsRenderBackend b = {
        .user_data = ctx,
        .clear = mock_clear,
        .draw_rect = mock_rect,
        .draw_line = mock_line,
        .draw_triangle = mock_triangle,
        .draw_circle = mock_circle,
    };
    return b;
}

static void test_deterministic_step_and_bounds(void) {
    M3dsBoundedScene a;
    M3dsBoundedScene b;
    m3ds_scene_init(&a, M3DS_TOP_SCREEN_WIDTH, M3DS_TOP_SCREEN_HEIGHT);
    m3ds_scene_init(&b, M3DS_TOP_SCREEN_WIDTH, M3DS_TOP_SCREEN_HEIGHT);

    assert(a.tick == 0);
    assert(memcmp(&a, &b, sizeof(a)) == 0);

    m3ds_scene_step(&a, 25u);
    m3ds_scene_step(&b, 25u);
    assert(a.tick == 25u);
    assert(memcmp(&a, &b, sizeof(a)) == 0);

    /* Pausing freezes simulation tick */
    a.paused = true;
    m3ds_scene_step(&a, 10u);
    assert(a.tick == 25u);

    /* Camera clamping stays within world bounds */
    float max_x = (float)M3DS_SCENE_GRID_W * M3DS_TILE_SIZE_PX - M3DS_TOP_SCREEN_WIDTH;
    float max_y = (float)M3DS_SCENE_GRID_H * M3DS_TILE_SIZE_PX - M3DS_TOP_SCREEN_HEIGHT;
    m3ds_scene_pan(&a, -9999.0f, -9999.0f);
    assert(a.camera_x == 0.0f);
    assert(a.camera_y == 0.0f);
    m3ds_scene_pan(&a, 9999.0f, 9999.0f);
    assert(a.camera_x == max_x);
    assert(a.camera_y == max_y);
}

static void test_culling_batching_and_budget(void) {
    M3dsBoundedScene scene;
    m3ds_scene_init(&scene, M3DS_TOP_SCREEN_WIDTH, M3DS_TOP_SCREEN_HEIGHT);
    m3ds_scene_step(&scene, 12u);

    MockBackend mock;
    M3dsRenderBackend backend = make_mock_backend(&mock);
    M3dsDrawStats stats;

    bool ok = m3ds_scene_render(&scene, &backend, &stats);
    assert(ok);
    assert(mock.clears == 1);
    assert(mock.history[0] == CMD_CLEAR);
    assert(stats.drawn_rects > 0);
    assert(stats.drawn_triangles > 0);
    assert(stats.drawn_lines > 0);
    assert(stats.drawn_circles > 0);
    assert(stats.culled_primitives > 0);
    assert(stats.budget_dropped == 0);

    /* Citro2D batching invariant: all non-circular draws occur before circles */
    assert(!mock.non_circle_after_circle);
    assert(stats.state_switches <= 1u);

    uint32_t total = stats.drawn_rects + stats.drawn_lines +
                     stats.drawn_triangles + stats.drawn_circles;
    assert(total <= M3DS_SCENE_MAX_PRIMITIVES);
    assert(mock.count == total + 1u);
}

static void test_invalid_inputs_fail_safely(void) {
    M3dsBoundedScene scene;
    m3ds_scene_init(&scene, -10.0f, 0.0f);
    assert(scene.viewport.width == M3DS_TOP_SCREEN_WIDTH);
    assert(scene.viewport.height == M3DS_TOP_SCREEN_HEIGHT);

    MockBackend mock;
    M3dsRenderBackend backend = make_mock_backend(&mock);
    M3dsDrawStats stats;

    assert(!m3ds_scene_render(NULL, &backend, &stats));
    assert(!m3ds_scene_render(&scene, NULL, &stats));

    scene.viewport.width = 0.0f;
    assert(!m3ds_scene_render(&scene, &backend, &stats));
}

static void test_live_factory_delivery(void) {
    M3dsBoundedScene scene;
    M3dsParityState factory;
    m3ds_factory_demo_init(&scene, &factory);
    assert(scene.show_sim_items && factory.core_copper == 0u);
    for (unsigned tick = 0; tick < 15u; ++tick) {
        m3ds_factory_demo_step(&scene, &factory);
    }
    assert(factory.items_mined == 1u && factory.items_delivered == 0u);
    assert(scene.belt_items[11][11] > 0u);
    MockBackend mock;
    M3dsRenderBackend backend = make_mock_backend(&mock);
    backend.draw_sprite = mock_sprite;
    M3dsDrawStats stats;
    assert(m3ds_scene_render(&scene, &backend, &stats));
    assert(mock.sprites[M3DS_SPRITE_FLOOR] > 0u);
    assert(mock.sprites[M3DS_SPRITE_OVERLAY] == 1u);
    assert(mock.sprites[M3DS_SPRITE_STRIP_1] == 7u);
    assert(mock.sprites[M3DS_SPRITE_ITEM] == 1u);
    assert(!mock.non_circle_after_circle && stats.budget_dropped == 0u);
    m3ds_factory_demo_step(&scene, &factory);
    backend = make_mock_backend(&mock);
    backend.draw_sprite = mock_sprite;
    assert(m3ds_scene_render(&scene, &backend, &stats));
    assert(mock.sprites[M3DS_SPRITE_STRIP_0] == 7u);

    scene.paused = true;
    uint32_t paused_hash = m3ds_parity_hash_state(&factory);
    m3ds_factory_demo_step(&scene, &factory);
    assert(scene.tick == 16u && paused_hash == m3ds_parity_hash_state(&factory));
    scene.paused = false;
    while (scene.tick < 60u) {
        m3ds_factory_demo_step(&scene, &factory);
    }
    assert(factory.items_delivered == 2u && factory.core_copper == 2u);
    assert(m3ds_parity_hash_state(&factory) == 0x32542fb8u);
    printf("live factory: tick=60 delivered=2 inventory=2 hash=0x%08lx\n",
           (unsigned long)m3ds_parity_hash_state(&factory));
}

static void test_zoom_bounds_and_render(void) {
    M3dsBoundedScene scene;
    m3ds_scene_init(&scene, M3DS_TOP_SCREEN_WIDTH, M3DS_TOP_SCREEN_HEIGHT);
    assert(m3ds_scene_zoom(&scene) == 1.0f);
    assert(m3ds_scene_zoom(NULL) == 1.0f);

    m3ds_scene_set_zoom(&scene, 99.0f);
    assert(m3ds_scene_zoom(&scene) == M3DS_SCENE_MAX_ZOOM);
    m3ds_scene_set_zoom(&scene, -1.0f);
    assert(m3ds_scene_zoom(&scene) == M3DS_SCENE_MIN_ZOOM);
    m3ds_scene_set_zoom(NULL, 2.0f);

    m3ds_scene_set_zoom(&scene, 2.0f);
    m3ds_scene_reset_camera(&scene);
    m3ds_scene_pan(&scene, 9999.0f, 9999.0f);
    float max_x = (float)M3DS_SCENE_GRID_W * M3DS_TILE_SIZE_PX * 2.0f -
                  M3DS_TOP_SCREEN_WIDTH;
    float max_y = (float)M3DS_SCENE_GRID_H * M3DS_TILE_SIZE_PX * 2.0f -
                  M3DS_TOP_SCREEN_HEIGHT;
    assert(scene.camera_x == max_x && scene.camera_y == max_y);

    scene.show_sim_items = true;
    MockBackend mock;
    M3dsRenderBackend backend = make_mock_backend(&mock);
    backend.draw_sprite = mock_sprite;
    M3dsDrawStats stats;
    assert(m3ds_scene_render(&scene, &backend, &stats));
    assert(mock.sprites[M3DS_SPRITE_FLOOR] > 0u);
    assert(mock.sprite_w[M3DS_SPRITE_FLOOR] == 32.0f);
    assert(mock.sprite_h[M3DS_SPRITE_FLOOR] == 32.0f);

    m3ds_scene_set_zoom(&scene, 0.5f);
    m3ds_scene_reset_camera(&scene);
    assert(m3ds_scene_render(&scene, &backend, &stats));
    assert(mock.sprite_w[M3DS_SPRITE_FLOOR] == 8.0f);
    assert(mock.sprite_h[M3DS_SPRITE_FLOOR] == 8.0f);

    m3ds_scene_zoom_by(&scene, 10.0f);
    assert(m3ds_scene_zoom(&scene) == M3DS_SCENE_MAX_ZOOM);
}

static void test_demo_turret_fixture(void) {
    M3dsBoundedScene scene;
    M3dsParityState factory;
    m3ds_factory_demo_init(&scene, &factory);
    m3ds_factory_demo_add_turret(&scene, &factory);
    assert(factory.grid[6][9].block == M3DS_PBLOCK_TURRET);
    assert(factory.grid[6][9].ammo == M3DS_PARITY_TURRET_MAX_AMMO);
    assert(scene.tiles[12][15] == M3DS_TILE_TURRET);
    assert(m3ds_parity_hash_state(&factory) != 0u);
}

int main(void) {
    test_deterministic_step_and_bounds();
    test_culling_batching_and_budget();
    test_invalid_inputs_fail_safely();
    test_zoom_bounds_and_render();
    test_live_factory_delivery();
    test_demo_turret_fixture();
    printf("test_scene_2d: all assertions passed.\n");
    return 0;
}
