#include "ui/main_menu.h"

#include <stdio.h>
#include <string.h>

static int failures = 0;

#define CHECK(cond)                                                        \
    do {                                                                   \
        if (!(cond)) {                                                     \
            printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);         \
            ++failures;                                                    \
        }                                                                  \
    } while (0)

static void test_labels_and_counts(void) {
    M3dsMainMenu menu;
    m3ds_main_menu_init(&menu);
    CHECK(menu.open);
    CHECK(menu.screen == M3DS_MENU_SCREEN_TITLE);
    CHECK(menu.selected == 0u);
    CHECK(m3ds_main_menu_count(&menu) == 6u);
    CHECK(strcmp(m3ds_main_menu_key(&menu, 0u), "play") == 0);
    CHECK(strcmp(m3ds_main_menu_label(&menu, 0u), "Play") == 0);
    CHECK(strcmp(m3ds_main_menu_label(&menu, 1u), "Database") == 0);
    CHECK(strcmp(m3ds_main_menu_label(&menu, 5u), "Quit") == 0);
    CHECK(m3ds_main_menu_enabled(&menu, 0u));
    CHECK(!m3ds_main_menu_enabled(&menu, 1u));
    CHECK(m3ds_main_menu_enabled(&menu, 5u));
}

static void test_wrapping(void) {
    M3dsMainMenu menu;
    m3ds_main_menu_init(&menu);
    m3ds_main_menu_move(&menu, -1);
    CHECK(menu.selected == 5u);
    m3ds_main_menu_move(&menu, 1);
    CHECK(menu.selected == 0u);
    m3ds_main_menu_move(&menu, 7);
    CHECK(menu.selected == 1u);
}

static void test_play_submenu_and_campaign(void) {
    M3dsMainMenu menu;
    m3ds_main_menu_init(&menu);
    M3dsMenuAction action = m3ds_main_menu_confirm(&menu);
    CHECK(action == M3DS_MENU_ACTION_NONE);
    CHECK(menu.screen == M3DS_MENU_SCREEN_PLAY);
    CHECK(menu.selected == 0u);
    CHECK(m3ds_main_menu_count(&menu) == 4u);
    CHECK(strcmp(m3ds_main_menu_key(&menu, 0u), "campaign") == 0);
    CHECK(strcmp(m3ds_main_menu_label(&menu, 2u), "Custom Game") == 0);
    CHECK(!m3ds_main_menu_enabled(&menu, 1u));

    action = m3ds_main_menu_confirm(&menu);
    CHECK(action == M3DS_MENU_ACTION_PLAY_CAMPAIGN);
    CHECK(!menu.open);
}

static void test_unavailable_entries(void) {
    M3dsMainMenu menu;
    m3ds_main_menu_init(&menu);
    m3ds_main_menu_move(&menu, 1); /* Database */
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_UNAVAILABLE);
    CHECK(menu.screen == M3DS_MENU_SCREEN_TITLE);

    m3ds_main_menu_move(&menu, 1); /* Editor */
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_UNAVAILABLE);

    m3ds_main_menu_move(&menu, 1); /* Mods */
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_UNAVAILABLE);

    m3ds_main_menu_move(&menu, -3); /* Play */
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_NONE);
    m3ds_main_menu_move(&menu, 1); /* Join Game */
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_UNAVAILABLE);
}

static void test_settings_and_back(void) {
    M3dsMainMenu menu;
    m3ds_main_menu_init(&menu);
    m3ds_main_menu_move(&menu, 4); /* Settings */
    CHECK(strcmp(m3ds_main_menu_key(&menu, menu.selected), "settings") == 0);
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_NONE);
    CHECK(menu.screen == M3DS_MENU_SCREEN_SETTINGS);
    CHECK(m3ds_main_menu_count(&menu) == 4u);

    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_TOGGLE_MUTE);
    CHECK(menu.muted);
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_TOGGLE_MUTE);
    CHECK(!menu.muted);

    m3ds_main_menu_move(&menu, 1);
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_TOGGLE_LIGHTING);
    CHECK(!menu.lighting);

    m3ds_main_menu_move(&menu, 1);
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_RESET_CAMERA);

    m3ds_main_menu_move(&menu, 1);
    CHECK(strcmp(m3ds_main_menu_key(&menu, menu.selected), "back") == 0);
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_NONE);
    CHECK(menu.screen == M3DS_MENU_SCREEN_TITLE);

    CHECK(m3ds_main_menu_back(&menu) == false);
    m3ds_main_menu_confirm(&menu); /* Play */
    CHECK(m3ds_main_menu_back(&menu) == true);
    CHECK(menu.screen == M3DS_MENU_SCREEN_TITLE);
    CHECK(menu.selected == 0u);
}

static void test_quit(void) {
    M3dsMainMenu menu;
    m3ds_main_menu_init(&menu);
    m3ds_main_menu_move(&menu, 5);
    CHECK(strcmp(m3ds_main_menu_key(&menu, menu.selected), "quit") == 0);
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_QUIT);
}

static void test_screen_names(void) {
    M3dsMainMenu menu;
    m3ds_main_menu_init(&menu);
    CHECK(strcmp(m3ds_main_menu_screen_name(&menu), "title") == 0);
    m3ds_main_menu_confirm(&menu);
    CHECK(strcmp(m3ds_main_menu_screen_name(&menu), "play") == 0);
    m3ds_main_menu_back(&menu);
    m3ds_main_menu_move(&menu, 4);
    m3ds_main_menu_confirm(&menu);
    CHECK(strcmp(m3ds_main_menu_screen_name(&menu), "settings") == 0);
}

int main(void) {
    test_labels_and_counts();
    test_wrapping();
    test_play_submenu_and_campaign();
    test_unavailable_entries();
    test_settings_and_back();
    test_quit();
    test_screen_names();
    if (failures != 0) {
        printf("MAIN_MENU FAIL failures=%d\n", failures);
        return 1;
    }
    printf("MAIN_MENU PASS\n");
    return 0;
}
