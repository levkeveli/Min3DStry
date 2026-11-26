#include "m3ds_planet_atmosphere.h"

#include <assert.h>
#include <math.h>
#include <stdio.h>

static M3dsAtmosphereVector normalized(M3dsAtmosphereVector vector) {
    float length = sqrtf(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z);
    return (M3dsAtmosphereVector){vector.x / length, vector.y / length, vector.z / length};
}

static void expect_color(const M3dsAtmosphere *atmosphere, M3dsAtmosphereVector eye,
    M3dsAtmosphereVector ray, const float expected[3], float tolerance) {
    float actual[3];
    assert(m3ds_atmosphere_scatter(atmosphere, eye, normalized(ray), actual));
    for (int i = 0; i < 3; i++) {
        assert(isfinite(actual[i]));
        assert(fabsf(actual[i] - expected[i]) < tolerance);
    }
}

int main(void) {
    /* Reference values evaluate the pinned atmosphere.frag on the real planet colors and radii. */
    M3dsAtmosphere serpulo = {1.02f, 1.3f, {60.0f / 255, 27.0f / 255, 143.0f / 255},
        normalized((M3dsAtmosphereVector){0.3f, 0, 1})};
    M3dsAtmosphere erekir = {1.02f, 1.3f, {240.0f / 255, 114.0f / 255, 24.0f / 255}, {0, 0, -1}};
    const M3dsAtmosphereVector focused = {0, 0, 2.8f};
    const M3dsAtmosphereVector center = {0, 0, -1};

    expect_color(&serpulo, focused, center, (float[]){0.1040749882f, 0.0485136445f, 0.2291955511f}, 0.00002f);
    serpulo.light = (M3dsAtmosphereVector){1, 0, 0};
    expect_color(&serpulo, focused, (M3dsAtmosphereVector){0.445f, 0, -1},
        (float[]){0.1283381652f, 0.0617286392f, 0.2724422424f}, 0.00002f);
    expect_color(&erekir, focused, center, (float[]){0, 0, 0}, 0.00000001f);
    erekir.light = normalized((M3dsAtmosphereVector){1, 0.4f, 0.1f});
    expect_color(&erekir, (M3dsAtmosphereVector){0, 0, 180}, (M3dsAtmosphereVector){0.0064f, 0, -1},
        (float[]){0.3029533490f, 0.1637151672f, 0.0384702327f}, 0.0008f);

    float miss[3] = {1, 1, 1};
    assert(!m3ds_atmosphere_scatter(&serpulo, focused, (M3dsAtmosphereVector){1, 0, 0}, miss));
    assert(!m3ds_atmosphere_scatter(&serpulo, focused, (M3dsAtmosphereVector){0, 0, 1}, miss));
    assert(miss[0] == 0 && miss[1] == 0 && miss[2] == 0);
    puts("Stock atmosphere scattering reference passed.");
    return 0;
}
