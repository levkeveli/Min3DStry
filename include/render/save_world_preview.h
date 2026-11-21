#ifndef MIN3DSTRY_RENDER_SAVE_WORLD_PREVIEW_H
#define MIN3DSTRY_RENDER_SAVE_WORLD_PREVIEW_H

#include <stdbool.h>

#include "platform/msav_world.h"
#include "render/scene_2d.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Draws a bounded 24x16 preview of an allocated save world, one rect per
 * sampled tile. This is a runtime visualization of the loaded tile arrays,
 * not an upstream minimap implementation.
 */
bool m3ds_save_world_preview_render(
    const M3dsWorldTiles *tiles,
    const M3dsRenderBackend *backend,
    float x,
    float y,
    float width,
    float height);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_RENDER_SAVE_WORLD_PREVIEW_H */
