#ifndef MIN3DSTRY_PLATFORM_MSAV_WORLD_H
#define MIN3DSTRY_PLATFORM_MSAV_WORLD_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct M3dsMsavWorld {
    uint32_t version;
    uint32_t width;
    uint32_t height;
    uint32_t floor_tiles;
    uint32_t block_records;
    uint32_t data_tiles;
    uint32_t entity_tiles;
    uint32_t entity_region_bytes;
    uint32_t entity_mapping;
    uint32_t entity_teams;
    uint32_t entity_team_blocks;
    uint32_t entity_records;
    uint64_t map_hash;
    uint64_t entity_hash;
    bool entities_partial;
    bool valid;
} M3dsMsavWorld;

/*
 * Walks the upstream save-region chain in an already inflated .msav stream and
 * reads the world width/height from the beginning of the map region. Region
 * contents other than the 4-byte lengths are skipped; no world is allocated.
 */
bool m3ds_msav_parse_world_header(
    const uint8_t *data, size_t size, M3dsMsavWorld *out);

#define M3DS_BUILDING_ITEMS_MAX 8u

typedef struct M3dsItemStack {
    uint16_t item_id;
    uint32_t amount;
} M3dsItemStack;

typedef struct M3dsBuildingState {
    uint32_t tile_index;
    uint32_t revision;
    uint32_t payload_size;
    uint8_t *payload;
    float health;
    uint32_t item_count;
    uint32_t extra_size;
    uint8_t team;
    uint8_t rotation;
    uint8_t enabled;
    uint8_t modules;
    bool partial;
    bool items_truncated;
    M3dsItemStack items[M3DS_BUILDING_ITEMS_MAX];
    uint32_t power_links;
    float power_status;
    uint32_t liquid_count;
    uint16_t liquid0_id;
    float liquid0_amount;
} M3dsBuildingState;

typedef struct M3dsWorldTiles {
    uint16_t *floor_ids;
    uint16_t *overlay_ids;
    uint16_t *block_ids;
    uint8_t *flags;
    uint32_t *building_index;
    M3dsBuildingState *buildings;
    size_t count;
    size_t building_count;
    uint64_t hash;
    uint64_t building_hash;
} M3dsWorldTiles;

/*
 * Additionally walks the map region's floor/block records and validates their
 * bounds, counting data tiles and center-building entity chunks. Entity bytes
 * are only bounds-checked, not decoded.
 */
bool m3ds_msav_parse_world(
    const uint8_t *data, size_t size, M3dsMsavWorld *out);

/*
 * Allocates flat floor/overlay/block/flag arrays for the whole map and fills
 * them from the validated RLE/block records. Returns false on allocation or
 * bounds failure; release with m3ds_msav_free_world.
 */
bool m3ds_msav_alloc_world(
    const uint8_t *data, size_t size, M3dsWorldTiles *out);

void m3ds_msav_free_world(M3dsWorldTiles *tiles);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_MSAV_WORLD_H */
