#pragma once

#include <stdint.h>

/** Open the server address keyboard. Return UTF-8 byte count, zero on cancel, or -1 on applet failure. */
int32_t m3ds_read_server_address(char *text, int32_t capacity, int32_t invalid);
