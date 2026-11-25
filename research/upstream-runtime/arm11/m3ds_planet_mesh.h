#pragma once

#include <stdint.h>

int32_t m3ds_planet_draw(int32_t planet, const float *matrix, const float *lighting);
void m3ds_planet_release(void);
