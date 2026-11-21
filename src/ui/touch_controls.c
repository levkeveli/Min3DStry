#include "ui/touch_controls.h"

typedef struct {
    float x0;
    float y0;
    float x1;
    float y1;
    M3dsTouchAction action;
} TouchRegion;

/*
 * Keep the right column clear of the text HUD rows where possible. The menu
 * button sits at the top-right, zoom below it, and the pan pad on the left.
 */
static const TouchRegion regions[] = {
    {248.0f, 8.0f, 312.0f, 48.0f, M3DS_TOUCH_ACTION_MENU},
    {248.0f, 64.0f, 312.0f, 104.0f, M3DS_TOUCH_ACTION_ZOOM_IN},
    {248.0f, 120.0f, 312.0f, 160.0f, M3DS_TOUCH_ACTION_ZOOM_OUT},
    {48.0f, 120.0f, 80.0f, 152.0f, M3DS_TOUCH_ACTION_CAMERA_UP},
    {48.0f, 168.0f, 80.0f, 200.0f, M3DS_TOUCH_ACTION_CAMERA_DOWN},
    {16.0f, 140.0f, 48.0f, 180.0f, M3DS_TOUCH_ACTION_CAMERA_LEFT},
    {80.0f, 140.0f, 112.0f, 180.0f, M3DS_TOUCH_ACTION_CAMERA_RIGHT}
};

M3dsTouchAction m3ds_touch_action_at(float x, float y) {
    if (x < 0.0f || y < 0.0f || x >= M3DS_TOUCH_SCREEN_WIDTH || y >= M3DS_TOUCH_SCREEN_HEIGHT) {
        return M3DS_TOUCH_ACTION_NONE;
    }
    for (unsigned i = 0; i < sizeof(regions) / sizeof(regions[0]); ++i) {
        const TouchRegion *region = &regions[i];
        if (x >= region->x0 && x < region->x1 && y >= region->y0 && y < region->y1) {
            return region->action;
        }
    }
    return M3DS_TOUCH_ACTION_NONE;
}

const char *m3ds_touch_action_id(M3dsTouchAction action) {
    switch (action) {
        case M3DS_TOUCH_ACTION_CAMERA_UP:    return "camera.up";
        case M3DS_TOUCH_ACTION_CAMERA_DOWN:  return "camera.down";
        case M3DS_TOUCH_ACTION_CAMERA_LEFT:  return "camera.left";
        case M3DS_TOUCH_ACTION_CAMERA_RIGHT: return "camera.right";
        case M3DS_TOUCH_ACTION_ZOOM_IN:      return "zoom.in";
        case M3DS_TOUCH_ACTION_ZOOM_OUT:     return "zoom.out";
        case M3DS_TOUCH_ACTION_MENU:         return "menu";
        case M3DS_TOUCH_ACTION_NONE:
        default:                             return "none";
    }
}

bool m3ds_touch_action_is_pan(M3dsTouchAction action) {
    return action == M3DS_TOUCH_ACTION_CAMERA_UP
        || action == M3DS_TOUCH_ACTION_CAMERA_DOWN
        || action == M3DS_TOUCH_ACTION_CAMERA_LEFT
        || action == M3DS_TOUCH_ACTION_CAMERA_RIGHT;
}
