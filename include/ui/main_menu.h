#ifndef MIN3DSTRY_UI_MAIN_MENU_H
#define MIN3DSTRY_UI_MAIN_MENU_H

#include <stdbool.h>
#include <stddef.h>

#include "ui/content_catalog.h"
#include "ui/schematics_catalog.h"
#include "ui/user_schematics.h"

/*
 * Stock title menu shell derived from upstream Mindustry v160.8
 * core/src/mindustry/ui/fragments/MenuFragment.java (commit 19a0f40d).
 *
 * Upstream hierarchy (desktopButtons):
 *   Play      -> Campaign, Join Game, Custom Game, Load Game
 *   Database  -> Schematics, Core Database, About
 *   Editor
 *   Mods
 *   Settings
 *   Quit
 *
 * This port implements the Play/Campaign, Settings, Database (About, Core
 * Database, read-only Schematics) branches. Join/Custom/Load, Editor and Mods
 * stay visible (matching the upstream menu) but report
 * M3DS_MENU_ACTION_UNAVAILABLE so the caller can log them instead of silently
 * pretending they work.
 */

typedef enum {
    M3DS_MENU_SCREEN_TITLE = 0,
    M3DS_MENU_SCREEN_PLAY,
    M3DS_MENU_SCREEN_SETTINGS,
    M3DS_MENU_SCREEN_DATABASE,
    M3DS_MENU_SCREEN_ABOUT,
    M3DS_MENU_SCREEN_CATALOG,
    M3DS_MENU_SCREEN_SCHEMATICS,
} M3dsMenuScreen;

typedef enum {
    M3DS_MENU_ACTION_NONE = 0,
    M3DS_MENU_ACTION_PLAY_CAMPAIGN,
    M3DS_MENU_ACTION_TOGGLE_MUTE,
    M3DS_MENU_ACTION_TOGGLE_LIGHTING,
    M3DS_MENU_ACTION_RESET_CAMERA,
    M3DS_MENU_ACTION_QUIT,
    M3DS_MENU_ACTION_UNAVAILABLE,
} M3dsMenuAction;

typedef struct {
    bool open;
    M3dsMenuScreen screen;
    unsigned selected;
    bool muted;
    bool lighting;
    M3dsCatalogCategory catalog_category;
    unsigned catalog_selected;
    unsigned catalog_scroll;
    bool catalog_detail;
    unsigned catalog_filter;
    unsigned schematic_selected;
    unsigned schematic_scroll;
    bool schematic_detail;
    bool schematics_user;
} M3dsMainMenu;

void m3ds_main_menu_init(M3dsMainMenu *menu);

void m3ds_main_menu_move(M3dsMainMenu *menu, int delta);

/* Switch the Core Database category (Items <-> Blocks) during a catalog view. */
void m3ds_main_menu_catalog_switch(M3dsMainMenu *menu, int delta);

/* Cycle the upstream block-category filter (All, distribution, ...). */
void m3ds_main_menu_catalog_filter_next(M3dsMainMenu *menu, int delta);

/* Entry under the cursor in the filtered catalog (NULL when empty). */
const M3dsCatalogEntry *m3ds_main_menu_catalog_entry(const M3dsMainMenu *menu,
                                                     size_t index);

/* Entry under the cursor in the read-only Schematics list (NULL when empty). */
const M3dsSchematicRow *m3ds_main_menu_schematic_entry(const M3dsMainMenu *menu,
                                                       size_t index);

/* Toggle the Schematics source between built-in data and sd:/3ds/min3dstry/schematics. */
void m3ds_main_menu_schematics_toggle_source(M3dsMainMenu *menu);

/* Entry under the cursor when the SD-card source is active (NULL otherwise). */
const M3dsUserSchematic *m3ds_main_menu_user_schematic(const M3dsMainMenu *menu,
                                                       size_t index);

size_t m3ds_main_menu_count(const M3dsMainMenu *menu);

const char *m3ds_main_menu_key(const M3dsMainMenu *menu, size_t index);

const char *m3ds_main_menu_label(const M3dsMainMenu *menu, size_t index);

bool m3ds_main_menu_enabled(const M3dsMainMenu *menu, size_t index);

M3dsMenuAction m3ds_main_menu_confirm(M3dsMainMenu *menu);

bool m3ds_main_menu_back(M3dsMainMenu *menu);

const char *m3ds_main_menu_screen_name(const M3dsMainMenu *menu);

#endif
