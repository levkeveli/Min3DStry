#ifndef M3DS_PLANET_ATMOSPHERE_H
#define M3DS_PLANET_ATMOSPHERE_H

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    float x, y, z;
} M3dsAtmosphereVector;

typedef struct {
    float inner_radius, outer_radius;
    float color[3];
    M3dsAtmosphereVector light;
} M3dsAtmosphere;

/* Eye and ray are in world axes relative to the planet center. Light and ray are unit vectors. */
bool m3ds_atmosphere_scatter(const M3dsAtmosphere *atmosphere, M3dsAtmosphereVector eye,
    M3dsAtmosphereVector ray, float result[3]);

/* Draw the scattering contribution in screen order, for additive RGB blending.
 * The three vectors describe the first pixel-center ray and its unnormalized steps. */
void m3ds_atmosphere_raster(const M3dsAtmosphere *atmosphere, M3dsAtmosphereVector eye,
    M3dsAtmosphereVector first_ray, M3dsAtmosphereVector pixel_dx, M3dsAtmosphereVector pixel_dy,
    unsigned width, unsigned height, uint8_t *rgb);

typedef struct {
    float x, y, z, u, v;
} M3dsAtmosphereVertex;

/* Project one stock mesh triangle into PICA clip space with W=1. Clip the
 * near plane first, then use affine framebuffer UVs. Returns 0, 3, or 6 vertices. */
unsigned m3ds_atmosphere_project_triangle(const M3dsAtmosphereVector triangle[3],
    const float matrix[16], float u_scale, float v_scale, M3dsAtmosphereVertex out[6]);

#endif
