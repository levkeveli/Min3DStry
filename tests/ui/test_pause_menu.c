#include "ui/pause_menu.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(void) {
    M3dsPauseMenu menu;
    m3ds_pause_menu_init(&menu);
    assert(!menu.open);
    assert(menu.selected == 0u);
    assert(m3ds_pause_menu_action(&menu) == M3DS_PAUSE_ACTION_RESUME);
    assert(strcmp(m3ds_pause_menu_action_label(M3DS_PAUSE_ACTION_RESUME), "Resume") == 0);
    assert(strcmp(m3ds_pause_menu_action_id(M3DS_PAUSE_ACTION_QUIT), "quit") == 0);

    assert(!m3ds_pause_menu_move(&menu, 1));
    m3ds_pause_menu_open(&menu);
    assert(menu.open);
    assert(m3ds_pause_menu_move(&menu, 1));
    assert(menu.selected == 1u);
    assert(m3ds_pause_menu_action(&menu) == M3DS_PAUSE_ACTION_RESET_CAMERA);
    assert(m3ds_pause_menu_move(&menu, 1));
    assert(menu.selected == 2u);
    assert(m3ds_pause_menu_action(&menu) == M3DS_PAUSE_ACTION_QUIT);
    assert(m3ds_pause_menu_move(&menu, 1));
    assert(menu.selected == 0u);
    assert(m3ds_pause_menu_move(&menu, -1));
    assert(menu.selected == 2u);

    m3ds_pause_menu_close(&menu);
    assert(!menu.open);
    m3ds_pause_menu_toggle(&menu);
    assert(menu.open);
    m3ds_pause_menu_toggle(&menu);
    assert(!menu.open);

    m3ds_pause_menu_init(NULL);
    m3ds_pause_menu_open(NULL);
    m3ds_pause_menu_close(NULL);
    m3ds_pause_menu_toggle(NULL);
    assert(!m3ds_pause_menu_move(NULL, 1));
    assert(m3ds_pause_menu_action(NULL) == M3DS_PAUSE_ACTION_RESUME);
    assert(strcmp(m3ds_pause_menu_action_label(
        (M3dsPauseAction)M3DS_PAUSE_ACTION_COUNT), "Resume") == 0);
    assert(strcmp(m3ds_pause_menu_action_id(
        (M3dsPauseAction)M3DS_PAUSE_ACTION_COUNT), "resume") == 0);

    puts("test_pause_menu: all assertions passed.");
    return 0;
}
