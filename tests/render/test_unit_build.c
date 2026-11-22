#include <assert.h>
#include <stdio.h>
#include "m3ds_unit_build.h"

int main(void) {
    const float accent[] = {0.5f, 1.0f, 0.0f, 1.0f};
    const uint8_t opaque[] = {255, 64, 128, 192};
    const uint8_t transparent[] = {0, 64, 128, 192};
    const uint8_t partial[] = {128, 64, 128, 192};
    uint8_t out[4];
    m3ds_shade_unit_build_pixel(out, opaque, 0.25f, accent, 1);
    assert(out[0] == 96 && out[1] == 160 && out[2] == 64 && out[3] == 224);
    m3ds_shade_unit_build_pixel(out, opaque, 0.25f, accent, 0);
    assert(out[0] == 64 && out[1] == 64 && out[2] == 128 && out[3] == 192);
    m3ds_shade_unit_build_pixel(out, transparent, 1, accent, 1);
    assert(out[0] == 0 && out[1] == 64 && out[2] == 128 && out[3] == 192);
    m3ds_shade_unit_build_pixel(out, partial, 0.5f, accent, 0);
    assert(out[0] == 32);

    const float phases[] = {0, 1.3f, -2, 12, -17};
    for (unsigned p = 0; p < sizeof(phases) / sizeof(phases[0]); ++p) {
        float edges[6];
        int count = m3ds_unit_build_edges(phases[p], edges);
        assert(count >= 2 && count <= 5);
        assert(edges[0] == 0 && edges[count - 1] == 1);
        for (int i = 0; i + 1 < count; ++i) {
            const float start = edges[i], end = edges[i + 1];
            assert(end > start);
            const float a = start + 0.1f * (end - start);
            const float b = start + 0.9f * (end - start);
            assert((fabsf(sinf(2 * a + phases[p])) <= 0.9f)
                == (fabsf(sinf(2 * b + phases[p])) <= 0.9f));
        }
    }
    assert(m3ds_unit_build_texel_offset(0, 0, 16) == 0);
    assert(m3ds_unit_build_texel_offset(7, 7, 16) == 252);
    assert(m3ds_unit_build_texel_offset(8, 0, 16) == 256);
    assert(m3ds_unit_build_texel_offset(0, 8, 16) == 512);
    puts("Stock unit construction shader checks passed.");
    return 0;
}
