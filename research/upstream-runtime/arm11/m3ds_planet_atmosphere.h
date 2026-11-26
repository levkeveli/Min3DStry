#ifndef M3DS_PLANET_ATMOSPHERE_H
#define M3DS_PLANET_ATMOSPHERE_H

#include <stdbool.h>

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

#endif
