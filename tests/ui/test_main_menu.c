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
    CHECK(m3ds_main_menu_enabled(&menu, 1u));
    CHECK(!m3ds_main_menu_enabled(&menu, 2u));
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
    m3ds_main_menu_move(&menu, 2); /* Editor */
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_UNAVAILABLE);

    m3ds_main_menu_move(&menu, 1); /* Mods */
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_UNAVAILABLE);

    m3ds_main_menu_move(&menu, -3); /* Play */
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_NONE);
    m3ds_main_menu_move(&menu, 1); /* Join Game */
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_UNAVAILABLE);
}

static void test_database_and_about(void) {
    M3dsMainMenu menu;
    m3ds_main_menu_init(&menu);
    m3ds_main_menu_move(&menu, 1); /* Database */
    CHECK(strcmp(m3ds_main_menu_key(&menu, menu.selected), "database") == 0);
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_NONE);
    CHECK(menu.screen == M3DS_MENU_SCREEN_DATABASE);
    CHECK(m3ds_main_menu_count(&menu) == 3u);
    CHECK(strcmp(m3ds_main_menu_key(&menu, 0u), "schematics") == 0);
    CHECK(strcmp(m3ds_main_menu_label(&menu, 0u), "Schematics") == 0);
    CHECK(strcmp(m3ds_main_menu_label(&menu, 1u), "Core Database") == 0);
    CHECK(strcmp(m3ds_main_menu_key(&menu, 2u), "about") == 0);
    CHECK(!m3ds_main_menu_enabled(&menu, 0u));
    CHECK(m3ds_main_menu_enabled(&menu, 1u));
    CHECK(m3ds_main_menu_enabled(&menu, 2u));

    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_UNAVAILABLE);
    m3ds_main_menu_move(&menu, 1);
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_NONE);
    CHECK(menu.screen == M3DS_MENU_SCREEN_CATALOG);
    CHECK(m3ds_main_menu_back(&menu) == true);
    CHECK(menu.screen == M3DS_MENU_SCREEN_DATABASE);
    m3ds_main_menu_move(&menu, 1);
    CHECK(strcmp(m3ds_main_menu_key(&menu, menu.selected), "about") == 0);
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_NONE);
    CHECK(menu.screen == M3DS_MENU_SCREEN_ABOUT);
    CHECK(m3ds_main_menu_count(&menu) == 1u);
    CHECK(strcmp(m3ds_main_menu_key(&menu, 0u), "back") == 0);

    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_NONE);
    CHECK(menu.screen == M3DS_MENU_SCREEN_DATABASE);

    CHECK(m3ds_main_menu_back(&menu) == true);
    CHECK(menu.screen == M3DS_MENU_SCREEN_TITLE);
    CHECK(menu.selected == 0u);

    /* B from About goes to Database, not straight to the title. */
    m3ds_main_menu_move(&menu, 1);
    m3ds_main_menu_confirm(&menu); /* database */
    m3ds_main_menu_move(&menu, 2);
    m3ds_main_menu_confirm(&menu); /* about */
    CHECK(menu.screen == M3DS_MENU_SCREEN_ABOUT);
    CHECK(m3ds_main_menu_back(&menu) == true);
    CHECK(menu.screen == M3DS_MENU_SCREEN_DATABASE);
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

static void test_catalog_navigation(void) {
    M3dsMainMenu menu;
    m3ds_main_menu_init(&menu);
    m3ds_main_menu_move(&menu, 1); /* Database */
    m3ds_main_menu_confirm(&menu);
    m3ds_main_menu_move(&menu, 1); /* Core Database */
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_NONE);
    CHECK(menu.screen == M3DS_MENU_SCREEN_CATALOG);
    CHECK(m3ds_main_menu_count(&menu) == 22u);
    CHECK(menu.catalog_category == M3DS_CATALOG_ITEMS);
    CHECK(strcmp(m3ds_main_menu_key(&menu, 0u), "beryllium") == 0);

    /* A opens the detail view; movement/category switching is ignored; B closes it. */
    CHECK(m3ds_main_menu_confirm(&menu) == M3DS_MENU_ACTION_NONE);
    CHECK(menu.catalog_detail);
    unsigned before = menu.catalog_selected;
    m3ds_main_menu_move(&menu, 1);
    CHECK(menu.catalog_selected == before);
    m3ds_main_menu_catalog_switch(&menu, 1);
    CHECK(menu.catalog_category == M3DS_CATALOG_ITEMS);
    CHECK(m3ds_main_menu_back(&menu) == true);
    CHECK(!menu.catalog_detail);
    CHECK(menu.screen == M3DS_MENU_SCREEN_CATALOG);

    m3ds_main_menu_move(&menu, 13);
    CHECK(menu.catalog_selected == 13u);
    CHECK(menu.catalog_scroll == 2u); /* 13 - 12 + 1 */

    m3ds_main_menu_move(&menu, 8); /* 21, last item */
    CHECK(menu.catalog_selected == 21u);
    CHECK(menu.catalog_scroll == 10u);
    m3ds_main_menu_move(&menu, 1); /* wrap to first */
    CHECK(menu.catalog_selected == 0u);
    CHECK(menu.catalog_scroll == 0u);

    m3ds_main_menu_catalog_switch(&menu, 1);
    CHECK(menu.catalog_category == M3DS_CATALOG_BLOCKS);
    CHECK(m3ds_main_menu_count(&menu) == 258u);
    CHECK(menu.catalog_selected == 0u);
    CHECK(menu.catalog_scroll == 0u);
    CHECK(menu.catalog_filter == M3DS_CATALOG_FILTER_ALL);

    m3ds_main_menu_catalog_filter_next(&menu, 1);
    CHECK(menu.catalog_filter == 1u);
    CHECK(m3ds_main_menu_count(&menu) ==
          m3ds_catalog_filtered_count(M3DS_CATALOG_BLOCKS, 1u));
    CHECK(m3ds_main_menu_count(&menu) < 258u);
    CHECK(menu.catalog_selected == 0u);

    size_t filters = m3ds_catalog_filter_count(M3DS_CATALOG_BLOCKS);
    for (size_t i = 1u; i < filters; ++i) {
        m3ds_main_menu_catalog_filter_next(&menu, 1);
    }
    CHECK(menu.catalog_filter == M3DS_CATALOG_FILTER_ALL);
    CHECK(m3ds_main_menu_count(&menu) == 258u);

    /* Detail suppresses filter cycling and movement. */
    m3ds_main_menu_confirm(&menu); /* open detail */
    CHECK(menu.catalog_detail);
    m3ds_main_menu_catalog_filter_next(&menu, 1);
    CHECK(menu.catalog_filter == M3DS_CATALOG_FILTER_ALL);
    m3ds_main_menu_confirm(&menu); /* close detail */

    m3ds_main_menu_catalog_switch(&menu, -1);
    CHECK(menu.catalog_category == M3DS_CATALOG_ITEMS);
    CHECK(menu.catalog_filter == M3DS_CATALOG_FILTER_ALL);
    CHECK(m3ds_main_menu_count(&menu) == 22u);

    CHECK(m3ds_main_menu_back(&menu) == true);
    CHECK(menu.screen == M3DS_MENU_SCREEN_DATABASE);
    CHECK(menu.selected == 1u);
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
    m3ds_main_menu_back(&menu);
    m3ds_main_menu_move(&menu, 1); /* Database */
    m3ds_main_menu_confirm(&menu);
    CHECK(strcmp(m3ds_main_menu_screen_name(&menu), "database") == 0);
    m3ds_main_menu_move(&menu, 2);
    m3ds_main_menu_confirm(&menu);
    CHECK(strcmp(m3ds_main_menu_screen_name(&menu), "about") == 0);
    m3ds_main_menu_back(&menu);
    m3ds_main_menu_move(&menu, 1);
    m3ds_main_menu_confirm(&menu);
    CHECK(strcmp(m3ds_main_menu_screen_name(&menu), "catalog") == 0);
}

int main(void) {
    test_labels_and_counts();
    test_wrapping();
    test_play_submenu_and_campaign();
    test_unavailable_entries();
    test_database_and_about();
    test_catalog_navigation();
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
