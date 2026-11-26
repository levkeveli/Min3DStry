#pragma once

#include <stdint.h>

int32_t m3ds_host_net_open(int32_t port, int32_t peers, uint32_t id_seed);
void m3ds_host_net_close(void);
/* details contains peer slot, IPv4 address in network byte order and payload bytes. */
int32_t m3ds_host_net_poll(void *payload, int32_t capacity, uint32_t *details);
int32_t m3ds_host_net_send(int32_t peer, const void *payload, int32_t size, int32_t reliable);
void m3ds_host_net_disconnect(int32_t peer);
int32_t m3ds_host_net_advertise(const void *payload, int32_t size);
