#ifndef M3DS_COMMUNITY_HTTP_H
#define M3DS_COMMUNITY_HTTP_H

#include <stdint.h>

int32_t m3ds_community_http_start(const uint8_t *url, int32_t length);

/* Returns zero while pending, a positive size on success, or a negative value on failure. */
int32_t m3ds_community_http_poll(uint8_t *output, int32_t capacity);

#endif
