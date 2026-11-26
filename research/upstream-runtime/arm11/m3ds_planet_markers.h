#pragma once

#include <citro3d.h>
#include <stdint.h>

int32_t m3ds_planet_draw_markers(const C3D_Mtx *projection, const int32_t *ids, const float *planes, int32_t count);
void m3ds_planet_release_markers(void);
