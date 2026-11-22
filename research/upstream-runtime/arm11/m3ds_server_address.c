#include "m3ds_server_address.h"

#include <3ds.h>
#include <string.h>

int32_t m3ds_read_server_address(char *text, int32_t capacity, int32_t invalid) {
    if (capacity < 2) return -1;
    if (!aptMainLoop()) return 0;
    SwkbdState keyboard;
    swkbdInit(&keyboard, SWKBD_TYPE_NORMAL, 2, 259);
    swkbdSetFeatures(&keyboard, SWKBD_DEFAULT_QWERTY | SWKBD_DARKEN_TOP_SCREEN);
    swkbdSetValidation(&keyboard, SWKBD_NOTEMPTY_NOTBLANK, 0, 0);
    swkbdSetHintText(&keyboard, invalid
        ? "Invalid address. Use host[:port], port 1-65535."
        : "Server IP or hostname[:port]");
    swkbdSetInitialText(&keyboard, text);
    SwkbdButton button = swkbdInputText(&keyboard, text, (size_t)capacity);
    if (button == SWKBD_BUTTON_CONFIRM) return (int32_t)strnlen(text, (size_t)capacity);
    if (button == SWKBD_BUTTON_LEFT) return 0;
    return -1;
}
