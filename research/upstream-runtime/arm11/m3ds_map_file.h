#pragma once

#include <stdint.h>

void* m3ds_map_open(void);
int32_t m3ds_map_read_byte(void* file);
int32_t m3ds_map_read(void* file, void* destination, int32_t length);
void m3ds_map_close(void* file);
