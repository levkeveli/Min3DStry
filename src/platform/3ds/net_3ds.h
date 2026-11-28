/*
 * Min3DStry libctru SOC networking transport header.
 * Provenance: Mindustry v8 build 160.8 (19a0f40d44514f4802a8b79770456e7e32e34da0, GPLv3)
 * and Arc (7445105cd2683daf5b9ac7033ebb07265d3e814d, Apache-2.0).
 */
#ifndef MIN3DSTRY_PLATFORM_3DS_NET_3DS_H
#define MIN3DSTRY_PLATFORM_3DS_NET_3DS_H

#include "net/mindustry_net.h"

typedef struct M3dsNet3dsContext {
  int tcp_fd;
  int udp_fd;
  bool soc_initialized;
  void *soc_buffer;
} M3dsNet3dsContext;

/* Initialize libctru SOC service on-demand and bind transport callbacks. */
bool m3ds_net_3ds_init(M3dsNetTransport *transport, M3dsNet3dsContext *ctx);

/* Send the stock Arc discovery query to the LAN broadcast and multicast endpoints. */
bool m3ds_net_3ds_discover(M3dsNet3dsContext *ctx, uint16_t port, const char *group, uint16_t multicast_port);

/* Receive an advertisement and its four IPv4 bytes, or return M3DS_NET_IO_TIMEOUT. */
int m3ds_net_3ds_read_advertisement(M3dsNet3dsContext *ctx, uint8_t *packet, size_t capacity, uint8_t peer[4]);

/* Shut down open sockets and libctru SOC service cleanly. */
void m3ds_net_3ds_exit(M3dsNet3dsContext *ctx);

#endif
