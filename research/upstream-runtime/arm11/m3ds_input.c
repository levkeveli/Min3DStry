#include "m3ds_input.h"

#include <3ds.h>

static int32_t cached_touch = -1;

static uint8_t pack_axis(int value) {
    if (value > 127) value = 127;
    if (value < -127) value = -127;
    return (uint8_t)value;
}

int32_t m3ds_input_poll(void) {
    cached_touch = -1;
    if (!aptMainLoop()) return -1;
    hidScanInput();
    const u32 keys = hidKeysHeld();
    const u32 pressed = hidKeysDown();
    if (pressed & KEY_START) return -1;

    if (pressed & KEY_TOUCH) {
        touchPosition touch;
        hidTouchRead(&touch);
        cached_touch = (int32_t)touch.px | ((int32_t)touch.py << 16);
    }

    circlePosition pad = {0};
    hidCircleRead(&pad);
    const bool dpad = (keys & (KEY_DLEFT | KEY_DRIGHT | KEY_DUP | KEY_DDOWN)) != 0;
    if (dpad) {
        pad.dx = 127 * ((int)((keys & KEY_DRIGHT) != 0) - (int)((keys & KEY_DLEFT) != 0));
        pad.dy = 127 * ((int)((keys & KEY_DUP) != 0) - (int)((keys & KEY_DDOWN) != 0));
    }
    return (int32_t)pack_axis(pad.dx) | ((int32_t)pack_axis(pad.dy) << 8) |
           ((int32_t)dpad << 16) | ((int32_t)((pressed & KEY_A) != 0) << 17) |
           ((int32_t)((pressed & KEY_R) != 0) << 18) | ((int32_t)((pressed & KEY_B) != 0) << 19) |
           ((int32_t)((pressed & KEY_X) != 0) << 20) | ((int32_t)((pressed & KEY_Y) != 0) << 21) |
           ((int32_t)((pressed & KEY_SELECT) != 0) << 22) | ((int32_t)((keys & KEY_L) != 0) << 23) |
           ((int32_t)((keys & KEY_X) != 0) << 25);
}

int32_t m3ds_input_touch(void) {
    return cached_touch;
}
