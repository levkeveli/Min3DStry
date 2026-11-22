#include "m3ds_save_rows.h"

#include <3ds.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "platform/teavm_world_loader.h"
#include "platform/world_rows.h"

extern int m3ds_teavm_main(int argc, char **argv);

static M3dsWorldTileRow *g_rows;
static M3dsWorldBuildingRow *g_buildings;
static size_t g_width;
static size_t g_height;
static size_t g_tile_count;
static size_t g_building_count;
static bool g_verify_fixture;
static M3dsWorldTiles g_loaded_tiles;
static bool g_loaded_ready;
static const char *g_requested_save_path;

int m3ds_save_rows_copy_path(void *destination, int capacity) {
    if (!g_requested_save_path || !destination || capacity <= 0) {
        return -1;
    }
    int length = (int)strlen(g_requested_save_path);
    if (length >= capacity) {
        return -1;
    }
    memcpy(destination, g_requested_save_path, (size_t)length + 1u);
    return length;
}

static void release_rows(void) {
    free(g_buildings);
    free(g_rows);
    g_buildings = NULL;
    g_rows = NULL;
    g_width = 0;
    g_height = 0;
    g_tile_count = 0;
    g_building_count = 0;
}

int m3ds_save_rows_begin(
    int width, int height, int tiles, int buildings, int verifyFixture) {
    release_rows();
    if (g_loaded_ready) {
        m3ds_msav_free_world(&g_loaded_tiles);
        g_loaded_ready = false;
    }
    g_verify_fixture = verifyFixture != 0;
    if (width <= 0 || height <= 0 || tiles != width * height || buildings < 0) {
        return 1;
    }
    g_rows = calloc((size_t)tiles, sizeof(M3dsWorldTileRow));
    g_buildings = calloc(buildings > 0 ? (size_t)buildings : 1u,
                         sizeof(M3dsWorldBuildingRow));
    if (!g_rows || !g_buildings) {
        release_rows();
        return 1;
    }
    g_width = (size_t)width;
    g_height = (size_t)height;
    g_tile_count = (size_t)tiles;
    g_building_count = (size_t)buildings;
    for (size_t i = 0; i < g_tile_count; ++i) {
        g_rows[i].x = (uint32_t)(i % g_width);
        g_rows[i].y = (uint32_t)(i / g_width);
        g_rows[i].building_index = UINT32_MAX;
    }
    return 0;
}

void m3ds_save_rows_tile(int index, int floor, int overlay, int block,
                         int x, int y, int flags, int buildingIndex) {
    if (index < 0 || (size_t)index >= g_tile_count) {
        return;
    }
    M3dsWorldTileRow *row = &g_rows[index];
    row->floor_id = (uint16_t)floor;
    row->overlay_id = (uint16_t)overlay;
    row->block_id = (uint16_t)block;
    row->x = (uint32_t)x;
    row->y = (uint32_t)y;
    row->flags = (uint8_t)flags;
    row->building_index = buildingIndex < 0
        ? UINT32_MAX : (uint32_t)buildingIndex;
}

void m3ds_save_rows_building(int index, int tileIndex, int x, int y,
                             int block, int team, int rotation, int enabled,
                             int modules, int healthBits, int itemCount,
                             int powerLinks, int powerStatusBits,
                             int liquidCount, int liquidId,
                             int liquidAmountBits, int extraSize,
                             int stateLow, int stateHigh) {
    if (index < 0 || (size_t)index >= g_building_count
            || tileIndex < 0 || (size_t)tileIndex >= g_tile_count) {
        return;
    }
    M3dsWorldBuildingRow *row = &g_buildings[index];
    row->tile_index = (uint32_t)tileIndex;
    row->x = (uint32_t)x;
    row->y = (uint32_t)y;
    row->block_id = (uint16_t)block;
    row->team = (uint8_t)team;
    row->rotation = (uint8_t)rotation;
    row->enabled = (uint8_t)enabled;
    row->modules = (uint8_t)modules;
    row->health = 0.0f;
    memcpy(&row->health, &healthBits, sizeof(row->health));
    row->item_count = (uint32_t)itemCount;
    row->power_links = (uint32_t)powerLinks;
    row->power_status = 0.0f;
    memcpy(&row->power_status, &powerStatusBits, sizeof(row->power_status));
    row->liquid_count = (uint32_t)liquidCount;
    row->liquid0_id = (uint16_t)liquidId;
    row->liquid0_amount = 0.0f;
    memcpy(&row->liquid0_amount, &liquidAmountBits,
           sizeof(row->liquid0_amount));
    row->extra_size = (uint32_t)extraSize;
    row->state_hash = ((uint64_t)(uint32_t)stateHigh << 32)
        | (uint32_t)stateLow;
}

int m3ds_save_rows_end(void) {
    M3dsWorldTiles tiles = {0};
    int result = 1;
    if (g_rows && g_buildings
            && m3ds_world_rows_alloc(
                (uint32_t)g_width, (uint32_t)g_height, g_rows, g_tile_count,
                g_buildings, g_building_count, &tiles)) {
        char line[192];
        int length = snprintf(
            line, sizeof(line),
            "UPSTREAM_ROWS flat=%016llx tile=%016llx save=%016llx"
            " building=%016llx buildings=%u\n",
            (unsigned long long)tiles.hash,
            (unsigned long long)tiles.tile_state_hash,
            (unsigned long long)tiles.save_state_hash,
            (unsigned long long)tiles.building_hash,
            (unsigned)tiles.building_count);
        if (length > 0 && length < (int)sizeof(line)) {
            svcOutputDebugString(line, length);
        }
        if (g_verify_fixture) {
            result = tiles.hash == UINT64_C(0x783fbd80e415dcd7)
                    && tiles.tile_state_hash == UINT64_C(0xe35397041df8e5fe)
                    && tiles.save_state_hash == UINT64_C(0x3a375fd4f81c5680)
                    && tiles.building_count == 2u ? 0 : 1;
        } else {
            result = 0;
        }
        if (result == 0) {
            g_loaded_tiles = tiles;
            g_loaded_ready = true;
        } else {
            m3ds_msav_free_world(&tiles);
        }
    }
    release_rows();
    return result;
}

bool m3ds_teavm_world_loader_available(void) {
    return true;
}

bool m3ds_teavm_world_load(const char *save_path, M3dsWorldTiles *out) {
    if (!save_path || !out) {
        return false;
    }
    g_requested_save_path = save_path;
    int result = m3ds_teavm_main(0, NULL);
    g_requested_save_path = NULL;
    if (result != 0 || !g_loaded_ready) {
        return false;
    }
    *out = g_loaded_tiles;
    memset(&g_loaded_tiles, 0, sizeof(g_loaded_tiles));
    g_loaded_ready = false;
    return true;
}
