#ifndef M3DS_COMMUNITY_PROBE_H
#define M3DS_COMMUNITY_PROBE_H

#include <stdint.h>

#define M3DS_COMMUNITY_PROBE_SLOTS 8

int32_t m3ds_community_probe_open(void);
int32_t m3ds_community_probe_start(int32_t slot, const uint8_t *host, int32_t length, int32_t port);
int32_t m3ds_community_probe_poll(int32_t slot, uint8_t *packet, int32_t capacity,
                                 int32_t metadata[2], uint8_t *target, int32_t target_capacity);
void m3ds_community_probe_close(void);

#endif
