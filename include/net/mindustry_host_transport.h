#ifndef MIN3DSTRY_NET_MINDUSTRY_HOST_TRANSPORT_H
#define MIN3DSTRY_NET_MINDUSTRY_HOST_TRANSPORT_H

#include "mindustry_net.h"

#define M3DS_HOST_REGISTRATION_FRAME_SIZE 8U
/* ArcNetProvider's server object buffer size. */
#define M3DS_HOST_MAX_INCOMING_FRAME 16384U

typedef struct M3dsNetHostPeer {
  int32_t connection_id;
  bool registered;
} M3dsNetHostPeer;

typedef enum M3dsNetHostFrameResult {
  M3DS_HOST_FRAME_INCOMPLETE = 0,
  M3DS_HOST_FRAME_READY,
  M3DS_HOST_FRAME_INVALID
} M3dsNetHostFrameResult;

/* Assign the ArcNet connection ID and write the first TCP frame. */
bool m3ds_net_host_begin(M3dsNetHostPeer *peer, int32_t connection_id,
                        uint8_t out[M3DS_HOST_REGISTRATION_FRAME_SIZE]);

/* Called after matching the UDP sender to the pending TCP peer. The reply travels over TCP. */
bool m3ds_net_host_register_udp(M3dsNetHostPeer *peer, const uint8_t *data, size_t size,
                               uint8_t out[M3DS_HOST_REGISTRATION_FRAME_SIZE]);

/* Return a borrowed first payload; the socket adapter retains incomplete data. */
M3dsNetHostFrameResult m3ds_net_host_tcp_payload(const uint8_t *data, size_t size,
                                               const uint8_t **payload, size_t *payload_size,
                                               size_t *consumed);

#endif
