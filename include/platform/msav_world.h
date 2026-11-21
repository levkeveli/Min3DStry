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

/*
 * Additionally walks the map region's floor/block records and validates their
 * bounds, counting data tiles and center-building entity chunks. Entity bytes
 * are only bounds-checked, not decoded.
 */
bool m3ds_msav_parse_world(
    const uint8_t *data, size_t size, M3dsMsavWorld *out);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_MSAV_WORLD_H */
