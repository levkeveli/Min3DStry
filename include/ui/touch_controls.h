#ifndef MIN3DSTRY_UI_TOUCH_CONTROLS_H
#define MIN3DSTRY_UI_TOUCH_CONTROLS_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_TOUCH_SCREEN_WIDTH  320.0f
#define M3DS_TOUCH_SCREEN_HEIGHT 240.0f
#define M3DS_TOUCH_MENU_FIRST_ROW 120.0f
#define M3DS_TOUCH_MENU_ROW_HEIGHT 8.0f

typedef enum {
    M3DS_TOUCH_ACTION_NONE = 0,
    M3DS_TOUCH_ACTION_CAMERA_UP,
    M3DS_TOUCH_ACTION_CAMERA_DOWN,
    M3DS_TOUCH_ACTION_CAMERA_LEFT,
    M3DS_TOUCH_ACTION_CAMERA_RIGHT,
    M3DS_TOUCH_ACTION_ZOOM_IN,
    M3DS_TOUCH_ACTION_ZOOM_OUT,
    M3DS_TOUCH_ACTION_MENU,
    M3DS_TOUCH_ACTION_COUNT
} M3dsTouchAction;

/*
 * Maps a bottom-screen touch position to one of the bounded HUD controls.
 * Coordinates are the raw libctru touch pixel coordinates. The regions are
 * deliberately larger than the text labels so the controls remain usable on
 * both 3DS generations.
 */
M3dsTouchAction m3ds_touch_action_at(float x, float y);
const char *m3ds_touch_action_id(M3dsTouchAction action);
bool m3ds_touch_action_is_pan(M3dsTouchAction action);

/*
 * Maps a bottom-screen touch row to a pause-menu item. The menu renders rows
 * 16..18 of the 8 px console starting at y=120. Returns -1 outside the rows.
 */
int m3ds_touch_menu_row(float y, unsigned count);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_UI_TOUCH_CONTROLS_H */
