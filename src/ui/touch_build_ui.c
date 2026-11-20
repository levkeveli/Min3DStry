#include "ui/touch_build_ui.h"

#include <stdio.h>
#include <string.h>

#include "render/factory_demo.h"

typedef struct UiGlyph {
    char ch;
    uint8_t rows[7];
} UiGlyph;

static const UiGlyph UI_FONT_GLYPHS[] = {
    {' ', {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {':', {0x00, 0x04, 0x04, 0x00, 0x04, 0x04, 0x00}},
    {',', {0x00, 0x00, 0x00, 0x00, 0x04, 0x04, 0x08}},
    {'(', {0x02, 0x04, 0x08, 0x08, 0x08, 0x04, 0x02}},
    {')', {0x08, 0x04, 0x02, 0x02, 0x02, 0x04, 0x08}},
    {'/', {0x01, 0x02, 0x02, 0x04, 0x08, 0x08, 0x10}},
    {'-', {0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00}},
    {'0', {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E}},
    {'1', {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E}},
    {'2', {0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F}},
    {'3', {0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E}},
    {'4', {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02}},
    {'5', {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E}},
    {'6', {0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E}},
    {'7', {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08}},
    {'8', {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E}},
    {'9', {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C}},
    {'A', {0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}},
    {'B', {0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E}},
    {'C', {0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E}},
    {'D', {0x1E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1E}},
    {'E', {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F}},
    {'F', {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10}},
    {'G', {0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F}},
    {'H', {0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}},
    {'I', {0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E}},
    {'K', {0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11}},
    {'L', {0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F}},
    {'M', {0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11}},
    {'N', {0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11}},
    {'O', {0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}},
    {'P', {0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10}},
    {'R', {0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11}},
    {'S', {0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E}},
    {'T', {0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04}},
    {'U', {0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}},
    {'V', {0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04}},
    {'W', {0x11, 0x11, 0x11, 0x15, 0x15, 0x1B, 0x11}},
    {'X', {0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11}},
    {'Y', {0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04}},
};

static const M3dsUiRect UI_BUTTON_RECTS[M3DS_UI_BUTTON_COUNT] = {
    {8.0f,   36.0f, 72.0f, 72.0f},  /* BLOCK_CONVEYOR */
    {85.0f,  36.0f, 72.0f, 72.0f},  /* BLOCK_DRILL */
    {162.0f, 36.0f, 72.0f, 72.0f},  /* BLOCK_WALL */
    {239.0f, 36.0f, 72.0f, 72.0f},  /* BLOCK_TURRET */
    {8.0f,   116.0f, 96.0f, 56.0f}, /* ROTATE_CW */
    {112.0f, 116.0f, 96.0f, 56.0f}, /* TOGGLE_DECONSTRUCT */
    {216.0f, 116.0f, 96.0f, 56.0f}, /* APPLY_ACTION */
    {8.0f,   180.0f, 96.0f, 36.0f}, /* TOGGLE_PAUSE */
    {112.0f, 180.0f, 96.0f, 36.0f}, /* TOGGLE_MUTE */
    {216.0f, 180.0f, 96.0f, 36.0f}, /* RESET_CAMERA */
};

static const uint8_t *find_glyph_rows(char ch) {
    char c = ch;
    if (c >= 'a' && c <= 'z') {
        c = (char)(c - ('a' - 'A'));
    }
    for (size_t i = 0; i < sizeof(UI_FONT_GLYPHS) / sizeof(UI_FONT_GLYPHS[0]); ++i) {
        if (UI_FONT_GLYPHS[i].ch == c) {
            return UI_FONT_GLYPHS[i].rows;
        }
    }
    return UI_FONT_GLYPHS[0].rows;
}

static uint32_t ui_total_drawn(const M3dsDrawStats *stats) {
    return stats->drawn_rects + stats->drawn_lines +
           stats->drawn_triangles + stats->drawn_circles;
}

static bool ui_emit_rect(
    const M3dsRenderBackend *backend,
    M3dsDrawStats *stats,
    float x, float y, float z, float w, float h, M3dsColor color) {
    if (w <= 0.0f || h <= 0.0f) {
        return true;
    }
    if (ui_total_drawn(stats) >= M3DS_UI_MAX_PRIMITIVES) {
        stats->budget_dropped++;
        return false;
    }
    if (!backend->draw_rect(backend->user_data, x, y, z, w, h, color)) {
        return false;
    }
    stats->drawn_rects++;
    return true;
}

static bool ui_emit_triangle(
    const M3dsRenderBackend *backend,
    M3dsDrawStats *stats,
    float x0, float y0, float x1, float y1, float x2, float y2,
    float z, M3dsColor color) {
    if (ui_total_drawn(stats) >= M3DS_UI_MAX_PRIMITIVES) {
        stats->budget_dropped++;
        return false;
    }
    if (!backend->draw_triangle(
            backend->user_data, x0, y0, x1, y1, x2, y2, z, color)) {
        return false;
    }
    stats->drawn_triangles++;
    return true;
}

static float ui_text_width(const char *text, int scale) {
    if (!text || text[0] == '\0' || scale <= 0) {
        return 0.0f;
    }
    size_t len = strlen(text);
    return (float)(len * 6u - 1u) * (float)scale;
}

static bool ui_draw_text(
    const M3dsRenderBackend *backend,
    M3dsDrawStats *stats,
    float x,
    float y,
    float z,
    int scale,
    M3dsColor color,
    const char *text) {
    if (!text || scale <= 0) {
        return true;
    }
    float cursor_x = x;
    float px = (float)scale;
    for (size_t i = 0; text[i] != '\0'; ++i) {
        const uint8_t *rows = find_glyph_rows(text[i]);
        for (int r = 0; r < 7; ++r) {
            uint8_t bits = rows[r];
            int c = 0;
            while (c < 5) {
                if ((bits & (1u << (4 - c))) == 0u) {
                    c++;
                    continue;
                }
                int start = c;
                while (c < 5 && (bits & (1u << (4 - c))) != 0u) {
                    c++;
                }
                if (!ui_emit_rect(
                        backend,
                        stats,
                        cursor_x + (float)start * px,
                        y + (float)r * px,
                        z,
                        (float)(c - start) * px,
                        px,
                        color)) {
                    return false;
                }
            }
        }
        cursor_x += 6.0f * px;
    }
    return true;
}

static bool ui_draw_text_centered(
    const M3dsRenderBackend *backend,
    M3dsDrawStats *stats,
    const M3dsUiRect *box,
    float y,
    float z,
    int scale,
    M3dsColor color,
    const char *text) {
    float tw = ui_text_width(text, scale);
    float x = box->x + (box->w - tw) * 0.5f;
    return ui_draw_text(backend, stats, x, y, z, scale, color, text);
}

static bool ui_draw_panel_box(
    const M3dsRenderBackend *backend,
    M3dsDrawStats *stats,
    const M3dsUiRect *rect,
    float border_px,
    M3dsColor border_color,
    M3dsColor fill_color) {
    if (!ui_emit_rect(backend, stats, rect->x, rect->y, 0.1f,
                      rect->w, rect->h, border_color)) {
        return false;
    }
    return ui_emit_rect(
        backend,
        stats,
        rect->x + border_px,
        rect->y + border_px,
        0.15f,
        rect->w - border_px * 2.0f,
        rect->h - border_px * 2.0f,
        fill_color);
}

static bool ui_draw_rot_arrow(
    const M3dsRenderBackend *backend,
    M3dsDrawStats *stats,
    float cx,
    float cy,
    float half_size,
    uint8_t rot,
    M3dsColor color) {
    float x0 = cx;
    float y0 = cy;
    float x1 = cx;
    float y1 = cy;
    float x2 = cx;
    float y2 = cy;
    switch (rot & 3u) {
        case 0u: /* Right */
            x0 = cx + half_size; y0 = cy;
            x1 = cx - half_size; y1 = cy - half_size * 0.8f;
            x2 = cx - half_size; y2 = cy + half_size * 0.8f;
            break;
        case 1u: /* Down */
            x0 = cx; y0 = cy + half_size;
            x1 = cx - half_size * 0.8f; y1 = cy - half_size;
            x2 = cx + half_size * 0.8f; y2 = cy - half_size;
            break;
        case 2u: /* Left */
            x0 = cx - half_size; y0 = cy;
            x1 = cx + half_size; y1 = cy - half_size * 0.8f;
            x2 = cx + half_size; y2 = cy + half_size * 0.8f;
            break;
        default: /* Up */
            x0 = cx; y0 = cy - half_size;
            x1 = cx - half_size * 0.8f; y1 = cy + half_size;
            x2 = cx + half_size * 0.8f; y2 = cy + half_size;
            break;
    }
    return ui_emit_triangle(backend, stats, x0, y0, x1, y1, x2, y2, 0.25f, color);
}

static uint8_t ui_button_to_block(M3dsTouchButtonId id) {
    switch (id) {
        case M3DS_TOUCH_BTN_BLOCK_CONVEYOR:
            return M3DS_PBLOCK_CONVEYOR;
        case M3DS_TOUCH_BTN_BLOCK_DRILL:
            return M3DS_PBLOCK_DRILL;
        case M3DS_TOUCH_BTN_BLOCK_WALL:
            return M3DS_PBLOCK_WALL;
        case M3DS_TOUCH_BTN_BLOCK_TURRET:
            return M3DS_PBLOCK_TURRET;
        default:
            return M3DS_PBLOCK_EMPTY;
    }
}

static uint32_t ui_block_cost(uint8_t block) {
    switch (block) {
        case M3DS_PBLOCK_CONVEYOR:
            return 1u;
        case M3DS_PBLOCK_WALL:
            return 2u;
        case M3DS_PBLOCK_DRILL:
            return 4u;
        case M3DS_PBLOCK_TURRET:
            return 6u;
        default:
            return 0u;
    }
}

static const char *ui_block_short_label(uint8_t block) {
    switch (block) {
        case M3DS_PBLOCK_CONVEYOR:
            return "CONV";
        case M3DS_PBLOCK_DRILL:
            return "DRIL";
        case M3DS_PBLOCK_WALL:
            return "WALL";
        case M3DS_PBLOCK_TURRET:
            return "TURR";
        default:
            return "NONE";
    }
}

static const char *ui_rot_letter(uint8_t rot) {
    switch (rot & 3u) {
        case 0u:
            return "R";
        case 1u:
            return "D";
        case 2u:
            return "L";
        default:
            return "U";
    }
}

static bool ui_draw_palette_icon(
    const M3dsRenderBackend *backend,
    M3dsDrawStats *stats,
    uint8_t block,
    uint8_t rot,
    float cx,
    float cy) {
    M3dsColor clr_gold = m3ds_rgba(0xF5, 0xB8, 0x41, 0xFF);
    M3dsColor clr_copper = m3ds_rgba(0xD9, 0x9D, 0x73, 0xFF);
    M3dsColor clr_copper_hi = m3ds_rgba(0xE8, 0xB8, 0x90, 0xFF);
    M3dsColor clr_slate = m3ds_rgba(0x52, 0x5D, 0x73, 0xFF);
    M3dsColor clr_bright = m3ds_rgba(0xE6, 0xED, 0xF3, 0xFF);
    M3dsColor clr_dark = m3ds_rgba(0x22, 0x27, 0x33, 0xFF);

    switch (block) {
        case M3DS_PBLOCK_CONVEYOR:
            return ui_emit_rect(backend, stats, cx - 10.0f, cy - 8.0f, 0.2f,
                                20.0f, 16.0f, clr_slate) &&
                   ui_draw_rot_arrow(backend, stats, cx, cy, 5.0f, rot, clr_gold);
        case M3DS_PBLOCK_DRILL:
            return ui_emit_rect(backend, stats, cx - 10.0f, cy - 10.0f, 0.2f,
                                20.0f, 20.0f, clr_copper) &&
                   ui_emit_rect(backend, stats, cx - 6.0f, cy - 6.0f, 0.25f,
                                12.0f, 12.0f, clr_dark) &&
                   ui_emit_rect(backend, stats, cx - 3.0f, cy - 3.0f, 0.28f,
                                6.0f, 6.0f, clr_gold);
        case M3DS_PBLOCK_WALL:
            return ui_emit_rect(backend, stats, cx - 9.0f, cy - 9.0f, 0.2f,
                                18.0f, 18.0f, clr_copper) &&
                   ui_emit_rect(backend, stats, cx - 5.0f, cy - 5.0f, 0.25f,
                                10.0f, 10.0f, clr_copper_hi);
        case M3DS_PBLOCK_TURRET:
        default:
            return ui_emit_rect(backend, stats, cx - 9.0f, cy - 8.0f, 0.2f,
                                18.0f, 18.0f, clr_slate) &&
                   ui_emit_rect(backend, stats, cx - 4.0f, cy - 12.0f, 0.25f,
                                3.0f, 10.0f, clr_bright) &&
                   ui_emit_rect(backend, stats, cx + 1.0f, cy - 12.0f, 0.25f,
                                3.0f, 10.0f, clr_bright);
    }
}

void m3ds_touch_ui_init(M3dsTouchBuildUi *ui) {
    if (!ui) {
        return;
    }
    memset(ui, 0, sizeof(*ui));
    ui->selected_block = M3DS_PBLOCK_CONVEYOR;
    ui->rotation = 0u;
    ui->deconstruct_mode = false;
    ui->cursor_x = 8u;
    ui->cursor_y = 4u;
    ui->pressed_button = M3DS_TOUCH_BTN_NONE;
}

bool m3ds_touch_ui_button_rect(M3dsTouchButtonId id, M3dsUiRect *out_rect) {
    if (!out_rect || id < 0 || id >= (int)M3DS_UI_BUTTON_COUNT) {
        return false;
    }
    *out_rect = UI_BUTTON_RECTS[id];
    return true;
}

M3dsTouchButtonId m3ds_touch_ui_hit_test(uint16_t px, uint16_t py) {
    float x = (float)px;
    float y = (float)py;
    for (int i = 0; i < (int)M3DS_UI_BUTTON_COUNT; ++i) {
        const M3dsUiRect *r = &UI_BUTTON_RECTS[i];
        if (x >= r->x && x < (r->x + r->w) && y >= r->y && y < (r->y + r->h)) {
            return (M3dsTouchButtonId)i;
        }
    }
    return M3DS_TOUCH_BTN_NONE;
}

void m3ds_touch_ui_select_block(M3dsTouchBuildUi *ui, uint8_t block) {
    if (!ui) {
        return;
    }
    if (block == M3DS_PBLOCK_CONVEYOR ||
        block == M3DS_PBLOCK_DRILL ||
        block == M3DS_PBLOCK_WALL ||
        block == M3DS_PBLOCK_TURRET) {
        ui->selected_block = block;
        ui->deconstruct_mode = false;
    }
}

void m3ds_touch_ui_rotate(M3dsTouchBuildUi *ui, int8_t delta) {
    if (!ui) {
        return;
    }
    int r = ((int)ui->rotation + (int)delta) % 4;
    if (r < 0) {
        r += 4;
    }
    ui->rotation = (uint8_t)r;
}

void m3ds_touch_ui_toggle_deconstruct(M3dsTouchBuildUi *ui) {
    if (!ui) {
        return;
    }
    ui->deconstruct_mode = !ui->deconstruct_mode;
}

void m3ds_touch_ui_move_cursor(M3dsTouchBuildUi *ui, int8_t dx, int8_t dy) {
    if (!ui) {
        return;
    }
    int nx = (int)ui->cursor_x + (int)dx;
    int ny = (int)ui->cursor_y + (int)dy;
    if (nx < 0) {
        nx = 0;
    } else if (nx >= (int)M3DS_PARITY_GRID_W) {
        nx = (int)M3DS_PARITY_GRID_W - 1;
    }
    if (ny < 0) {
        ny = 0;
    } else if (ny >= (int)M3DS_PARITY_GRID_H) {
        ny = (int)M3DS_PARITY_GRID_H - 1;
    }
    ui->cursor_x = (uint8_t)nx;
    ui->cursor_y = (uint8_t)ny;
}

bool m3ds_touch_ui_can_apply(
    const M3dsTouchBuildUi *ui,
    const M3dsParityState *factory) {
    if (!ui || !factory ||
        ui->cursor_x >= M3DS_PARITY_GRID_W ||
        ui->cursor_y >= M3DS_PARITY_GRID_H) {
        return false;
    }

    const M3dsParityCell *cell = &factory->grid[ui->cursor_y][ui->cursor_x];
    if (ui->deconstruct_mode) {
        return cell->block != M3DS_PBLOCK_EMPTY && cell->block != M3DS_PBLOCK_CORE;
    }

    uint32_t cost = ui_block_cost(ui->selected_block);
    if (cost == 0u || cell->block != M3DS_PBLOCK_EMPTY) {
        return false;
    }
    if (ui->selected_block == M3DS_PBLOCK_DRILL && cell->ore == M3DS_PORE_NONE) {
        return false;
    }
    return factory->core_copper >= cost;
}

M3dsTouchAction m3ds_touch_ui_handle_touch(
    M3dsTouchBuildUi *ui,
    const M3dsTouchInput *touch) {
    M3dsTouchAction action;
    memset(&action, 0, sizeof(action));
    action.button = M3DS_TOUCH_BTN_NONE;

    if (!ui || !touch) {
        return action;
    }

    if (!touch->pressed) {
        ui->was_touch_down = false;
        ui->pressed_button = M3DS_TOUCH_BTN_NONE;
        action.block = ui->selected_block;
        action.rot = ui->rotation;
        action.deconstruct_mode = ui->deconstruct_mode;
        return action;
    }

    M3dsTouchButtonId hit = m3ds_touch_ui_hit_test(touch->px, touch->py);
    ui->pressed_button = hit;
    bool rising_edge = !ui->was_touch_down;
    ui->was_touch_down = true;

    if (rising_edge && hit != M3DS_TOUCH_BTN_NONE) {
        action.button = hit;
        switch (hit) {
            case M3DS_TOUCH_BTN_BLOCK_CONVEYOR:
            case M3DS_TOUCH_BTN_BLOCK_DRILL:
            case M3DS_TOUCH_BTN_BLOCK_WALL:
            case M3DS_TOUCH_BTN_BLOCK_TURRET:
                m3ds_touch_ui_select_block(ui, ui_button_to_block(hit));
                action.kind = M3DS_TOUCH_ACTION_SELECT_BLOCK;
                break;
            case M3DS_TOUCH_BTN_ROTATE_CW:
                m3ds_touch_ui_rotate(ui, 1);
                action.kind = M3DS_TOUCH_ACTION_ROTATE;
                break;
            case M3DS_TOUCH_BTN_TOGGLE_DECONSTRUCT:
                m3ds_touch_ui_toggle_deconstruct(ui);
                action.kind = M3DS_TOUCH_ACTION_TOGGLE_DECONSTRUCT;
                break;
            case M3DS_TOUCH_BTN_APPLY_ACTION:
                action.kind = ui->deconstruct_mode
                    ? M3DS_TOUCH_ACTION_REMOVE_BLOCK
                    : M3DS_TOUCH_ACTION_PLACE_BLOCK;
                break;
            case M3DS_TOUCH_BTN_TOGGLE_PAUSE:
                action.kind = M3DS_TOUCH_ACTION_TOGGLE_PAUSE;
                break;
            case M3DS_TOUCH_BTN_TOGGLE_MUTE:
                action.kind = M3DS_TOUCH_ACTION_TOGGLE_MUTE;
                break;
            case M3DS_TOUCH_BTN_RESET_CAMERA:
                action.kind = M3DS_TOUCH_ACTION_RESET_CAMERA;
                break;
            case M3DS_TOUCH_BTN_NONE:
            default:
                break;
        }
    }

    action.block = ui->selected_block;
    action.rot = ui->rotation;
    action.deconstruct_mode = ui->deconstruct_mode;
    return action;
}

bool m3ds_touch_ui_apply_to_factory(
    M3dsTouchBuildUi *ui,
    M3dsParityState *factory,
    M3dsBoundedScene *scene) {
    if (!ui || !factory) {
        return false;
    }

    M3dsParityInput input;
    input.tick = factory->tick;
    input.kind = ui->deconstruct_mode
        ? M3DS_PINPUT_REMOVE_BLOCK
        : M3DS_PINPUT_PLACE_BLOCK;
    input.x = ui->cursor_x;
    input.y = ui->cursor_y;
    input.block = ui->deconstruct_mode ? M3DS_PBLOCK_EMPTY : ui->selected_block;
    input.rot = ui->rotation;

    bool ok = m3ds_parity_apply_input(factory, &input);
    if (ok) {
        ui->actions_applied += 1u;
        if (scene) {
            m3ds_factory_demo_sync_tile(scene, factory, ui->cursor_x, ui->cursor_y);
        }
    } else {
        ui->actions_rejected += 1u;
    }
    return ok;
}

void m3ds_touch_ui_sync_scene_cursor(
    const M3dsTouchBuildUi *ui,
    const M3dsParityState *factory,
    M3dsBoundedScene *scene) {
    if (!ui || !scene) {
        return;
    }
    scene->cursor_visible = true;
    scene->cursor_tile_x = (int)ui->cursor_x + (int)M3DS_FACTORY_DEMO_OFFSET_X;
    scene->cursor_tile_y = (int)ui->cursor_y + (int)M3DS_FACTORY_DEMO_OFFSET_Y;
    scene->cursor_rot = ui->rotation & 3u;
    scene->cursor_deconstruct = ui->deconstruct_mode;
    scene->cursor_valid = factory ? m3ds_touch_ui_can_apply(ui, factory) : false;
}

bool m3ds_touch_ui_render(
    const M3dsTouchBuildUi *ui,
    const M3dsTouchUiRenderContext *ctx,
    const M3dsRenderBackend *backend,
    M3dsDrawStats *out_stats) {
    M3dsDrawStats local_stats;
    memset(&local_stats, 0, sizeof(local_stats));

    if (!ui || !backend || !backend->clear || !backend->draw_rect || !backend->draw_triangle) {
        if (out_stats) {
            *out_stats = local_stats;
        }
        return false;
    }

    M3dsTouchUiRenderContext fallback_ctx;
    if (!ctx) {
        memset(&fallback_ctx, 0, sizeof(fallback_ctx));
        ctx = &fallback_ctx;
    }

    const M3dsColor clr_bg = m3ds_rgba(0x11, 0x15, 0x1D, 0xFF);
    const M3dsColor clr_header = m3ds_rgba(0x1B, 0x22, 0x2F, 0xFF);
    const M3dsColor clr_card = m3ds_rgba(0x1D, 0x24, 0x33, 0xFF);
    const M3dsColor clr_card_sel = m3ds_rgba(0x2B, 0x31, 0x42, 0xFF);
    const M3dsColor clr_card_press = m3ds_rgba(0x36, 0x3F, 0x54, 0xFF);
    const M3dsColor clr_border = m3ds_rgba(0x38, 0x42, 0x57, 0xFF);
    const M3dsColor clr_gold = m3ds_rgba(0xF5, 0xB8, 0x41, 0xFF);
    const M3dsColor clr_text = m3ds_rgba(0xE6, 0xED, 0xF3, 0xFF);
    const M3dsColor clr_muted = m3ds_rgba(0x91, 0x9C, 0xAE, 0xFF);
    const M3dsColor clr_emerald = m3ds_rgba(0x2F, 0x9E, 0x57, 0xFF);
    const M3dsColor clr_emerald_fill = m3ds_rgba(0x19, 0x42, 0x2A, 0xFF);
    const M3dsColor clr_crimson = m3ds_rgba(0xE5, 0x53, 0x53, 0xFF);
    const M3dsColor clr_crimson_fill = m3ds_rgba(0x47, 0x1D, 0x22, 0xFF);
    const M3dsColor clr_amber_fill = m3ds_rgba(0x3F, 0x31, 0x17, 0xFF);

    if (!backend->clear(backend->user_data, clr_bg)) {
        if (out_stats) {
            *out_stats = local_stats;
        }
        return false;
    }

    /* Top header strip */
    if (!ui_emit_rect(backend, &local_stats, 0.0f, 0.0f, 0.1f,
                      M3DS_BOTTOM_SCREEN_WIDTH, 28.0f, clr_header) ||
        !ui_emit_rect(backend, &local_stats, 0.0f, 27.0f, 0.15f,
                      M3DS_BOTTOM_SCREEN_WIDTH, 1.0f, clr_border)) {
        goto finish;
    }

    char header_left[] = "MIN3DSTRY";
    char header_right[32];
    snprintf(
        header_right,
        sizeof(header_right),
        "CU:%lu T:%lu",
        (unsigned long)ctx->core_copper,
        (unsigned long)ctx->tick);
    if (!ui_draw_text(backend, &local_stats, 8.0f, 7.0f, 0.3f, 2, clr_gold, header_left) ||
        !ui_draw_text(
            backend,
            &local_stats,
            M3DS_BOTTOM_SCREEN_WIDTH - 8.0f - ui_text_width(header_right, 2),
            7.0f,
            0.3f,
            2,
            clr_text,
            header_right)) {
        goto finish;
    }

    /* Row 1: Block palette cards */
    for (int i = 0; i < 4; ++i) {
        M3dsTouchButtonId id = (M3dsTouchButtonId)i;
        uint8_t blk = ui_button_to_block(id);
        const M3dsUiRect *r = &UI_BUTTON_RECTS[i];
        bool selected = !ui->deconstruct_mode && ui->selected_block == blk;
        bool pressed = ui->pressed_button == id;

        M3dsColor border = selected ? clr_gold : clr_border;
        M3dsColor fill = pressed ? clr_card_press : (selected ? clr_card_sel : clr_card);
        if (!ui_draw_panel_box(backend, &local_stats, r, selected ? 2.0f : 1.0f, border, fill)) {
            goto finish;
        }

        if (!ui_draw_palette_icon(
                backend,
                &local_stats,
                blk,
                ui->rotation,
                r->x + r->w * 0.5f,
                r->y + 18.0f)) {
            goto finish;
        }

        const char *label = ui_block_short_label(blk);
        char cost_text[16];
        snprintf(cost_text, sizeof(cost_text), "%luC", (unsigned long)ui_block_cost(blk));
        if (!ui_draw_text_centered(
                backend, &local_stats, r, r->y + 36.0f, 0.3f, 2,
                selected ? clr_gold : clr_text, label) ||
            !ui_draw_text_centered(
                backend, &local_stats, r, r->y + 56.0f, 0.3f, 1,
                clr_muted, cost_text)) {
            goto finish;
        }
    }

    /* Row 2: Rotate, Mode Toggle, Place/Remove */
    {
        const M3dsUiRect *r_rot = &UI_BUTTON_RECTS[M3DS_TOUCH_BTN_ROTATE_CW];
        bool p_rot = ui->pressed_button == M3DS_TOUCH_BTN_ROTATE_CW;
        char rot_label[12];
        snprintf(rot_label, sizeof(rot_label), "ROT:%s", ui_rot_letter(ui->rotation));
        if (!ui_draw_panel_box(backend, &local_stats, r_rot, 1.0f, clr_border,
                               p_rot ? clr_card_press : clr_card) ||
            !ui_draw_rot_arrow(backend, &local_stats, r_rot->x + r_rot->w * 0.5f,
                               r_rot->y + 15.0f, 7.0f, ui->rotation, clr_gold) ||
            !ui_draw_text_centered(backend, &local_stats, r_rot, r_rot->y + 30.0f,
                                   0.3f, 2, clr_text, rot_label)) {
            goto finish;
        }

        const M3dsUiRect *r_mode = &UI_BUTTON_RECTS[M3DS_TOUCH_BTN_TOGGLE_DECONSTRUCT];
        bool p_mode = ui->pressed_button == M3DS_TOUCH_BTN_TOGGLE_DECONSTRUCT;
        M3dsColor mode_border = ui->deconstruct_mode ? clr_crimson : clr_gold;
        M3dsColor mode_fill = p_mode
            ? clr_card_press
            : (ui->deconstruct_mode ? clr_crimson_fill : clr_amber_fill);
        if (!ui_draw_panel_box(backend, &local_stats, r_mode, 2.0f, mode_border, mode_fill) ||
            !ui_draw_text_centered(backend, &local_stats, r_mode, r_mode->y + 14.0f,
                                   0.3f, 2, ui->deconstruct_mode ? clr_crimson : clr_gold,
                                   ui->deconstruct_mode ? "DECON" : "BUILD") ||
            !ui_draw_text_centered(backend, &local_stats, r_mode, r_mode->y + 36.0f,
                                   0.3f, 1, clr_muted, "MODE")) {
            goto finish;
        }

        const M3dsUiRect *r_act = &UI_BUTTON_RECTS[M3DS_TOUCH_BTN_APPLY_ACTION];
        bool p_act = ui->pressed_button == M3DS_TOUCH_BTN_APPLY_ACTION;
        M3dsColor act_border = clr_border;
        M3dsColor act_fill = clr_card;
        M3dsColor act_txt = clr_muted;
        if (ctx->action_valid) {
            act_border = ui->deconstruct_mode ? clr_crimson : clr_emerald;
            act_fill = ui->deconstruct_mode ? clr_crimson_fill : clr_emerald_fill;
            act_txt = clr_text;
        }
        if (p_act) {
            act_fill = clr_card_press;
        }
        if (!ui_draw_panel_box(backend, &local_stats, r_act, 2.0f, act_border, act_fill) ||
            !ui_draw_text_centered(backend, &local_stats, r_act, r_act->y + 14.0f,
                                   0.3f, 2, act_txt,
                                   ui->deconstruct_mode ? "DEL" : "PLACE") ||
            !ui_draw_text_centered(backend, &local_stats, r_act, r_act->y + 36.0f,
                                   0.3f, 1, ctx->action_valid ? clr_text : clr_muted,
                                   ctx->action_valid ? "VALID" : "NO GO")) {
            goto finish;
        }
    }

    /* Row 3: Pause, Mute, Camera Reset */
    {
        const M3dsUiRect *r_pause = &UI_BUTTON_RECTS[M3DS_TOUCH_BTN_TOGGLE_PAUSE];
        bool p_pause = ui->pressed_button == M3DS_TOUCH_BTN_TOGGLE_PAUSE;
        if (!ui_draw_panel_box(
                backend, &local_stats, r_pause, 1.0f,
                ctx->paused ? clr_gold : clr_border,
                p_pause ? clr_card_press : (ctx->paused ? clr_amber_fill : clr_card)) ||
            !ui_draw_text_centered(
                backend, &local_stats, r_pause, r_pause->y + 11.0f, 0.3f, 2,
                ctx->paused ? clr_gold : clr_text, ctx->paused ? "PAUSED" : "RUN")) {
            goto finish;
        }

        const M3dsUiRect *r_mute = &UI_BUTTON_RECTS[M3DS_TOUCH_BTN_TOGGLE_MUTE];
        bool p_mute = ui->pressed_button == M3DS_TOUCH_BTN_TOGGLE_MUTE;
        if (!ui_draw_panel_box(
                backend, &local_stats, r_mute, 1.0f,
                ctx->muted ? clr_crimson : clr_border,
                p_mute ? clr_card_press : (ctx->muted ? clr_crimson_fill : clr_card)) ||
            !ui_draw_text_centered(
                backend, &local_stats, r_mute, r_mute->y + 11.0f, 0.3f, 2,
                ctx->muted ? clr_crimson : clr_text, ctx->muted ? "MUTED" : "SOUND")) {
            goto finish;
        }

        const M3dsUiRect *r_cam = &UI_BUTTON_RECTS[M3DS_TOUCH_BTN_RESET_CAMERA];
        bool p_cam = ui->pressed_button == M3DS_TOUCH_BTN_RESET_CAMERA;
        if (!ui_draw_panel_box(
                backend, &local_stats, r_cam, 1.0f, clr_border,
                p_cam ? clr_card_press : clr_card) ||
            !ui_draw_text_centered(
                backend, &local_stats, r_cam, r_cam->y + 6.0f, 0.3f, 2,
                clr_text, "CAM") ||
            !ui_draw_text_centered(
                backend, &local_stats, r_cam, r_cam->y + 23.0f, 0.3f, 1,
                clr_muted, "RESET")) {
            goto finish;
        }
    }

    /* Footer strip */
    {
        const char *hw = (ctx->hw_short && ctx->hw_short[0] != '\0') ? ctx->hw_short : "3DS";
        char footer[48];
        snprintf(
            footer,
            sizeof(footer),
            "CUR:(%02u,%02u) L/R:ROT B:ACT %s",
            (unsigned int)ui->cursor_x,
            (unsigned int)ui->cursor_y,
            hw);
        if (!ui_emit_rect(backend, &local_stats, 0.0f, 222.0f, 0.1f,
                          M3DS_BOTTOM_SCREEN_WIDTH, 18.0f, clr_header) ||
            !ui_draw_text(backend, &local_stats, 8.0f, 227.0f, 0.3f, 1,
                          clr_muted, footer)) {
            goto finish;
        }
    }

finish:
    if (out_stats) {
        *out_stats = local_stats;
    }
    return local_stats.budget_dropped == 0u;
}

size_t m3ds_touch_ui_format_diag(
    const M3dsTouchBuildUi *ui,
    char *out_buf,
    size_t out_buf_size) {
    if (!ui || !out_buf || out_buf_size == 0u) {
        return 0u;
    }
    int written = snprintf(
        out_buf,
        out_buf_size,
        "block=%s rot=%u mode=%s cursor=(%u,%u) buttons=%u",
        m3ds_touch_ui_block_name(ui->selected_block),
        (unsigned int)(ui->rotation & 3u),
        ui->deconstruct_mode ? "deconstruct" : "build",
        (unsigned int)ui->cursor_x,
        (unsigned int)ui->cursor_y,
        M3DS_UI_BUTTON_COUNT);
    if (written < 0) {
        out_buf[0] = '\0';
        return 0u;
    }
    return (size_t)written;
}

const char *m3ds_touch_ui_block_name(uint8_t block) {
    switch (block) {
        case M3DS_PBLOCK_CONVEYOR:
            return "conveyor";
        case M3DS_PBLOCK_DRILL:
            return "drill";
        case M3DS_PBLOCK_WALL:
            return "wall";
        case M3DS_PBLOCK_TURRET:
            return "turret";
        default:
            return "none";
    }
}
