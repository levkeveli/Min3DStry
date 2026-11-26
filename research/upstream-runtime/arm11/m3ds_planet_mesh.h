#pragma once

#include <stdint.h>

int32_t m3ds_planet_draw(int32_t planet, const float *matrix, const float *lighting,
    const float *overlay, int32_t vertices, int32_t sector_vertices, const int32_t *surface, int32_t faces);
void m3ds_planet_release(void);
void m3ds_planet_draw_sector_markers(int32_t planet, const float *matrix,
    const int32_t *ids, const float *planes, int32_t markers);
int32_t m3ds_planet_draw_atmosphere(int32_t planet, const float *matrix,
    const float *camera, const float *parameters);
int32_t m3ds_planet_draw_orbits(const float *matrix, const float *overlay, int32_t vertices);
