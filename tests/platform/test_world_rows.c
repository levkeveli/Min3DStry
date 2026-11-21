#include "platform/world_rows.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "platform/msav_inflate.h"
#include "platform/msav_world.h"

static void build_rows(
    const M3dsWorldTiles *source,
    M3dsWorldTileRow **out_tiles,
    M3dsWorldBuildingRow **out_buildings) {
    M3dsWorldTileRow *tiles = calloc(source->count, sizeof(*tiles));
    M3dsWorldBuildingRow *buildings = calloc(
        source->building_count > 0u ? source->building_count : 1u,
        sizeof(*buildings));
    assert(tiles != NULL && buildings != NULL);
    for (size_t i = 0; i < source->count; ++i) {
        tiles[i].floor_id = source->tile_states[i].floor_id;
        tiles[i].overlay_id = source->tile_states[i].overlay_id;
        tiles[i].block_id = source->tile_states[i].block_id;
        tiles[i].x = source->tile_states[i].x;
        tiles[i].y = source->tile_states[i].y;
        tiles[i].flags = source->tile_states[i].flags;
        tiles[i].building_index = source->tile_states[i].building_index;
    }
    for (size_t i = 0; i < source->building_count; ++i) {
        const M3dsBuildingState *from = &source->buildings[i];
        M3dsWorldBuildingRow *to = &buildings[i];
        to->tile_index = from->tile_index;
        to->block_id = from->block_id;
        to->team = from->team;
        to->rotation = from->rotation;
        to->enabled = from->enabled;
        to->modules = from->modules;
        to->partial = from->partial ? 1u : 0u;
        to->health = from->health;
        to->item_count = from->item_count;
        memcpy(to->items, from->items, sizeof(to->items));
        to->power_links = from->power_links;
        to->power_status = from->power_status;
        to->liquid_count = from->liquid_count;
        to->liquid0_id = from->liquid0_id;
        to->liquid0_amount = from->liquid0_amount;
        to->extra_size = from->extra_size;
        to->items_truncated = from->items_truncated;
        to->state_hash = from->state_hash;
    }
    *out_tiles = tiles;
    *out_buildings = buildings;
}

static void test_real_fixture_bridge(void) {
    FILE *file = fopen("tests/fixtures/glacier-v13.msav", "rb");
    assert(file != NULL);
    assert(fseek(file, 0L, SEEK_END) == 0);
    long length = ftell(file);
    assert(length > 0);
    rewind(file);
    uint8_t *raw = malloc((size_t)length);
    assert(raw != NULL);
    assert(fread(raw, 1u, (size_t)length, file) == (size_t)length);
    assert(fclose(file) == 0);

    uint8_t *inflated = NULL;
    size_t inflated_size = 0u;
    bool too_large = false;
    assert(m3ds_msav_inflate_all(
        raw, (size_t)length, &inflated, &inflated_size, 8u * 1024u * 1024u,
        &too_large));
    assert(!too_large);

    M3dsWorldTiles source;
    assert(m3ds_msav_alloc_world(inflated, inflated_size, &source));

    M3dsWorldTileRow *tiles = NULL;
    M3dsWorldBuildingRow *buildings = NULL;
    build_rows(&source, &tiles, &buildings);

    M3dsWorldTiles bridged;
    assert(m3ds_world_rows_alloc(
        150u, 250u, tiles, source.count, buildings, source.building_count,
        &bridged));
    assert(bridged.count == 37500u);
    assert(bridged.building_count == 2u);
    assert(bridged.width == 150u && bridged.height == 250u);
    assert(bridged.hash == UINT64_C(0x783fbd80e415dcd7));
    assert(bridged.tile_state_hash == UINT64_C(0xe35397041df8e5fe));
    assert(bridged.save_state_hash == UINT64_C(0x3a375fd4f81c5680));
    // Raw entity payload bytes are not part of the upstream row stream.
    assert(bridged.building_hash == 0u);
    assert(bridged.buildings[0].state_hash == UINT64_C(0xb529451197a6e504));
    assert(bridged.buildings[1].state_hash == UINT64_C(0xebc4778e833eb8e5));
    assert(bridged.buildings[0].tile == &bridged.tile_states[81u + 26u * 150u]);
    assert(bridged.buildings[0].modules == 9u);
    assert(m3ds_msav_world_bytes(&bridged) > 1000000u);

    m3ds_msav_free_world(&bridged);
    m3ds_msav_free_world(&source);
    free(buildings);
    free(tiles);
    free(inflated);
    free(raw);
}

static void test_invalid_rows(void) {
    M3dsWorldTileRow row = {
        .floor_id = 1u,
        .overlay_id = 0u,
        .block_id = 2u,
        .x = 0u,
        .y = 0u,
        .flags = 0u,
        .building_index = UINT32_MAX,
    };
    M3dsWorldTiles tiles;

    assert(!m3ds_world_rows_alloc(0u, 1u, &row, 1u, NULL, 0u, &tiles));
    assert(!m3ds_world_rows_alloc(2u, 1u, &row, 1u, NULL, 0u, &tiles));
    assert(!m3ds_world_rows_alloc(1u, 1u, NULL, 1u, NULL, 0u, &tiles));
    assert(!m3ds_world_rows_alloc(1u, 1u, &row, 1u, NULL, 0u, NULL));

    row.x = 1u;
    assert(!m3ds_world_rows_alloc(1u, 1u, &row, 1u, NULL, 0u, &tiles));
    row.x = 0u;
    row.building_index = 1u;
    assert(!m3ds_world_rows_alloc(1u, 1u, &row, 1u, NULL, 0u, &tiles));

    row.building_index = UINT32_MAX;
    assert(m3ds_world_rows_alloc(1u, 1u, &row, 1u, NULL, 0u, &tiles));
    assert(tiles.count == 1u && tiles.building_count == 0u);
    assert(tiles.tile_state_hash != 0u);
    m3ds_msav_free_world(&tiles);
}

int main(void) {
    test_real_fixture_bridge();
    test_invalid_rows();
    return 0;
}
