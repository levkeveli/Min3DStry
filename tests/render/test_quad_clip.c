#include "m3ds_quad_clip.h"

#include <assert.h>
#include <math.h>

// Use a UV affine mapping with distinct slopes for position and vertex color.
static M3DS_QuadVertex vertex(float u, float v) {
    return (M3DS_QuadVertex){2 * u - 200, 200 - 3 * v, u, v,
                            {(u - 120) / 24, (v - 120) / 24, 0.25f, 1}};
}

static float area(const M3DS_QuadVertex *polygon, int count) {
    float twice = 0;
    for (int i = 0; i < count; i++) {
        M3DS_QuadVertex a = polygon[i], b = polygon[(i + 1) % count];
        twice += a.x * b.y - b.x * a.y;
    }
    return twice * 0.5f;
}

int main(void) {
    M3DS_QuadVertex triangle[] = {vertex(120, 120), vertex(144, 120), vertex(120, 144)};
    M3DS_QuadVertex piece[M3DS_QUAD_MAX_VERTICES];
    float combined = 0;
    int pieces = 0;
    for (int top = 0; top <= 128; top += 128) {
        for (int left = 0; left <= 128; left += 128) {
            int count = m3ds_quad_clip_triangle(triangle, piece, left, top, left + 128, top + 128);
            assert(count >= 3 && count <= 7);
            for (int i = 0; i < count; i++) {
                M3DS_QuadVertex v = piece[i], expected = vertex(v.u, v.v);
                assert(v.u >= left && v.u <= left + 128 && v.v >= top && v.v <= top + 128);
                assert(fabsf(v.x - expected.x) < 0.001f && fabsf(v.y - expected.y) < 0.001f);
                for (int c = 0; c < 4; c++) assert(fabsf(v.color[c] - expected.color[c]) < 0.00001f);
            }
            assert(area(piece, count) < 0);
            combined += area(piece, count);
            pieces++;
        }
    }
    assert(pieces == 4 && fabsf(combined - area(triangle, 3)) < 0.01f);
    assert(m3ds_quad_clip_triangle(triangle, piece, 256, 256, 384, 384) == 0);
    assert(m3ds_quad_clip_triangle(triangle, piece, 0, 0, 256, 256) == 3);
    return 0;
}
