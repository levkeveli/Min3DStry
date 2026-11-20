#ifndef MIN3DSTRY_UI_TOUCH_BUILD_UI_H
#define MIN3DSTRY_UI_TOUCH_BUILD_UI_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "render/scene_2d.h"
#include "sim/parity_scenario.h"

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_BOTTOM_SCREEN_WIDTH  320.0f
#define M3DS_BOTTOM_SCREEN_HEIGHT 240.0f
#define M3DS_UI_MAX_PRIMITIVES    1536u
#define M3DS_UI_BUTTON_COUNT      10u

typedef enum M3dsTouchButtonId {
    M3DS_TOUCH_BTN_NONE = -1,
    M3DS_TOUCH_BTN_BLOCK_CONVEYOR = 0,
    M3DS_TOUCH_BTN_BLOCK_DRILL = 1,
    M3DS_TOUCH_BTN_BLOCK_WALL = 2,
    M3DS_TOUCH_BTN_BLOCK_TURRET = 3,
    M3DS_TOUCH_BTN_ROTATE_CW = 4,
    M3DS_TOUCH_BTN_TOGGLE_DECONSTRUCT = 5,
    M3DS_TOUCH_BTN_APPLY_ACTION = 6,
    M3DS_TOUCH_BTN_TOGGLE_PAUSE = 7,
    M3DS_TOUCH_BTN_TOGGLE_MUTE = 8,
    M3DS_TOUCH_BTN_RESET_CAMERA = 9
} M3dsTouchButtonId;

typedef enum M3dsTouchActionKind {
    M3DS_TOUCH_ACTION_NONE = 0,
    M3DS_TOUCH_ACTION_SELECT_BLOCK = 1,
    M3DS_TOUCH_ACTION_ROTATE = 2,
    M3DS_TOUCH_ACTION_TOGGLE_DECONSTRUCT = 3,
    M3DS_TOUCH_ACTION_PLACE_BLOCK = 4,
    M3DS_TOUCH_ACTION_REMOVE_BLOCK = 5,
    M3DS_TOUCH_ACTION_TOGGLE_PAUSE = 6,
    M3DS_TOUCH_ACTION_TOGGLE_MUTE = 7,
    M3DS_TOUCH_ACTION_RESET_CAMERA = 8
} M3dsTouchActionKind;

typedef struct M3dsUiRect {
    float x;
    float y;
    float w;
    float h;
} M3dsUiRect;

typedef struct M3dsTouchInput {
    bool pressed;
    uint16_t px;
    uint16_t py;
} M3dsTouchInput;

typedef struct M3dsTouchAction {
    M3dsTouchActionKind kind;
    M3dsTouchButtonId button;
    uint8_t block;
    uint8_t rot;
    bool deconstruct_mode;
} M3dsTouchAction;

typedef struct M3dsTouchBuildUi {
    uint8_t selected_block;
    uint8_t rotation;
    bool deconstruct_mode;
    uint8_t cursor_x;
    uint8_t cursor_y;
    bool was_touch_down;
    M3dsTouchButtonId pressed_button;
    uint32_t actions_applied;
    uint32_t actions_rejected;
} M3dsTouchBuildUi;

typedef struct M3dsTouchUiRenderContext {
    uint32_t core_copper;
    uint32_t tick;
    bool paused;
    bool muted;
    bool action_valid;
    const char *hw_short;
} M3dsTouchUiRenderContext;

void m3ds_touch_ui_init(M3dsTouchBuildUi *ui);

bool m3ds_touch_ui_button_rect(M3dsTouchButtonId id, M3dsUiRect *out_rect);
M3dsTouchButtonId m3ds_touch_ui_hit_test(uint16_t px, uint16_t py);

void m3ds_touch_ui_select_block(M3dsTouchBuildUi *ui, uint8_t block);
void m3ds_touch_ui_rotate(M3dsTouchBuildUi *ui, int8_t delta);
void m3ds_touch_ui_toggle_deconstruct(M3dsTouchBuildUi *ui);
void m3ds_touch_ui_move_cursor(M3dsTouchBuildUi *ui, int8_t dx, int8_t dy);

bool m3ds_touch_ui_can_apply(
    const M3dsTouchBuildUi *ui,
    const M3dsParityState *factory);

M3dsTouchAction m3ds_touch_ui_handle_touch(
    M3dsTouchBuildUi *ui,
    const M3dsTouchInput *touch);

bool m3ds_touch_ui_apply_to_factory(
    M3dsTouchBuildUi *ui,
    M3dsParityState *factory,
    M3dsBoundedScene *scene);

void m3ds_touch_ui_sync_scene_cursor(
    const M3dsTouchBuildUi *ui,
    const M3dsParityState *factory,
    M3dsBoundedScene *scene);

bool m3ds_touch_ui_render(
    const M3dsTouchBuildUi *ui,
    const M3dsTouchUiRenderContext *ctx,
    const M3dsRenderBackend *backend,
    M3dsDrawStats *out_stats);

size_t m3ds_touch_ui_format_diag(
    const M3dsTouchBuildUi *ui,
    char *out_buf,
    size_t out_buf_size);

const char *m3ds_touch_ui_block_name(uint8_t block);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_UI_TOUCH_BUILD_UI_H */
