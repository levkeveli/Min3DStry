#ifndef MIN3DSTRY_RENDER_MINIMAP_H
#define MIN3DSTRY_RENDER_MINIMAP_H

#include "render/scene_2d.h"
#include "sim/parity_scenario.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Bounded top-screen minimap prototype. Draws one cell per scene tile plus the
 * current camera rectangle. This is not yet the upstream HUD/minimap parity
 * implementation.
 */
bool m3ds_minimap_render(
    const M3dsBoundedScene *scene,
    const M3dsParityState *factory,
    const M3dsRenderBackend *backend,
    float x,
    float y,
    float width,
    float height);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_RENDER_MINIMAP_H */
