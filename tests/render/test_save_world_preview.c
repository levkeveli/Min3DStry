#include "render/save_world_preview.h"

#include <assert.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct MockBackend {
    unsigned rects;
    M3dsColor last;
} MockBackend;

static bool mock_draw_rect(
    void *user_data, float x, float y, float z, float w, float h,
    M3dsColor color) {
    (void)x;
    (void)y;
    (void)z;
    (void)w;
    (void)h;
    MockBackend *mock = (MockBackend *)user_data;
    assert(mock != NULL);
    ++mock->rects;
    mock->last = color;
    return true;
}

int main(void) {
    uint16_t floors[4] = {1u, 2u, 1u, 2u};
    uint16_t overlays[4] = {0u, 3u, 0u, 0u};
    uint16_t blocks[4] = {0u, 0u, 5u, 0u};
    uint8_t flags[4] = {0u};
    uint32_t building_index[4] = {UINT32_MAX, UINT32_MAX, 0u, UINT32_MAX};
    M3dsWorldTiles tiles = {
        .floor_ids = floors,
        .overlay_ids = overlays,
        .block_ids = blocks,
        .flags = flags,
        .building_index = building_index,
        .count = 4u,
        .width = 2u,
        .height = 2u,
    };
    MockBackend mock = {0};
    M3dsRenderBackend backend = {
        .user_data = &mock,
        .draw_rect = mock_draw_rect,
    };
    assert(m3ds_save_world_preview_render(&tiles, &backend, 8.0f, 8.0f, 96.0f, 64.0f));
    assert(mock.rects == 24u * 16u);
    assert(mock.last.a == 255u);
    assert(!m3ds_save_world_preview_render(NULL, &backend, 0, 0, 1, 1));
    assert(!m3ds_save_world_preview_render(&tiles, NULL, 0, 0, 1, 1));
    tiles.count = 0u;
    assert(!m3ds_save_world_preview_render(&tiles, &backend, 0, 0, 1, 1));
    return 0;
}
