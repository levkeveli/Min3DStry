/* ArcNet Server.java at 7445105cd2683daf5b9ac7033ebb07265d3e814d. */
#include "net/mindustry_host_transport.h"

static bool encode_registration_tcp(M3dsNetFrameworkId type, int32_t id, uint8_t *out) {
  M3dsNetFrameworkMsg message = {.type = type, .id = id};
  uint8_t payload[6];
  size_t size, framed;
  return m3ds_net_encode_framework(&message, payload, sizeof(payload), &size) &&
      m3ds_net_tcp_frame_encode(payload, size, out, M3DS_HOST_REGISTRATION_FRAME_SIZE, &framed) &&
      framed == M3DS_HOST_REGISTRATION_FRAME_SIZE;
}

bool m3ds_net_host_begin(M3dsNetHostPeer *peer, int32_t connection_id,
                        uint8_t out[M3DS_HOST_REGISTRATION_FRAME_SIZE]) {
  if (!peer || !encode_registration_tcp(M3DS_FW_REGISTER_TCP, connection_id, out)) {
    return false;
  }
  *peer = (M3dsNetHostPeer){.connection_id = connection_id};
  return true;
}

bool m3ds_net_host_register_udp(M3dsNetHostPeer *peer, const uint8_t *data, size_t size,
                               uint8_t out[M3DS_HOST_REGISTRATION_FRAME_SIZE]) {
  M3dsNetFrameworkMsg message;
  if (!peer || peer->registered || size != 6U ||
      !m3ds_net_decode_framework(data, size, &message) || message.type != M3DS_FW_REGISTER_UDP ||
      message.id != peer->connection_id || !encode_registration_tcp(M3DS_FW_REGISTER_UDP, 0, out)) {
    return false;
  }
  peer->registered = true;
  return true;
}

M3dsNetHostFrameResult m3ds_net_host_tcp_payload(const uint8_t *data, size_t size,
                                               const uint8_t **payload, size_t *payload_size,
                                               size_t *consumed) {
  if (!payload || !payload_size || !consumed) return M3DS_HOST_FRAME_INVALID;
  *payload = NULL;
  *payload_size = *consumed = 0;
  if (size < 2U) return M3DS_HOST_FRAME_INCOMPLETE;
  uint16_t length;
  if (!m3ds_net_tcp_frame_peek_len(data, size, &length) || !length || length > M3DS_HOST_MAX_INCOMING_FRAME) {
    return M3DS_HOST_FRAME_INVALID;
  }
  size_t total = 2U + (size_t)length;
  if (size < total) return M3DS_HOST_FRAME_INCOMPLETE;
  *payload = data + 2U;
  *payload_size = length;
  *consumed = total;
  return M3DS_HOST_FRAME_READY;
}
