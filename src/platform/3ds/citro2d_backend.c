#include "citro2d_backend.h"

#include <stddef.h>

static u32 to_c2d_color(M3dsColor c) {
    return C2D_Color32(c.r, c.g, c.b, c.a);
}

static bool c2d_clear(void *user_data, M3dsColor color) {
    M3dsCitro2dContext *ctx = (M3dsCitro2dContext *)user_data;
    if (!ctx || !ctx->target) {
        return false;
    }
    C2D_TargetClear(ctx->target, to_c2d_color(color));
    C2D_SceneBegin(ctx->target);
    return true;
}

static bool c2d_draw_rect(
    void *user_data, float x, float y, float z, float w, float h, M3dsColor color) {
    (void)user_data;
    return C2D_DrawRectSolid(x, y, z, w, h, to_c2d_color(color));
}

static bool c2d_draw_line(
    void *user_data, float x0, float y0, float x1, float y1,
    float thickness, float z, M3dsColor color) {
    (void)user_data;
    u32 clr = to_c2d_color(color);
    return C2D_DrawLine(x0, y0, clr, x1, y1, clr, thickness, z);
}

static bool c2d_draw_triangle(
    void *user_data, float x0, float y0, float x1, float y1,
    float x2, float y2, float z, M3dsColor color) {
    (void)user_data;
    u32 clr = to_c2d_color(color);
    return C2D_DrawTriangle(x0, y0, clr, x1, y1, clr, x2, y2, clr, z);
}

static bool c2d_draw_circle(
    void *user_data, float cx, float cy, float z, float radius, M3dsColor color) {
    (void)user_data;
    return C2D_DrawCircleSolid(cx, cy, z, radius, to_c2d_color(color));
}

void m3ds_citro2d_backend_init(
    M3dsRenderBackend *backend,
    M3dsCitro2dContext *ctx,
    C3D_RenderTarget *target) {
    if (!backend || !ctx) {
        return;
    }
    ctx->target = target;
    backend->user_data = ctx;
    backend->clear = c2d_clear;
    backend->draw_rect = c2d_draw_rect;
    backend->draw_line = c2d_draw_line;
    backend->draw_triangle = c2d_draw_triangle;
    backend->draw_circle = c2d_draw_circle;
}
