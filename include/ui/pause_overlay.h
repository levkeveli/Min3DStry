#ifndef MIN3DSTRY_UI_PAUSE_OVERLAY_H
#define MIN3DSTRY_UI_PAUSE_OVERLAY_H

#include "render/scene_2d.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct M3dsPauseOverlayStats {
    uint32_t rects;
    uint32_t lines;
} M3dsPauseOverlayStats;

/*
 * Draws a bounded top-screen pause treatment: a dimmed scene, a centered
 * panel, a border and two pause bars. It is a HUD prototype, not the upstream
 * menu art.
 */
bool m3ds_pause_overlay_render(
    const M3dsRenderBackend *backend,
    const M3dsViewport *viewport,
    bool paused,
    M3dsPauseOverlayStats *stats);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_UI_PAUSE_OVERLAY_H */
