#include "ui/pause_menu.h"

#include <stddef.h>

static const char *const ACTION_LABELS[M3DS_PAUSE_ACTION_COUNT] = {
    "Resume",
    "Reset Camera",
    "Quit"
};

static const char *const ACTION_IDS[M3DS_PAUSE_ACTION_COUNT] = {
    "resume",
    "reset-camera",
    "quit"
};

void m3ds_pause_menu_init(M3dsPauseMenu *menu) {
    if (!menu) {
        return;
    }
    menu->open = false;
    menu->selected = 0u;
}

void m3ds_pause_menu_open(M3dsPauseMenu *menu) {
    if (!menu) {
        return;
    }
    menu->open = true;
}

void m3ds_pause_menu_close(M3dsPauseMenu *menu) {
    if (!menu) {
        return;
    }
    menu->open = false;
}

void m3ds_pause_menu_toggle(M3dsPauseMenu *menu) {
    if (!menu) {
        return;
    }
    menu->open = !menu->open;
}

bool m3ds_pause_menu_move(M3dsPauseMenu *menu, int delta) {
    if (!menu || !menu->open || delta == 0) {
        return false;
    }
    int selected = (int)menu->selected + delta;
    int count = (int)M3DS_PAUSE_ACTION_COUNT;
    selected %= count;
    if (selected < 0) {
        selected += count;
    }
    menu->selected = (uint8_t)selected;
    return true;
}

M3dsPauseAction m3ds_pause_menu_action(const M3dsPauseMenu *menu) {
    if (!menu || menu->selected >= M3DS_PAUSE_ACTION_COUNT) {
        return M3DS_PAUSE_ACTION_RESUME;
    }
    return (M3dsPauseAction)menu->selected;
}

const char *m3ds_pause_menu_action_label(M3dsPauseAction action) {
    if ((unsigned)action >= M3DS_PAUSE_ACTION_COUNT) {
        return "Resume";
    }
    return ACTION_LABELS[action];
}

const char *m3ds_pause_menu_action_id(M3dsPauseAction action) {
    if ((unsigned)action >= M3DS_PAUSE_ACTION_COUNT) {
        return "resume";
    }
    return ACTION_IDS[action];
}
