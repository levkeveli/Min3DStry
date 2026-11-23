#pragma once

#include <stdbool.h>
#include "m3ds_unit_build.h"

/* Pinned blockbuild.frag compares alpha at eight neighbors 3.5 texels
 * from the fragment. Sample at original texel centers for the 3DS atlas.
 * The padded border is transparent outside the original icon bounds. */
enum { M3DS_BLOCK_BUILD_BORDER = 4 };

static inline bool m3ds_block_build_contour(const uint8_t *center, int stride) {
    const int lo = -3, hi = 4;
    return center[lo] < 26 || center[hi] < 26
        || center[lo * stride] < 26 || center[hi * stride] < 26
        || center[lo * stride + lo] < 26 || center[lo * stride + hi] < 26
        || center[hi * stride + lo] < 26 || center[hi * stride + hi] < 26;
}

static inline void m3ds_shade_block_build_pixel(uint8_t *out, const uint8_t *source,
                                                const uint8_t *mask, int stride,
                                                float x, float y, int width, int height,
                                                float progress, float time, float alpha,
                                                const float vertex[4]) {
    const float phase = time / 1.5f + x + y;
    const float distance = (fabsf(width * 0.5f - x) + fabsf(height * 0.5f - y)) * 0.5f;
    const float threshold = (1.0f - progress) * (width * 0.5f);
    const bool outline = source[0] >= 26
        && ((phase - 20.0f * floorf(phase / 20.0f) < 15.0f && m3ds_block_build_contour(mask, stride))
            || (distance <= threshold && distance + 2.0f > threshold));
    for (int channel = 0; channel < 4; ++channel) {
        float value = source[channel] / 255.0f;
        if (outline) {
            value = vertex[channel] * vertex[0] + value * (1.0f - vertex[0]);
        } else if (distance <= threshold) {
            value = 0.0f;
        }
        out[channel] = m3ds_unit_build_byte(channel == 0 ? value * alpha : value);
    }
}
