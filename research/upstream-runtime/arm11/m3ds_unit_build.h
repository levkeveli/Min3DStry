#pragma once

#include <math.h>
#include <stdint.h>

#define M3DS_UNIT_BUILD_TEXTURE_LIMIT 128

typedef struct {
    float pivot_x;
    float width;
} M3dsUnitBuildSlice;

/* Citro2D translates by pos after rotating local offsets. Keep pos and
 * angle fixed, shifting -center.x to the start of this slice. */
static inline M3dsUnitBuildSlice m3ds_unit_build_slice(float pivot_x, float width, float from, float to) {
    return (M3dsUnitBuildSlice){ pivot_x - width * from, width * (to - from) };
}

/* GLSL math from pinned Mindustry core/assets/shaders/unitbuild.frag.
 * Tex3DS stores the RGBA8 channels in ABGR byte order. */
static inline uint8_t m3ds_unit_build_byte(float value) {
    return (uint8_t)(fminf(1.0f, fmaxf(0.0f, value)) * 255.0f + 0.5f);
}

static inline void m3ds_shade_unit_build_pixel(uint8_t *output, const uint8_t *input,
                                               float progress, const float accent[4], float stripe) {
    const float original_alpha = input[0] / 255.0f;
    float alpha = original_alpha * progress;
    const float mix = alpha > 0.01f ? stripe * accent[0] : 0.0f;
    alpha += (accent[0] - alpha) * mix;
    output[0] = m3ds_unit_build_byte(alpha * original_alpha);
    for (int channel = 1; channel < 4; ++channel) {
        const float source = input[channel] / 255.0f;
        output[channel] = m3ds_unit_build_byte(source + (accent[channel] - source) * mix);
    }
}

/* The shader tests abs(sin(2*x + phase)) against .9 at every fragment.
 * Split the original quad where that condition changes, even between texels. */
static inline int m3ds_unit_build_edges(float phase, float edges[6]) {
    const float pi = 3.14159265358979323846f;
    const float a = asinf(0.9f);
    const float crossings[] = { a, pi - a, pi + a, 2 * pi - a };
    float start = fmodf(phase, pi);
    if (start < 0.0f) start += pi;
    int count = 0;
    edges[count++] = 0.0f;
    for (int i = 0; i < 4; ++i) {
        const float x = (crossings[i] - start) * 0.5f;
        if (x > 0.0f && x < 1.0f) edges[count++] = x;
    }
    edges[count++] = 1.0f;
    return count;
}

static inline unsigned m3ds_unit_build_texel_offset(unsigned x, unsigned y, unsigned width) {
    const unsigned tile = (y / 8) * (width / 8) + x / 8;
    const unsigned sample = (x & 1) | (y & 1) << 1 | (x & 2) << 1 | (y & 2) << 2
        | (x & 4) << 2 | (y & 4) << 3;
    return 4 * (tile * 64 + sample);
}
