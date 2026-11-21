#include "ui/pause_overlay.h"

#include <math.h>

static bool draw_rect(
    const M3dsRenderBackend *backend, float x, float y, float z, float w, float h, M3dsColor color) {
    return backend->draw_rect(backend->user_data, x, y, z, w, h, color);
}

static bool draw_line(
    const M3dsRenderBackend *backend, float x0, float y0, float x1, float y1,
    float thickness, float z, M3dsColor color) {
    return backend->draw_line(backend->user_data, x0, y0, x1, y1, thickness, z, color);
}

bool m3ds_pause_overlay_render(
    const M3dsRenderBackend *backend,
    const M3dsViewport *viewport,
    bool paused,
    M3dsPauseOverlayStats *stats) {
    if (stats) {
        stats->rects = 0u;
        stats->lines = 0u;
    }
    if (!backend || !viewport || !backend->draw_rect || !backend->draw_line
            || viewport->width < 32.0f || viewport->height < 32.0f) {
        return false;
    }
    if (!paused) {
        return true;
    }

    const float panel_w = fminf(200.0f, viewport->width - 32.0f);
    const float panel_h = fminf(64.0f, viewport->height - 32.0f);
    const float panel_x = (viewport->width - panel_w) * 0.5f;
    const float panel_y = (viewport->height - panel_h) * 0.5f;
    const M3dsColor dim = m3ds_rgba(0, 0, 0, 120);
    const M3dsColor panel = m3ds_rgba(28, 33, 42, 232);
    const M3dsColor accent = m3ds_rgba(250, 213, 107, 255);

    if (!draw_rect(backend, 0.0f, 0.0f, 0.85f, viewport->width, viewport->height, dim) ||
        !draw_rect(backend, panel_x, panel_y, 0.9f, panel_w, panel_h, panel)) {
        return false;
    }
    if (stats) stats->rects += 2u;

    if (!draw_line(backend, panel_x, panel_y, panel_x + panel_w, panel_y, 2.0f, 0.91f, accent) ||
        !draw_line(backend, panel_x + panel_w, panel_y, panel_x + panel_w, panel_y + panel_h,
                   2.0f, 0.91f, accent) ||
        !draw_line(backend, panel_x + panel_w, panel_y + panel_h, panel_x, panel_y + panel_h,
                   2.0f, 0.91f, accent) ||
        !draw_line(backend, panel_x, panel_y + panel_h, panel_x, panel_y, 2.0f, 0.91f, accent)) {
        return false;
    }
    if (stats) stats->lines += 4u;

    const float bar_w = 10.0f;
    const float bar_h = fminf(32.0f, panel_h - 16.0f);
    const float bar_y = panel_y + (panel_h - bar_h) * 0.5f;
    const float center = panel_x + panel_w * 0.5f;
    if (!draw_rect(backend, center - bar_w - 5.0f, bar_y, 0.92f, bar_w, bar_h, accent) ||
        !draw_rect(backend, center + 5.0f, bar_y, 0.92f, bar_w, bar_h, accent)) {
        return false;
    }
    if (stats) stats->rects += 2u;
    return true;
}
