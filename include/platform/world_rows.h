#ifndef MIN3DSTRY_PLATFORM_WORLD_ROWS_H
#define MIN3DSTRY_PLATFORM_WORLD_ROWS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "platform/msav_world.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Neutral decoded rows for the runtime world bridge. The upstream SaveIO/MapIO
 * loader fills these rows; m3ds_world_rows_alloc turns them into the resident
 * M3dsWorldTiles arrays used by the HUD and preview. No .msav parsing happens
 * here.
 */

typedef struct M3dsWorldTileRow {
    uint16_t floor_id;
    uint16_t overlay_id;
    uint16_t block_id;
    uint32_t x;
    uint32_t y;
    uint8_t flags;
    uint32_t building_index;
} M3dsWorldTileRow;

typedef struct M3dsWorldBuildingRow {
    uint32_t tile_index;
    uint16_t block_id;
    uint8_t team;
    uint8_t rotation;
    uint8_t enabled;
    uint8_t modules;
    uint8_t partial;
    float health;
    uint32_t item_count;
    M3dsItemStack items[M3DS_BUILDING_ITEMS_MAX];
    uint32_t power_links;
    float power_status;
    uint32_t liquid_count;
    uint16_t liquid0_id;
    float liquid0_amount;
    uint32_t extra_size;
    bool items_truncated;
    uint64_t state_hash;
} M3dsWorldBuildingRow;

/*
 * Validates and copies width*height tile rows and building rows into a newly
 * allocated world. Tile rows must be in row-major order and their x/y must
 * match their index. Each building row's tile_index must be a tile whose
 * building_index points back at that row. Returns false on invalid input or
 * allocation failure; release with m3ds_msav_free_world.
 */
bool m3ds_world_rows_alloc(
    uint32_t width,
    uint32_t height,
    const M3dsWorldTileRow *rows,
    size_t row_count,
    const M3dsWorldBuildingRow *buildings,
    size_t building_count,
    M3dsWorldTiles *out);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_WORLD_ROWS_H */
