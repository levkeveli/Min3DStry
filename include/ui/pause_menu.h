#ifndef MIN3DSTRY_UI_PAUSE_MENU_H
#define MIN3DSTRY_UI_PAUSE_MENU_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum M3dsPauseAction {
    M3DS_PAUSE_ACTION_RESUME = 0,
    M3DS_PAUSE_ACTION_RESET_CAMERA = 1,
    M3DS_PAUSE_ACTION_QUIT = 2,
    M3DS_PAUSE_ACTION_COUNT = 3
} M3dsPauseAction;

typedef struct M3dsPauseMenu {
    bool open;
    uint8_t selected;
} M3dsPauseMenu;

void m3ds_pause_menu_init(M3dsPauseMenu *menu);
void m3ds_pause_menu_open(M3dsPauseMenu *menu);
void m3ds_pause_menu_close(M3dsPauseMenu *menu);
void m3ds_pause_menu_toggle(M3dsPauseMenu *menu);
bool m3ds_pause_menu_move(M3dsPauseMenu *menu, int delta);
M3dsPauseAction m3ds_pause_menu_action(const M3dsPauseMenu *menu);
const char *m3ds_pause_menu_action_label(M3dsPauseAction action);
const char *m3ds_pause_menu_action_id(M3dsPauseAction action);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_UI_PAUSE_MENU_H */
