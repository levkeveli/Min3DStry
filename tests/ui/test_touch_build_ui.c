#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "render/factory_demo.h"
#include "render/scene_2d.h"
#include "sim/parity_scenario.h"
#include "ui/touch_build_ui.h"

typedef struct MockUiRenderBackend {
    uint32_t clears;
    bool seen_circle;
    bool non_circle_after_circle;
} MockUiRenderBackend;

static bool mock_clear(void *user_data, M3dsColor color) {
    (void)color;
    MockUiRenderBackend *m = (MockUiRenderBackend *)user_data;
    m->clears++;
    return true;
}

static bool mock_rect(
    void *user_data, float x, float y, float z, float w, float h, M3dsColor color) {
    (void)x; (void)y; (void)z; (void)w; (void)h; (void)color;
    MockUiRenderBackend *m = (MockUiRenderBackend *)user_data;
    if (m->seen_circle) {
        m->non_circle_after_circle = true;
    }
    return true;
}

static bool mock_line(
    void *user_data, float x0, float y0, float x1, float y1,
    float thickness, float z, M3dsColor color) {
    (void)x0; (void)y0; (void)x1; (void)y1; (void)thickness; (void)z; (void)color;
    MockUiRenderBackend *m = (MockUiRenderBackend *)user_data;
    if (m->seen_circle) {
        m->non_circle_after_circle = true;
    }
    return true;
}

static bool mock_triangle(
    void *user_data, float x0, float y0, float x1, float y1,
    float x2, float y2, float z, M3dsColor color) {
    (void)x0; (void)y0; (void)x1; (void)y1; (void)x2; (void)y2; (void)z; (void)color;
    MockUiRenderBackend *m = (MockUiRenderBackend *)user_data;
    if (m->seen_circle) {
        m->non_circle_after_circle = true;
    }
    return true;
}

static bool mock_circle(
    void *user_data, float cx, float cy, float z, float radius, M3dsColor color) {
    (void)cx; (void)cy; (void)z; (void)radius; (void)color;
    MockUiRenderBackend *m = (MockUiRenderBackend *)user_data;
    m->seen_circle = true;
    return true;
}

static M3dsRenderBackend make_mock_backend(MockUiRenderBackend *ctx) {
    memset(ctx, 0, sizeof(*ctx));
    M3dsRenderBackend b = {
        .user_data = ctx,
        .clear = mock_clear,
        .draw_rect = mock_rect,
        .draw_line = mock_line,
        .draw_triangle = mock_triangle,
        .draw_circle = mock_circle,
        .draw_sprite = NULL,
    };
    return b;
}

static bool rects_overlap(const M3dsUiRect *a, const M3dsUiRect *b) {
    if (a->x + a->w <= b->x || b->x + b->w <= a->x) {
        return false;
    }
    if (a->y + a->h <= b->y || b->y + b->h <= a->y) {
        return false;
    }
    return true;
}

static void test_layout_and_hit_testing(void) {
    M3dsUiRect rects[M3DS_UI_BUTTON_COUNT];
    for (int i = 0; i < (int)M3DS_UI_BUTTON_COUNT; ++i) {
        assert(m3ds_touch_ui_button_rect((M3dsTouchButtonId)i, &rects[i]));
        assert(rects[i].w > 0.0f && rects[i].h > 0.0f);
        assert(rects[i].x >= 0.0f && rects[i].x + rects[i].w <= M3DS_BOTTOM_SCREEN_WIDTH);
        assert(rects[i].y >= 0.0f && rects[i].y + rects[i].h <= M3DS_BOTTOM_SCREEN_HEIGHT);

        uint16_t cx = (uint16_t)(rects[i].x + rects[i].w * 0.5f);
        uint16_t cy = (uint16_t)(rects[i].y + rects[i].h * 0.5f);
        assert(m3ds_touch_ui_hit_test(cx, cy) == (M3dsTouchButtonId)i);

        uint16_t rx = (uint16_t)(rects[i].x + rects[i].w - 1.0f);
        uint16_t by = (uint16_t)(rects[i].y + rects[i].h - 1.0f);
        assert(m3ds_touch_ui_hit_test((uint16_t)rects[i].x, (uint16_t)rects[i].y) ==
               (M3dsTouchButtonId)i);
        assert(m3ds_touch_ui_hit_test(rx, by) == (M3dsTouchButtonId)i);
    }

    for (int i = 0; i < (int)M3DS_UI_BUTTON_COUNT; ++i) {
        for (int j = i + 1; j < (int)M3DS_UI_BUTTON_COUNT; ++j) {
            assert(!rects_overlap(&rects[i], &rects[j]));
        }
    }

    assert(!m3ds_touch_ui_button_rect(M3DS_TOUCH_BTN_NONE, &rects[0]));
    assert(!m3ds_touch_ui_button_rect((M3dsTouchButtonId)M3DS_UI_BUTTON_COUNT, &rects[0]));
    assert(m3ds_touch_ui_hit_test(2u, 2u) == M3DS_TOUCH_BTN_NONE);
    assert(m3ds_touch_ui_hit_test(82u, 50u) == M3DS_TOUCH_BTN_NONE);
    assert(m3ds_touch_ui_hit_test(400u, 100u) == M3DS_TOUCH_BTN_NONE);
    assert(m3ds_touch_ui_hit_test(160u, 239u) == M3DS_TOUCH_BTN_NONE);
}

static void test_touch_edges_rotation_and_cursor(void) {
    M3dsTouchBuildUi ui;
    m3ds_touch_ui_init(&ui);
    assert(ui.selected_block == M3DS_PBLOCK_CONVEYOR);
    assert(ui.rotation == 0u);
    assert(!ui.deconstruct_mode);
    assert(ui.cursor_x == 8u && ui.cursor_y == 4u);

    /* Tap rotate button and hold on subsequent frame */
    M3dsUiRect rot_rect;
    assert(m3ds_touch_ui_button_rect(M3DS_TOUCH_BTN_ROTATE_CW, &rot_rect));
    M3dsTouchInput press = {
        true, (uint16_t)(rot_rect.x + 10.0f), (uint16_t)(rot_rect.y + 10.0f)};
    M3dsTouchAction act = m3ds_touch_ui_handle_touch(&ui, &press);
    assert(act.kind == M3DS_TOUCH_ACTION_ROTATE);
    assert(ui.rotation == 1u);
    assert(ui.pressed_button == M3DS_TOUCH_BTN_ROTATE_CW);

    act = m3ds_touch_ui_handle_touch(&ui, &press);
    assert(act.kind == M3DS_TOUCH_ACTION_NONE);
    assert(ui.rotation == 1u);

    M3dsTouchInput release = {false, 0u, 0u};
    act = m3ds_touch_ui_handle_touch(&ui, &release);
    assert(act.kind == M3DS_TOUCH_ACTION_NONE);
    assert(ui.pressed_button == M3DS_TOUCH_BTN_NONE);

    /* Wrap rotation in both directions */
    m3ds_touch_ui_rotate(&ui, 1);
    m3ds_touch_ui_rotate(&ui, 1);
    m3ds_touch_ui_rotate(&ui, 1);
    assert(ui.rotation == 0u);
    m3ds_touch_ui_rotate(&ui, -1);
    assert(ui.rotation == 3u);

    /* Selecting a block exits deconstruct mode */
    m3ds_touch_ui_toggle_deconstruct(&ui);
    assert(ui.deconstruct_mode);
    M3dsUiRect wall_rect;
    assert(m3ds_touch_ui_button_rect(M3DS_TOUCH_BTN_BLOCK_WALL, &wall_rect));
    M3dsTouchInput wall_press = {
        true, (uint16_t)(wall_rect.x + 10.0f), (uint16_t)(wall_rect.y + 10.0f)};
    act = m3ds_touch_ui_handle_touch(&ui, &wall_press);
    assert(act.kind == M3DS_TOUCH_ACTION_SELECT_BLOCK);
    assert(ui.selected_block == M3DS_PBLOCK_WALL);
    assert(!ui.deconstruct_mode);

    /* Cursor clamps within 16x12 simulation grid */
    m3ds_touch_ui_move_cursor(&ui, -50, -50);
    assert(ui.cursor_x == 0u && ui.cursor_y == 0u);
    m3ds_touch_ui_move_cursor(&ui, 50, 50);
    assert(ui.cursor_x == M3DS_PARITY_GRID_W - 1u);
    assert(ui.cursor_y == M3DS_PARITY_GRID_H - 1u);
}

static void test_factory_placement_and_deconstruct(void) {
    M3dsBoundedScene scene;
    M3dsParityState factory;
    m3ds_factory_demo_init(&scene, &factory);
    factory.core_copper = 20u;

    M3dsTouchBuildUi ui;
    m3ds_touch_ui_init(&ui);

    /* Place left-facing conveyor at empty stone tile (8, 4) -> cost 1 copper */
    ui.cursor_x = 8u;
    ui.cursor_y = 4u;
    ui.rotation = 2u;
    m3ds_touch_ui_select_block(&ui, M3DS_PBLOCK_CONVEYOR);
    assert(m3ds_touch_ui_can_apply(&ui, &factory));
    assert(m3ds_touch_ui_apply_to_factory(&ui, &factory, &scene));
    assert(factory.core_copper == 19u);
    assert(factory.grid[4][8].block == M3DS_PBLOCK_CONVEYOR);
    assert(factory.grid[4][8].rot == 2u);
    assert(scene.tiles[4u + M3DS_FACTORY_DEMO_OFFSET_Y][8u + M3DS_FACTORY_DEMO_OFFSET_X] ==
           M3DS_TILE_CONVEYOR_LEFT);

    /* Drill rejected on stone (8, 3), accepted on copper ore (2, 5) */
    ui.cursor_x = 8u;
    ui.cursor_y = 3u;
    m3ds_touch_ui_select_block(&ui, M3DS_PBLOCK_DRILL);
    assert(!m3ds_touch_ui_can_apply(&ui, &factory));
    assert(!m3ds_touch_ui_apply_to_factory(&ui, &factory, &scene));
    assert(ui.actions_rejected == 1u);

    ui.cursor_x = 2u;
    ui.cursor_y = 5u;
    assert(m3ds_touch_ui_can_apply(&ui, &factory));
    assert(m3ds_touch_ui_apply_to_factory(&ui, &factory, &scene));
    assert(factory.core_copper == 15u);

    /* Deconstruct wall placed on empty stone: place (2c) then remove (+1c refund) */
    ui.cursor_x = 9u;
    ui.cursor_y = 3u;
    ui.rotation = 0u;
    m3ds_touch_ui_select_block(&ui, M3DS_PBLOCK_WALL);
    assert(m3ds_touch_ui_apply_to_factory(&ui, &factory, &scene));
    assert(factory.core_copper == 13u);
    assert(scene.tiles[3u + M3DS_FACTORY_DEMO_OFFSET_Y][9u + M3DS_FACTORY_DEMO_OFFSET_X] ==
           M3DS_TILE_WALL);

    m3ds_touch_ui_toggle_deconstruct(&ui);
    assert(m3ds_touch_ui_can_apply(&ui, &factory));
    assert(m3ds_touch_ui_apply_to_factory(&ui, &factory, &scene));
    assert(factory.core_copper == 14u);
    assert(factory.grid[3][9].block == M3DS_PBLOCK_EMPTY);
    assert(scene.tiles[3u + M3DS_FACTORY_DEMO_OFFSET_Y][9u + M3DS_FACTORY_DEMO_OFFSET_X] ==
           M3DS_TILE_STONE);

    /* Cannot deconstruct core */
    ui.cursor_x = factory.core_x;
    ui.cursor_y = factory.core_y;
    assert(!m3ds_touch_ui_can_apply(&ui, &factory));
    assert(!m3ds_touch_ui_apply_to_factory(&ui, &factory, &scene));
}

static void test_top_cursor_and_bottom_rendering(void) {
    M3dsBoundedScene scene;
    M3dsParityState factory;
    m3ds_factory_demo_init(&scene, &factory);
    factory.core_copper = 10u;

    M3dsTouchBuildUi ui;
    m3ds_touch_ui_init(&ui);
    m3ds_touch_ui_sync_scene_cursor(&ui, &factory, &scene);
    assert(scene.cursor_visible);
    assert(scene.cursor_valid);

    MockUiRenderBackend top_mock;
    M3dsRenderBackend top_backend = make_mock_backend(&top_mock);
    M3dsDrawStats top_stats;
    assert(m3ds_scene_render(&scene, &top_backend, &top_stats));
    assert(!top_mock.non_circle_after_circle);
    assert(top_stats.state_switches <= 1u);
    assert(top_stats.budget_dropped == 0u);
    assert(top_stats.drawn_triangles > 0u);

    /* Deconstruct cursor uses lines in Pass 1 before Pass 2 circles */
    m3ds_touch_ui_toggle_deconstruct(&ui);
    ui.cursor_x = 5u;
    ui.cursor_y = factory.core_y;
    m3ds_touch_ui_sync_scene_cursor(&ui, &factory, &scene);
    top_backend = make_mock_backend(&top_mock);
    assert(m3ds_scene_render(&scene, &top_backend, &top_stats));
    assert(!top_mock.non_circle_after_circle);
    assert(top_stats.state_switches <= 1u);
    assert(top_stats.budget_dropped == 0u);
    assert(top_stats.drawn_lines >= 2u);

    /* Bottom touch UI rendering */
    MockUiRenderBackend bot_mock;
    M3dsRenderBackend bot_backend = make_mock_backend(&bot_mock);
    M3dsTouchUiRenderContext ctx = {10u, 60u, false, false, true, "N3DS"};
    M3dsDrawStats bot_stats;
    assert(m3ds_touch_ui_render(&ui, &ctx, &bot_backend, &bot_stats));
    assert(bot_mock.clears == 1u);
    assert(bot_stats.drawn_rects > 100u);
    assert(bot_stats.drawn_triangles > 0u);
    assert(bot_stats.state_switches == 0u);
    assert(bot_stats.budget_dropped == 0u);

    char diag[160];
    size_t n = m3ds_touch_ui_format_diag(&ui, diag, sizeof(diag));
    assert(n > 0u);
    assert(strcmp(
        diag,
        "block=conveyor rot=0 mode=deconstruct cursor=(5,5) buttons=10") == 0);
}

int main(void) {
    test_layout_and_hit_testing();
    test_touch_edges_rotation_and_cursor();
    test_factory_placement_and_deconstruct();
    test_top_cursor_and_bottom_rendering();
    printf("test_touch_build_ui: all assertions passed.\n");
    return 0;
}
