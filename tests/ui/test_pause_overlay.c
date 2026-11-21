#include "ui/pause_overlay.h"

#include <assert.h>
#include <math.h>
#include <string.h>

typedef struct {
    int rects;
    int lines;
    float first_rect[4];
    M3dsColor first_rect_color;
    float last_rect[4];
} MockStats;

static bool mock_rect(
    void *raw, float x, float y, float z, float w, float h, M3dsColor color) {
    (void)z;
    MockStats *stats = (MockStats *)raw;
    if (stats->rects == 0) {
        stats->first_rect[0] = x;
        stats->first_rect[1] = y;
        stats->first_rect[2] = w;
        stats->first_rect[3] = h;
        stats->first_rect_color = color;
    }
    stats->last_rect[0] = x;
    stats->last_rect[1] = y;
    stats->last_rect[2] = w;
    stats->last_rect[3] = h;
    stats->rects++;
    return true;
}

static bool mock_line(
    void *raw, float x0, float y0, float x1, float y1, float thickness, float z, M3dsColor color) {
    (void)x0; (void)y0; (void)x1; (void)y1; (void)thickness; (void)z; (void)color;
    MockStats *stats = (MockStats *)raw;
    stats->lines++;
    return true;
}

static M3dsRenderBackend mock_backend(MockStats *stats) {
    M3dsRenderBackend backend;
    memset(&backend, 0, sizeof(backend));
    backend.user_data = stats;
    backend.draw_rect = mock_rect;
    backend.draw_line = mock_line;
    return backend;
}

static void test_paused_overlay(void) {
    MockStats stats;
    memset(&stats, 0, sizeof(stats));
    M3dsRenderBackend backend = mock_backend(&stats);
    M3dsViewport viewport = {400.0f, 240.0f};
    M3dsPauseOverlayStats overlay;
    assert(m3ds_pause_overlay_render(&backend, &viewport, true, &overlay));
    assert(overlay.rects == 4u);
    assert(overlay.lines == 4u);
    assert(stats.rects == 4);
    assert(stats.lines == 4);
    // The first rect dims the whole screen.
    assert(stats.first_rect[0] == 0.0f && stats.first_rect[1] == 0.0f);
    assert(stats.first_rect[2] == 400.0f && stats.first_rect[3] == 240.0f);
    assert(stats.first_rect_color.a > 0 && stats.first_rect_color.r == 0);
    // The last two bars sit near the panel center.
    assert(stats.last_rect[2] == 10.0f && stats.last_rect[3] > 0.0f);
    assert(stats.last_rect[0] > 200.0f);
}

static void test_unpaused_and_invalid(void) {
    MockStats stats;
    memset(&stats, 0, sizeof(stats));
    M3dsRenderBackend backend = mock_backend(&stats);
    M3dsViewport viewport = {400.0f, 240.0f};
    M3dsPauseOverlayStats overlay;
    assert(m3ds_pause_overlay_render(&backend, &viewport, false, &overlay));
    assert(overlay.rects == 0u && overlay.lines == 0u);
    assert(stats.rects == 0 && stats.lines == 0);

    assert(!m3ds_pause_overlay_render(NULL, &viewport, true, NULL));
    assert(!m3ds_pause_overlay_render(&backend, NULL, true, NULL));
    M3dsViewport tiny = {16.0f, 16.0f};
    assert(!m3ds_pause_overlay_render(&backend, &tiny, true, NULL));

    M3dsRenderBackend missing = backend;
    missing.draw_line = NULL;
    assert(!m3ds_pause_overlay_render(&missing, &viewport, true, NULL));
}

int main(void) {
    test_paused_overlay();
    test_unpaused_and_invalid();
    return 0;
}
