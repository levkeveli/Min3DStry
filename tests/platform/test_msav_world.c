#include "platform/msav_world.h"

#include <assert.h>
#include <string.h>

static size_t put_be16(uint8_t *out, unsigned value) {
    out[0] = (uint8_t)(value >> 8);
    out[1] = (uint8_t)value;
    return 2u;
}

static size_t put_be32(uint8_t *out, unsigned value) {
    out[0] = (uint8_t)(value >> 24);
    out[1] = (uint8_t)(value >> 16);
    out[2] = (uint8_t)(value >> 8);
    out[3] = (uint8_t)value;
    return 4u;
}

static size_t put_region(
    uint8_t *out, size_t position, const uint8_t *data, size_t size) {
    position += put_be32(out + position, (unsigned)size);
    memcpy(out + position, data, size);
    return position + size;
}

static void test_v13_regions(void) {
    uint8_t data[128];
    size_t size = 0u;
    memcpy(data + size, "MSAV", 4);
    size += 4u;
    size += put_be32(data + size, 13u);
    const uint8_t meta[] = {1, 2, 3, 4};
    const uint8_t patches[] = {5, 6};
    const uint8_t content[] = {7, 8, 9};
    size = put_region(data, size, meta, sizeof(meta));
    size = put_region(data, size, patches, sizeof(patches));
    size = put_region(data, size, content, sizeof(content));
    size += put_be32(data + size, 6u);
    size += put_be16(data + size, 150u);
    size += put_be16(data + size, 250u);
    size += 2u;

    M3dsMsavWorld world;
    assert(m3ds_msav_parse_world_header(data, size, &world));
    assert(world.valid);
    assert(world.version == 13u);
    assert(world.width == 150u);
    assert(world.height == 250u);
}

static void test_v7_regions(void) {
    uint8_t data[96];
    size_t size = 0u;
    memcpy(data + size, "MSAV", 4);
    size += 4u;
    size += put_be32(data + size, 7u);
    const uint8_t meta[] = {1, 2};
    const uint8_t content[] = {3};
    size = put_region(data, size, meta, sizeof(meta));
    size = put_region(data, size, content, sizeof(content));
    size += put_be32(data + size, 4u);
    size += put_be16(data + size, 10u);
    size += put_be16(data + size, 20u);

    M3dsMsavWorld world;
    assert(m3ds_msav_parse_world_header(data, size, &world));
    assert(world.version == 7u);
    assert(world.width == 10u && world.height == 20u);
}

static void test_tiles_and_entities(void) {
    uint8_t data[256];
    size_t size = 0u;
    memcpy(data + size, "MSAV", 4);
    size += 4u;
    size += put_be32(data + size, 13u);
    const uint8_t meta[] = {1};
    const uint8_t patches[] = {2};
    const uint8_t content[] = {3};
    size = put_region(data, size, meta, sizeof(meta));
    size = put_region(data, size, patches, sizeof(patches));
    size = put_region(data, size, content, sizeof(content));

    uint8_t map[64];
    size_t map_size = 0u;
    map_size += put_be16(map + map_size, 2u);
    map_size += put_be16(map + map_size, 2u);
    map_size += put_be16(map + map_size, 1u);
    map_size += put_be16(map + map_size, 0u);
    map[map_size++] = 3u;

    map_size += put_be16(map + map_size, 1u);
    map[map_size++] = 4u;
    memset(map + map_size, 9, 7u);
    map_size += 7u;

    map_size += put_be16(map + map_size, 2u);
    map[map_size++] = 1u;
    map[map_size++] = 1u;
    map_size += put_be32(map + map_size, 3u);
    memset(map + map_size, 7, 3u);
    map_size += 3u;

    map_size += put_be16(map + map_size, 3u);
    map[map_size++] = 0u;
    map[map_size++] = 1u;

    size = put_region(data, size, map, map_size);
    const uint8_t entities[] = {1, 2, 3, 4, 5};
    size = put_region(data, size, entities, sizeof(entities));

    M3dsMsavWorld world;
    assert(m3ds_msav_parse_world(data, size, &world));
    assert(world.valid);
    assert(world.version == 13u);
    assert(world.width == 2u && world.height == 2u);
    assert(world.floor_tiles == 4u);
    assert(world.block_records == 3u);
    assert(world.data_tiles == 1u);
    assert(world.entity_tiles == 1u);
    assert(world.entity_region_bytes == 5u);
    assert(world.map_hash != 0u);
    assert(m3ds_msav_parse_world_header(data, size, &world));
    assert(world.valid && world.width == 2u && world.height == 2u);
}

static void test_malformed_tiles(void) {
    uint8_t data[128];
    M3dsMsavWorld world;
    memcpy(data, "MSAV", 4);
    put_be32(data + 4, 13u);
    const uint8_t meta[] = {1};
    const uint8_t patches[] = {2};
    const uint8_t content[] = {3};
    size_t base = 8u;
    base = put_region(data, base, meta, sizeof(meta));
    base = put_region(data, base, patches, sizeof(patches));
    base = put_region(data, base, content, sizeof(content));

    uint8_t map[32];
    size_t map_size = 0u;
    map_size += put_be16(map + map_size, 2u);
    map_size += put_be16(map + map_size, 2u);
    map_size += put_be16(map + map_size, 1u);
    map_size += put_be16(map + map_size, 0u);
    map[map_size++] = 255u;
    size_t size = put_region(data, base, map, map_size);
    assert(!m3ds_msav_parse_world(data, size, &world));

    map_size = 0u;
    map_size += put_be16(map + map_size, 1u);
    map_size += put_be16(map + map_size, 1u);
    map_size += put_be16(map + map_size, 1u);
    map_size += put_be16(map + map_size, 0u);
    map[map_size++] = 0u;
    map_size += put_be16(map + map_size, 1u);
    map[map_size++] = 1u;
    map[map_size++] = 1u;
    map_size += put_be32(map + map_size, 100u);
    size = put_region(data, base, map, map_size);
    assert(!m3ds_msav_parse_world(data, size, &world));
}

static void test_malformed(void) {
    uint8_t data[64];
    memset(data, 0, sizeof(data));
    M3dsMsavWorld world;
    assert(!m3ds_msav_parse_world_header(NULL, sizeof(data), &world));
    assert(!m3ds_msav_parse_world_header(data, sizeof(data), NULL));
    assert(!m3ds_msav_parse_world_header(data, 7u, &world));

    memcpy(data, "MSAV", 4);
    put_be32(data + 4, 13u);
    put_be32(data + 8, 1000u);
    assert(!m3ds_msav_parse_world_header(data, sizeof(data), &world));

    memcpy(data, "MSAV", 4);
    put_be32(data + 4, 13u);
    size_t size = 8u;
    const uint8_t meta[] = {1};
    const uint8_t patches[] = {2};
    const uint8_t content[] = {3};
    size = put_region(data, size, meta, sizeof(meta));
    size = put_region(data, size, patches, sizeof(patches));
    size = put_region(data, size, content, sizeof(content));
    size += put_be32(data + size, 4u);
    size += put_be16(data + size, 0u);
    size += put_be16(data + size, 250u);
    assert(!m3ds_msav_parse_world_header(data, size, &world));
}

int main(void) {
    test_v13_regions();
    test_v7_regions();
    test_tiles_and_entities();
    test_malformed_tiles();
    test_malformed();
    return 0;
}
