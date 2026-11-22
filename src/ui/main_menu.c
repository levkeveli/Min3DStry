#include "ui/main_menu.h"

#include <stdint.h>
#include <string.h>

typedef struct {
    const char *key;
    const char *label;
    bool enabled;
} MenuEntry;

/*
 * Labels come from the pinned upstream bundle
 * (core/assets/bundles/bundle.properties, 19a0f40d):
 *   play = Play, campaign = Campaign, joingame = Join Game,
 *   customgame = Custom Game, loadgame = Load Game,
 *   database.button = Database, editor = Editor, mods = Mods,
 *   settings = Settings, quit = Quit.
 */
static const MenuEntry TITLE_ITEMS[] = {
    {"play", "Play", true},
    {"database", "Database", true},
    {"editor", "Editor", false},
    {"mods", "Mods", false},
    {"settings", "Settings", true},
    {"quit", "Quit", true},
};

/*
 * Upstream database.button submenu: Schematics, Core Database, About.
 * Only About is implemented; the other two remain visible and unavailable.
 */
static const MenuEntry DATABASE_ITEMS[] = {
    {"schematics", "Schematics", false},
    {"database", "Core Database", true},
    {"about", "About", true},
};


static const MenuEntry ABOUT_ITEMS[] = {
    {"back", "Back", true},
};

static const MenuEntry PLAY_ITEMS[] = {
    {"campaign", "Campaign", true},
    {"joingame", "Join Game", false},
    {"customgame", "Custom Game", false},
    {"loadgame", "Load Game", false},
};

static const MenuEntry SETTINGS_ITEMS[] = {
    {"audio", "Audio", true},
    {"lighting", "Lighting", true},
    {"camera", "Reset camera", true},
    {"back", "Back", true},
};

static const MenuEntry *items_for(M3dsMenuScreen screen, size_t *count) {
    switch (screen) {
    case M3DS_MENU_SCREEN_PLAY:
        *count = sizeof(PLAY_ITEMS) / sizeof(PLAY_ITEMS[0]);
        return PLAY_ITEMS;
    case M3DS_MENU_SCREEN_SETTINGS:
        *count = sizeof(SETTINGS_ITEMS) / sizeof(SETTINGS_ITEMS[0]);
        return SETTINGS_ITEMS;
    case M3DS_MENU_SCREEN_DATABASE:
        *count = sizeof(DATABASE_ITEMS) / sizeof(DATABASE_ITEMS[0]);
        return DATABASE_ITEMS;
    case M3DS_MENU_SCREEN_ABOUT:
        *count = sizeof(ABOUT_ITEMS) / sizeof(ABOUT_ITEMS[0]);
        return ABOUT_ITEMS;
    case M3DS_MENU_SCREEN_CATALOG:
        *count = 0u;
        return NULL;
    case M3DS_MENU_SCREEN_TITLE:
    default:
        *count = sizeof(TITLE_ITEMS) / sizeof(TITLE_ITEMS[0]);
        return TITLE_ITEMS;
    }
}

static const MenuEntry *current_items(const M3dsMainMenu *menu, size_t *count) {
    return items_for(menu->screen, count);
}

void m3ds_main_menu_init(M3dsMainMenu *menu) {
    if (!menu) {
        return;
    }
    menu->open = true;
    menu->screen = M3DS_MENU_SCREEN_TITLE;
    menu->selected = 0u;
    menu->muted = false;
    menu->lighting = true;
    menu->catalog_category = M3DS_CATALOG_ITEMS;
    menu->catalog_selected = 0u;
    menu->catalog_scroll = 0u;
    menu->catalog_detail = false;
}

void m3ds_main_menu_catalog_switch(M3dsMainMenu *menu, int delta) {
    if (!menu || !menu->open || menu->screen != M3DS_MENU_SCREEN_CATALOG || delta == 0 ||
        menu->catalog_detail) {
        return;
    }
    menu->catalog_category =
        (menu->catalog_category == M3DS_CATALOG_ITEMS) ? M3DS_CATALOG_BLOCKS
                                                       : M3DS_CATALOG_ITEMS;
    menu->catalog_selected = 0u;
    menu->catalog_scroll = 0u;
}

void m3ds_main_menu_move(M3dsMainMenu *menu, int delta) {
    if (!menu || !menu->open || delta == 0) {
        return;
    }
    if (menu->screen == M3DS_MENU_SCREEN_CATALOG) {
        if (menu->catalog_detail) {
            return;
        }
        size_t total = m3ds_catalog_count(menu->catalog_category);
        if (total == 0u) {
            return;
        }
        int next = (int)menu->catalog_selected + delta;
        int wrapped = next % (int)total;
        if (wrapped < 0) {
            wrapped += (int)total;
        }
        menu->catalog_selected = (unsigned)wrapped;
        if (menu->catalog_selected < menu->catalog_scroll) {
            menu->catalog_scroll = menu->catalog_selected;
        } else if (menu->catalog_selected >=
                   menu->catalog_scroll + M3DS_CATALOG_ROWS) {
            menu->catalog_scroll =
                menu->catalog_selected - M3DS_CATALOG_ROWS + 1u;
        }
        return;
    }
    size_t count = 0u;
    (void)current_items(menu, &count);
    if (count == 0u) {
        return;
    }
    int next = (int)menu->selected + delta;
    int wrapped = next % (int)count;
    if (wrapped < 0) {
        wrapped += (int)count;
    }
    menu->selected = (unsigned)wrapped;
}

size_t m3ds_main_menu_count(const M3dsMainMenu *menu) {
    if (!menu) {
        return 0u;
    }
    if (menu->screen == M3DS_MENU_SCREEN_CATALOG) {
        return m3ds_catalog_count(menu->catalog_category);
    }
    size_t count = 0u;
    (void)current_items(menu, &count);
    return count;
}

const char *m3ds_main_menu_key(const M3dsMainMenu *menu, size_t index) {
    if (!menu) {
        return "";
    }
    if (menu->screen == M3DS_MENU_SCREEN_CATALOG) {
        const M3dsCatalogEntry *entry =
            m3ds_catalog_entry(menu->catalog_category, index);
        return entry ? entry->id : "";
    }
    size_t count = 0u;
    const MenuEntry *items = current_items(menu, &count);
    if (index >= count) {
        return "";
    }
    return items[index].key;
}

const char *m3ds_main_menu_label(const M3dsMainMenu *menu, size_t index) {
    if (!menu) {
        return "";
    }
    if (menu->screen == M3DS_MENU_SCREEN_CATALOG) {
        const M3dsCatalogEntry *entry =
            m3ds_catalog_entry(menu->catalog_category, index);
        return entry ? entry->name : "";
    }
    size_t count = 0u;
    const MenuEntry *items = current_items(menu, &count);
    if (index >= count) {
        return "";
    }
    return items[index].label;
}

bool m3ds_main_menu_enabled(const M3dsMainMenu *menu, size_t index) {
    if (!menu) {
        return false;
    }
    if (menu->screen == M3DS_MENU_SCREEN_CATALOG) {
        return index < m3ds_catalog_count(menu->catalog_category);
    }
    size_t count = 0u;
    const MenuEntry *items = current_items(menu, &count);
    if (index >= count) {
        return false;
    }
    return items[index].enabled;
}

M3dsMenuAction m3ds_main_menu_confirm(M3dsMainMenu *menu) {
    if (!menu || !menu->open) {
        return M3DS_MENU_ACTION_NONE;
    }
    if (menu->screen == M3DS_MENU_SCREEN_CATALOG) {
        menu->catalog_detail = !menu->catalog_detail;
        return M3DS_MENU_ACTION_NONE;
    }
    size_t count = 0u;
    const MenuEntry *items = current_items(menu, &count);
    if (menu->selected >= count) {
        return M3DS_MENU_ACTION_NONE;
    }
    const MenuEntry *entry = &items[menu->selected];
    if (!entry->enabled) {
        return M3DS_MENU_ACTION_UNAVAILABLE;
    }

    switch (menu->screen) {
    case M3DS_MENU_SCREEN_TITLE:
        if (strcmp(entry->key, "play") == 0) {
            menu->screen = M3DS_MENU_SCREEN_PLAY;
            menu->selected = 0u;
            return M3DS_MENU_ACTION_NONE;
        }
        if (strcmp(entry->key, "settings") == 0) {
            menu->screen = M3DS_MENU_SCREEN_SETTINGS;
            menu->selected = 0u;
            return M3DS_MENU_ACTION_NONE;
        }
        if (strcmp(entry->key, "database") == 0) {
            menu->screen = M3DS_MENU_SCREEN_DATABASE;
            menu->selected = 0u;
            return M3DS_MENU_ACTION_NONE;
        }
        if (strcmp(entry->key, "quit") == 0) {
            return M3DS_MENU_ACTION_QUIT;
        }
        return M3DS_MENU_ACTION_UNAVAILABLE;
    case M3DS_MENU_SCREEN_PLAY:
        if (strcmp(entry->key, "campaign") == 0) {
            menu->open = false;
            return M3DS_MENU_ACTION_PLAY_CAMPAIGN;
        }
        return M3DS_MENU_ACTION_UNAVAILABLE;
    case M3DS_MENU_SCREEN_SETTINGS:
        if (strcmp(entry->key, "audio") == 0) {
            menu->muted = !menu->muted;
            return M3DS_MENU_ACTION_TOGGLE_MUTE;
        }
        if (strcmp(entry->key, "lighting") == 0) {
            menu->lighting = !menu->lighting;
            return M3DS_MENU_ACTION_TOGGLE_LIGHTING;
        }
        if (strcmp(entry->key, "camera") == 0) {
            return M3DS_MENU_ACTION_RESET_CAMERA;
        }
        if (strcmp(entry->key, "back") == 0) {
            menu->screen = M3DS_MENU_SCREEN_TITLE;
            menu->selected = 0u;
            return M3DS_MENU_ACTION_NONE;
        }
        return M3DS_MENU_ACTION_NONE;
    case M3DS_MENU_SCREEN_DATABASE:
        if (strcmp(entry->key, "database") == 0) {
            menu->screen = M3DS_MENU_SCREEN_CATALOG;
            menu->catalog_selected = 0u;
            menu->catalog_scroll = 0u;
            menu->catalog_detail = false;
            return M3DS_MENU_ACTION_NONE;
        }
        if (strcmp(entry->key, "about") == 0) {
            menu->screen = M3DS_MENU_SCREEN_ABOUT;
            menu->selected = 0u;
            return M3DS_MENU_ACTION_NONE;
        }
        return M3DS_MENU_ACTION_UNAVAILABLE;
    case M3DS_MENU_SCREEN_CATALOG:
        menu->catalog_detail = !menu->catalog_detail;
        return M3DS_MENU_ACTION_NONE;
    case M3DS_MENU_SCREEN_ABOUT:
        if (strcmp(entry->key, "back") == 0) {
            menu->screen = M3DS_MENU_SCREEN_DATABASE;
            menu->selected = 0u;
        }
        return M3DS_MENU_ACTION_NONE;
    default:
        return M3DS_MENU_ACTION_NONE;
    }
}

bool m3ds_main_menu_back(M3dsMainMenu *menu) {
    if (!menu || !menu->open) {
        return false;
    }
    if (menu->screen == M3DS_MENU_SCREEN_ABOUT) {
        menu->screen = M3DS_MENU_SCREEN_DATABASE;
        menu->selected = 0u;
        return true;
    }
    if (menu->screen == M3DS_MENU_SCREEN_CATALOG) {
        if (menu->catalog_detail) {
            menu->catalog_detail = false;
            return true;
        }
        menu->screen = M3DS_MENU_SCREEN_DATABASE;
        menu->selected = 1u;
        return true;
    }
    if (menu->screen != M3DS_MENU_SCREEN_TITLE) {
        menu->screen = M3DS_MENU_SCREEN_TITLE;
        menu->selected = 0u;
        return true;
    }
    return false;
}

const char *m3ds_main_menu_screen_name(const M3dsMainMenu *menu) {
    if (!menu) {
        return "none";
    }
    switch (menu->screen) {
    case M3DS_MENU_SCREEN_PLAY:
        return "play";
    case M3DS_MENU_SCREEN_SETTINGS:
        return "settings";
    case M3DS_MENU_SCREEN_DATABASE:
        return "database";
    case M3DS_MENU_SCREEN_ABOUT:
        return "about";
    case M3DS_MENU_SCREEN_CATALOG:
        return "catalog";
    case M3DS_MENU_SCREEN_TITLE:
    default:
        return "title";
    }
}
