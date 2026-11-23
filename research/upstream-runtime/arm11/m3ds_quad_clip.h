#pragma once

#include <stdbool.h>
#include <stdint.h>

// Arc masks the low alpha bit in float colors, so 254 represents full opacity.
static inline uint32_t m3ds_quad_alpha(uint32_t color) {
    uint32_t alpha = color >> 24;
    return alpha < 254 ? alpha : 254;
}

static inline uint32_t m3ds_quad_expand_alpha(uint32_t color) {
    return (color & 0x00ffffffu) | ((m3ds_quad_alpha(color) * 255 / 254) << 24);
}

enum { M3DS_QUAD_MAX_VERTICES = 8 };

typedef struct {
    float x, y, u, v;
    float color[4];
} M3DS_QuadVertex;

static inline float m3ds_quad_plane(M3DS_QuadVertex vertex, bool vertical, float edge, bool upper) {
    float position = vertical ? vertex.v : vertex.u;
    return upper ? edge - position : position - edge;
}

static inline M3DS_QuadVertex m3ds_quad_intersect(M3DS_QuadVertex a, M3DS_QuadVertex b, float factor) {
    M3DS_QuadVertex vertex = {
        .x = a.x + (b.x - a.x) * factor,
        .y = a.y + (b.y - a.y) * factor,
        .u = a.u + (b.u - a.u) * factor,
        .v = a.v + (b.v - a.v) * factor,
    };
    for (int i = 0; i < 4; i++) vertex.color[i] = a.color[i] + (b.color[i] - a.color[i]) * factor;
    return vertex;
}

static inline int m3ds_quad_clip_plane(const M3DS_QuadVertex *in, int count, M3DS_QuadVertex *out,
                                       bool vertical, float edge, bool upper) {
    if (count == 0) return 0;
    int written = 0;
    M3DS_QuadVertex previous = in[count - 1];
    float before = m3ds_quad_plane(previous, vertical, edge, upper);
    for (int i = 0; i < count; i++) {
        M3DS_QuadVertex next = in[i];
        float after = m3ds_quad_plane(next, vertical, edge, upper);
        if ((before < 0 && after > 0) || (before > 0 && after < 0)) {
            M3DS_QuadVertex vertex = m3ds_quad_intersect(previous, next, before / (before - after));
            if (vertical) vertex.v = edge;
            else vertex.u = edge;
            out[written++] = vertex;
        }
        if (after >= 0) out[written++] = next;
        previous = next;
        before = after;
    }
    return written;
}

// A triangle intersected with a rectangle has at most seven vertices. Its
// convex result retains the original triangle's interpolation and winding.
static inline int m3ds_quad_clip_triangle(const M3DS_QuadVertex *triangle, M3DS_QuadVertex *out,
                                          float left, float top, float right, float bottom) {
    M3DS_QuadVertex scratch[M3DS_QUAD_MAX_VERTICES];
    int count = m3ds_quad_clip_plane(triangle, 3, scratch, false, left, false);
    count = m3ds_quad_clip_plane(scratch, count, out, false, right, true);
    count = m3ds_quad_clip_plane(out, count, scratch, true, top, false);
    return m3ds_quad_clip_plane(scratch, count, out, true, bottom, true);
}
