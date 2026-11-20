#include "net/mindustry_net.h"

#include <string.h>

static uint16_t read_u16_be(const uint8_t *p) {
  return (uint16_t)(((uint16_t)p[0] << 8U) | (uint16_t)p[1]);
}

static uint32_t read_u32_be(const uint8_t *p) {
  return ((uint32_t)p[0] << 24U) | ((uint32_t)p[1] << 16U) |
         ((uint32_t)p[2] << 8U) | (uint32_t)p[3];
}

static int32_t read_i32_be(const uint8_t *p) {
  return (int32_t)read_u32_be(p);
}

static uint64_t read_u64_be(const uint8_t *p) {
  return ((uint64_t)read_u32_be(p) << 32U) | (uint64_t)read_u32_be(p + 4U);
}

static void write_u16_be(uint8_t *p, uint16_t v) {
  p[0] = (uint8_t)((v >> 8U) & 0xFFU);
  p[1] = (uint8_t)(v & 0xFFU);
}

static void write_u32_be(uint8_t *p, uint32_t v) {
  p[0] = (uint8_t)((v >> 24U) & 0xFFU);
  p[1] = (uint8_t)((v >> 16U) & 0xFFU);
  p[2] = (uint8_t)((v >> 8U) & 0xFFU);
  p[3] = (uint8_t)(v & 0xFFU);
}

static void write_u64_be(uint8_t *p, uint64_t v) {
  write_u32_be(p, (uint32_t)((v >> 32U) & 0xFFFFFFFFU));
  write_u32_be(p + 4U, (uint32_t)(v & 0xFFFFFFFFU));
}

uint32_t m3ds_net_crc32(const uint8_t *data, size_t len) {
  if (!data && len > 0U) {
    return 0U;
  }
  uint32_t crc = 0xFFFFFFFFU;
  for (size_t i = 0U; i < len; ++i) {
    crc ^= (uint32_t)data[i];
    for (int bit = 0; bit < 8; ++bit) {
      uint32_t mask = (crc & 1U) ? 0xEDB88320U : 0U;
      crc = (crc >> 1U) ^ mask;
    }
  }
  return ~crc;
}

bool m3ds_net_lz4_decompress(
    const uint8_t *src,
    size_t src_len,
    uint8_t *dst,
    size_t dst_cap,
    size_t *out_len) {
  if (!src || !dst || !out_len) {
    return false;
  }
  size_t s = 0U;
  size_t d = 0U;

  while (s < src_len) {
    uint8_t token = src[s++];
    size_t lit_len = (size_t)(token >> 4U);
    if (lit_len == 15U) {
      uint8_t b = 0U;
      do {
        if (s >= src_len) {
          return false;
        }
        b = src[s++];
        if (lit_len > SIZE_MAX - (size_t)b) {
          return false;
        }
        lit_len += (size_t)b;
      } while (b == 255U);
    }

    if (lit_len > src_len - s || lit_len > dst_cap - d) {
      return false;
    }
    if (lit_len > 0U) {
      memcpy(dst + d, src + s, lit_len);
      s += lit_len;
      d += lit_len;
    }

    /* End of compressed block occurs right after the final literal run. */
    if (s == src_len) {
      break;
    }

    if (src_len - s < 2U) {
      return false;
    }
    uint16_t offset = (uint16_t)((uint16_t)src[s] | ((uint16_t)src[s + 1U] << 8U));
    s += 2U;
    if (offset == 0U || (size_t)offset > d) {
      return false;
    }

    size_t match_len = (size_t)(token & 0x0FU);
    if (match_len == 15U) {
      uint8_t b = 0U;
      do {
        if (s >= src_len) {
          return false;
        }
        b = src[s++];
        if (match_len > SIZE_MAX - (size_t)b) {
          return false;
        }
        match_len += (size_t)b;
      } while (b == 255U);
    }
    if (match_len > SIZE_MAX - 4U) {
      return false;
    }
    match_len += 4U;

    if (match_len > dst_cap - d) {
      return false;
    }
    for (size_t i = 0U; i < match_len; ++i) {
      dst[d + i] = dst[d - (size_t)offset + i];
    }
    d += match_len;
  }

  *out_len = d;
  return true;
}

bool m3ds_net_tcp_frame_encode(
    const uint8_t *payload,
    size_t payload_len,
    uint8_t *out_frame,
    size_t out_cap,
    size_t *out_frame_len) {
  if (!payload || !out_frame || !out_frame_len || payload_len == 0U ||
      payload_len > 0xFFFFU || out_cap < payload_len + 2U) {
    return false;
  }
  write_u16_be(out_frame, (uint16_t)payload_len);
  memcpy(out_frame + 2U, payload, payload_len);
  *out_frame_len = payload_len + 2U;
  return true;
}

bool m3ds_net_tcp_frame_peek_len(const uint8_t *buf, size_t buf_len, uint16_t *out_payload_len) {
  if (!buf || !out_payload_len || buf_len < 2U) {
    return false;
  }
  *out_payload_len = read_u16_be(buf);
  return true;
}

bool m3ds_net_encode_framework(
    const M3dsNetFrameworkMsg *msg,
    uint8_t *out_buf,
    size_t out_cap,
    size_t *out_len) {
  if (!msg || !out_buf || !out_len || out_cap < 2U) {
    return false;
  }
  out_buf[0] = (uint8_t)M3DS_NET_FRAMEWORK_SENTINEL;
  out_buf[1] = (uint8_t)msg->type;

  switch (msg->type) {
    case M3DS_FW_PING:
      if (out_cap < 7U) {
        return false;
      }
      write_u32_be(out_buf + 2U, (uint32_t)msg->id);
      out_buf[6] = msg->is_reply ? 1U : 0U;
      *out_len = 7U;
      return true;
    case M3DS_FW_DISCOVER_HOST:
    case M3DS_FW_KEEP_ALIVE:
      *out_len = 2U;
      return true;
    case M3DS_FW_REGISTER_UDP:
    case M3DS_FW_REGISTER_TCP:
      if (out_cap < 6U) {
        return false;
      }
      write_u32_be(out_buf + 2U, (uint32_t)msg->id);
      *out_len = 6U;
      return true;
    default:
      return false;
  }
}

bool m3ds_net_decode_framework(
    const uint8_t *buf,
    size_t buf_len,
    M3dsNetFrameworkMsg *out_msg) {
  if (!buf || !out_msg || buf_len < 2U || buf[0] != (uint8_t)M3DS_NET_FRAMEWORK_SENTINEL) {
    return false;
  }
  memset(out_msg, 0, sizeof(*out_msg));
  uint8_t fw_id = buf[1];
  switch (fw_id) {
    case M3DS_FW_PING:
      if (buf_len < 7U) {
        return false;
      }
      out_msg->type = M3DS_FW_PING;
      out_msg->id = read_i32_be(buf + 2U);
      out_msg->is_reply = buf[6] == 1U;
      return true;
    case M3DS_FW_DISCOVER_HOST:
      out_msg->type = M3DS_FW_DISCOVER_HOST;
      return true;
    case M3DS_FW_KEEP_ALIVE:
      out_msg->type = M3DS_FW_KEEP_ALIVE;
      return true;
    case M3DS_FW_REGISTER_UDP:
      if (buf_len < 6U) {
        return false;
      }
      out_msg->type = M3DS_FW_REGISTER_UDP;
      out_msg->id = read_i32_be(buf + 2U);
      return true;
    case M3DS_FW_REGISTER_TCP:
      if (buf_len < 6U) {
        return false;
      }
      out_msg->type = M3DS_FW_REGISTER_TCP;
      out_msg->id = read_i32_be(buf + 2U);
      return true;
    default:
      return false;
  }
}

bool m3ds_net_encode_discover_host(uint8_t *out_buf, size_t out_cap, size_t *out_len) {
  M3dsNetFrameworkMsg msg;
  memset(&msg, 0, sizeof(msg));
  msg.type = M3DS_FW_DISCOVER_HOST;
  return m3ds_net_encode_framework(&msg, out_buf, out_cap, out_len);
}

static bool read_str8(
    const uint8_t *buf,
    size_t buf_len,
    size_t *pos,
    char *out_str,
    size_t out_cap) {
  if (*pos >= buf_len) {
    return false;
  }
  uint8_t len = buf[(*pos)++];
  if (len > buf_len - *pos) {
    return false;
  }
  size_t copy_len = len;
  if (copy_len >= out_cap) {
    copy_len = out_cap - 1U;
  }
  if (copy_len > 0U) {
    memcpy(out_str, buf + *pos, copy_len);
  }
  out_str[copy_len] = '\0';
  *pos += (size_t)len;
  return true;
}

bool m3ds_net_decode_host_info(const uint8_t *buf, size_t buf_len, M3dsNetHostInfo *out_info) {
  if (!buf || !out_info) {
    return false;
  }
  memset(out_info, 0, sizeof(*out_info));
  size_t pos = 0U;

  if (!read_str8(buf, buf_len, &pos, out_info->name, sizeof(out_info->name)) ||
      !read_str8(buf, buf_len, &pos, out_info->map_name, sizeof(out_info->map_name))) {
    return false;
  }
  if (buf_len - pos < 12U) {
    return false;
  }
  out_info->players = read_i32_be(buf + pos);
  out_info->wave = read_i32_be(buf + pos + 4U);
  out_info->version = read_i32_be(buf + pos + 8U);
  pos += 12U;

  if (!read_str8(buf, buf_len, &pos, out_info->version_type, sizeof(out_info->version_type))) {
    return false;
  }
  if (buf_len - pos < 5U) {
    return false;
  }
  out_info->gamemode = buf[pos++];
  out_info->player_limit = read_i32_be(buf + pos);
  pos += 4U;

  if (!read_str8(buf, buf_len, &pos, out_info->description, sizeof(out_info->description)) ||
      !read_str8(buf, buf_len, &pos, out_info->mode_name, sizeof(out_info->mode_name))) {
    return false;
  }
  if (buf_len - pos < 2U) {
    return false;
  }
  uint16_t wire_port = read_u16_be(buf + pos);
  out_info->port = wire_port != 0U ? wire_port : (uint16_t)M3DS_NET_DEFAULT_PORT;
  return true;
}

bool m3ds_net_encode_packet_raw(
    uint8_t packet_id,
    const uint8_t *payload,
    size_t payload_len,
    uint8_t *out_buf,
    size_t out_cap,
    size_t *out_len) {
  if (!out_buf || !out_len || (!payload && payload_len > 0U) ||
      payload_len > M3DS_NET_MAX_PACKET_SIZE || out_cap < payload_len + 4U) {
    return false;
  }
  out_buf[0] = packet_id;
  write_u16_be(out_buf + 1U, (uint16_t)payload_len);
  out_buf[3] = 0U; /* uncompressed */
  if (payload_len > 0U) {
    memcpy(out_buf + 4U, payload, payload_len);
  }
  *out_len = payload_len + 4U;
  return true;
}

bool m3ds_net_decode_packet(
    const uint8_t *buf,
    size_t buf_len,
    uint8_t *out_packet_id,
    uint8_t *out_payload,
    size_t out_payload_cap,
    size_t *out_payload_len) {
  if (!buf || !out_packet_id || !out_payload || !out_payload_len || buf_len < 4U) {
    return false;
  }
  if (buf[0] == (uint8_t)M3DS_NET_FRAMEWORK_SENTINEL) {
    return false;
  }
  uint8_t packet_id = buf[0];
  uint16_t declared_len = read_u16_be(buf + 1U);
  uint8_t compression = buf[3];

  if (declared_len > out_payload_cap) {
    return false;
  }
  if (compression == 0U) {
    if (buf_len - 4U < (size_t)declared_len) {
      return false;
    }
    if (declared_len > 0U) {
      memcpy(out_payload, buf + 4U, declared_len);
    }
    *out_packet_id = packet_id;
    *out_payload_len = declared_len;
    return true;
  }

  size_t decompressed_len = 0U;
  if (!m3ds_net_lz4_decompress(
          buf + 4U, buf_len - 4U, out_payload, out_payload_cap, &decompressed_len)) {
    return false;
  }
  if (decompressed_len != (size_t)declared_len) {
    return false;
  }
  *out_packet_id = packet_id;
  *out_payload_len = decompressed_len;
  return true;
}

static bool write_typeio_str(uint8_t *buf, size_t cap, size_t *pos, const char *str) {
  if (!str) {
    if (*pos >= cap) {
      return false;
    }
    buf[(*pos)++] = 0U;
    return true;
  }
  size_t slen = strlen(str);
  if (slen > 0xFFFFU || cap - *pos < slen + 3U) {
    return false;
  }
  buf[(*pos)++] = 1U;
  write_u16_be(buf + *pos, (uint16_t)slen);
  *pos += 2U;
  if (slen > 0U) {
    memcpy(buf + *pos, str, slen);
    *pos += slen;
  }
  return true;
}

static bool read_typeio_str(
    const uint8_t *buf,
    size_t len,
    size_t *pos,
    char *out_str,
    size_t out_cap) {
  if (*pos >= len) {
    return false;
  }
  uint8_t exists = buf[(*pos)++];
  if (exists == 0U) {
    out_str[0] = '\0';
    return true;
  }
  if (len - *pos < 2U) {
    return false;
  }
  uint16_t slen = read_u16_be(buf + *pos);
  *pos += 2U;
  if (len - *pos < (size_t)slen) {
    return false;
  }
  size_t copy_len = slen;
  if (copy_len >= out_cap) {
    copy_len = out_cap - 1U;
  }
  if (copy_len > 0U) {
    memcpy(out_str, buf + *pos, copy_len);
  }
  out_str[copy_len] = '\0';
  *pos += (size_t)slen;
  return true;
}

bool m3ds_net_encode_connect_packet(
    const M3dsNetConnectPacket *pkt,
    uint8_t *out_buf,
    size_t out_cap,
    size_t *out_len) {
  if (!pkt || !out_buf || !out_len || pkt->mod_count > M3DS_NET_MAX_MODS || out_cap < 4U) {
    return false;
  }
  size_t pos = 0U;
  write_u32_be(out_buf + pos, (uint32_t)pkt->version);
  pos += 4U;

  if (!write_typeio_str(out_buf, out_cap, &pos, pkt->version_type) ||
      !write_typeio_str(out_buf, out_cap, &pos, pkt->name) ||
      !write_typeio_str(out_buf, out_cap, &pos, pkt->locale) ||
      !write_typeio_str(out_buf, out_cap, &pos, pkt->usid)) {
    return false;
  }

  /* 8 raw UUID bytes + 8-byte big-endian IEEE CRC32 = 16 bytes on the wire. */
  if (out_cap - pos < 22U) {
    return false;
  }
  memcpy(out_buf + pos, pkt->uuid_raw, 8U);
  pos += 8U;
  uint64_t crc = (uint64_t)m3ds_net_crc32(pkt->uuid_raw, 8U);
  write_u64_be(out_buf + pos, crc);
  pos += 8U;

  out_buf[pos++] = pkt->mobile ? 1U : 0U;
  write_u32_be(out_buf + pos, pkt->color);
  pos += 4U;
  out_buf[pos++] = pkt->mod_count;

  for (uint8_t i = 0U; i < pkt->mod_count; ++i) {
    if (!write_typeio_str(out_buf, out_cap, &pos, pkt->mods[i])) {
      return false;
    }
  }

  *out_len = pos;
  return true;
}

bool m3ds_net_decode_connect_packet(
    const uint8_t *buf,
    size_t buf_len,
    M3dsNetDecodedConnect *out_pkt) {
  if (!buf || !out_pkt || buf_len < 4U) {
    return false;
  }
  memset(out_pkt, 0, sizeof(*out_pkt));
  size_t pos = 0U;
  out_pkt->version = read_i32_be(buf + pos);
  pos += 4U;

  if (!read_typeio_str(buf, buf_len, &pos, out_pkt->version_type, sizeof(out_pkt->version_type)) ||
      !read_typeio_str(buf, buf_len, &pos, out_pkt->name, sizeof(out_pkt->name)) ||
      !read_typeio_str(buf, buf_len, &pos, out_pkt->locale, sizeof(out_pkt->locale)) ||
      !read_typeio_str(buf, buf_len, &pos, out_pkt->usid, sizeof(out_pkt->usid))) {
    return false;
  }

  if (buf_len - pos < 22U) {
    return false;
  }
  memcpy(out_pkt->uuid_raw, buf + pos, 8U);
  pos += 8U;
  out_pkt->uuid_crc32 = read_u64_be(buf + pos);
  pos += 8U;
  out_pkt->crc_valid = (out_pkt->uuid_crc32 == (uint64_t)m3ds_net_crc32(out_pkt->uuid_raw, 8U));

  out_pkt->mobile = (buf[pos++] == 1U);
  out_pkt->color = read_u32_be(buf + pos);
  pos += 4U;
  out_pkt->mod_count = buf[pos++];
  if (out_pkt->mod_count > M3DS_NET_MAX_MODS) {
    return false;
  }

  for (uint8_t i = 0U; i < out_pkt->mod_count; ++i) {
    if (!read_typeio_str(buf, buf_len, &pos, out_pkt->mods[i], sizeof(out_pkt->mods[i]))) {
      return false;
    }
  }
  return true;
}

bool m3ds_net_decode_stream_begin(
    const uint8_t *payload,
    size_t payload_len,
    M3dsNetStreamBegin *out_begin) {
  if (!payload || !out_begin || payload_len < 9U) {
    return false;
  }
  out_begin->id = read_i32_be(payload);
  out_begin->total = read_i32_be(payload + 4U);
  out_begin->type = payload[8];
  return true;
}

bool m3ds_net_decode_stream_chunk(
    const uint8_t *payload,
    size_t payload_len,
    M3dsNetStreamChunk *out_chunk) {
  if (!payload || !out_chunk || payload_len < 6U) {
    return false;
  }
  uint16_t chunk_len = read_u16_be(payload + 4U);
  if (payload_len - 6U < (size_t)chunk_len) {
    return false;
  }
  out_chunk->id = read_i32_be(payload);
  out_chunk->len = chunk_len;
  out_chunk->data = payload + 6U;
  return true;
}

bool m3ds_net_ping_host(
    const M3dsNetTransport *transport,
    const char *host,
    uint16_t port,
    uint32_t timeout_ms,
    M3dsNetHostInfo *out_info) {
  if (!transport || !host || !out_info || !transport->udp_open ||
      !transport->udp_send || !transport->udp_recv || !transport->udp_close) {
    return false;
  }

  uint8_t req[4];
  size_t req_len = 0U;
  if (!m3ds_net_encode_discover_host(req, sizeof(req), &req_len)) {
    return false;
  }

  if (!transport->udp_open(transport->user_data, host, port)) {
    return false;
  }

  bool ok = false;
  if (transport->udp_send(transport->user_data, req, req_len) == (int)req_len) {
    uint8_t resp[512];
    int n = transport->udp_recv(transport->user_data, resp, sizeof(resp), timeout_ms);
    if (n > 0) {
      ok = m3ds_net_decode_host_info(resp, (size_t)n, out_info);
    }
  }

  transport->udp_close(transport->user_data);
  return ok;
}

static bool tcp_send_all(const M3dsNetTransport *transport, const uint8_t *data, size_t len) {
  size_t sent = 0U;
  while (sent < len) {
    int n = transport->tcp_send(transport->user_data, data + sent, len - sent);
    if (n <= 0) {
      return false;
    }
    sent += (size_t)n;
  }
  return true;
}

static int tcp_recv_exact(
    const M3dsNetTransport *transport,
    uint8_t *out_buf,
    size_t len,
    uint32_t timeout_ms) {
  size_t got = 0U;
  while (got < len) {
    int n = transport->tcp_recv(transport->user_data, out_buf + got, len - got, timeout_ms);
    if (n <= 0) {
      return n;
    }
    got += (size_t)n;
  }
  return (int)got;
}

static M3dsNetHandshakeStatus read_next_tcp_frame(
    const M3dsNetTransport *transport,
    uint8_t *frame_buf,
    size_t frame_cap,
    size_t *out_frame_len,
    uint32_t timeout_ms) {
  uint8_t hdr[2];
  int rc = tcp_recv_exact(transport, hdr, 2U, timeout_ms);
  if (rc == M3DS_NET_IO_EOF) {
    return M3DS_HANDSHAKE_DISCONNECTED;
  }
  if (rc == M3DS_NET_IO_TIMEOUT) {
    return M3DS_HANDSHAKE_TIMEOUT;
  }
  if (rc < 0) {
    return M3DS_HANDSHAKE_TRANSPORT_ERROR;
  }
  uint16_t payload_len = read_u16_be(hdr);
  if (payload_len == 0U || (size_t)payload_len > frame_cap) {
    return M3DS_HANDSHAKE_PROTOCOL_ERROR;
  }
  rc = tcp_recv_exact(transport, frame_buf, (size_t)payload_len, timeout_ms);
  if (rc == M3DS_NET_IO_EOF) {
    return M3DS_HANDSHAKE_DISCONNECTED;
  }
  if (rc == M3DS_NET_IO_TIMEOUT) {
    return M3DS_HANDSHAKE_TIMEOUT;
  }
  if (rc < 0) {
    return M3DS_HANDSHAKE_TRANSPORT_ERROR;
  }
  *out_frame_len = (size_t)payload_len;
  return M3DS_HANDSHAKE_OK_WORLD_STREAM;
}

bool m3ds_net_handshake(
    const M3dsNetTransport *transport,
    const char *host,
    uint16_t port,
    uint32_t timeout_ms,
    const M3dsNetConnectPacket *connect_pkt,
    M3dsNetHandshakeResult *out_result) {
  if (!out_result) {
    return false;
  }
  memset(out_result, 0, sizeof(*out_result));
  out_result->connection_id = -1;
  out_result->stream_id = -1;

  if (!transport || !host || !connect_pkt || !transport->tcp_connect ||
      !transport->tcp_send || !transport->tcp_recv || !transport->tcp_close ||
      !transport->udp_open || !transport->udp_send || !transport->udp_recv ||
      !transport->udp_close) {
    out_result->status = M3DS_HANDSHAKE_INVALID_ARG;
    return false;
  }

  static uint8_t frame_buf[M3DS_NET_MAX_PACKET_SIZE + 16U];
  static uint8_t payload_buf[M3DS_NET_MAX_PACKET_SIZE];
  static uint8_t tx_buf[M3DS_NET_MAX_PACKET_SIZE + 16U];

  if (!transport->tcp_connect(transport->user_data, host, port, timeout_ms)) {
    out_result->status = M3DS_HANDSHAKE_TRANSPORT_ERROR;
    return false;
  }

  bool udp_opened = false;
  bool have_conn_id = false;
  M3dsNetHandshakeStatus status = M3DS_HANDSHAKE_PROTOCOL_ERROR;

  /* Step 1: Wait for ArcNet RegisterTCP(connectionID). Note: Arc IDs may be negative. */
  for (int guard = 0; guard < 16; ++guard) {
    size_t frame_len = 0U;
    status = read_next_tcp_frame(transport, frame_buf, sizeof(frame_buf), &frame_len, timeout_ms);
    if (status != M3DS_HANDSHAKE_OK_WORLD_STREAM) {
      goto cleanup;
    }
    M3dsNetFrameworkMsg fw;
    if (m3ds_net_decode_framework(frame_buf, frame_len, &fw)) {
      if (fw.type == M3DS_FW_REGISTER_TCP) {
        out_result->connection_id = fw.id;
        have_conn_id = true;
        break;
      }
    }
  }
  if (!have_conn_id) {
    status = M3DS_HANDSHAKE_PROTOCOL_ERROR;
    goto cleanup;
  }

  /* Step 2: Open UDP and send RegisterUDP(connectionID) until server echoes RegisterUDP on TCP. */
  if (!transport->udp_open(transport->user_data, host, port)) {
    status = M3DS_HANDSHAKE_TRANSPORT_ERROR;
    goto cleanup;
  }
  udp_opened = true;

  M3dsNetFrameworkMsg reg_udp;
  memset(&reg_udp, 0, sizeof(reg_udp));
  reg_udp.type = M3DS_FW_REGISTER_UDP;
  reg_udp.id = out_result->connection_id;
  size_t reg_udp_len = 0U;
  if (!m3ds_net_encode_framework(&reg_udp, tx_buf, sizeof(tx_buf), &reg_udp_len)) {
    status = M3DS_HANDSHAKE_PROTOCOL_ERROR;
    goto cleanup;
  }

  uint32_t poll_ms = timeout_ms > 200U ? 200U : timeout_ms;
  for (int attempt = 0; attempt < 20 && !out_result->udp_registered; ++attempt) {
    if (transport->udp_send(transport->user_data, tx_buf, reg_udp_len) != (int)reg_udp_len) {
      status = M3DS_HANDSHAKE_TRANSPORT_ERROR;
      goto cleanup;
    }
    size_t frame_len = 0U;
    M3dsNetHandshakeStatus rx =
        read_next_tcp_frame(transport, frame_buf, sizeof(frame_buf), &frame_len, poll_ms);
    if (rx == M3DS_HANDSHAKE_TIMEOUT) {
      continue;
    }
    if (rx != M3DS_HANDSHAKE_OK_WORLD_STREAM) {
      status = rx;
      goto cleanup;
    }
    M3dsNetFrameworkMsg fw;
    if (m3ds_net_decode_framework(frame_buf, frame_len, &fw) && fw.type == M3DS_FW_REGISTER_UDP) {
      out_result->udp_registered = true;
      break;
    }
  }
  if (!out_result->udp_registered) {
    status = M3DS_HANDSHAKE_TIMEOUT;
    goto cleanup;
  }

  /* Step 3: Encode ConnectPacket inside PacketSerializer envelope + Arc TCP length prefix. */
  uint8_t connect_payload[2048];
  size_t connect_payload_len = 0U;
  if (!m3ds_net_encode_connect_packet(
          connect_pkt, connect_payload, sizeof(connect_payload), &connect_payload_len)) {
    status = M3DS_HANDSHAKE_INVALID_ARG;
    goto cleanup;
  }
  uint8_t pkt_envelope[2064];
  size_t pkt_envelope_len = 0U;
  if (!m3ds_net_encode_packet_raw(
          M3DS_PKT_CONNECT_PACKET,
          connect_payload,
          connect_payload_len,
          pkt_envelope,
          sizeof(pkt_envelope),
          &pkt_envelope_len)) {
    status = M3DS_HANDSHAKE_PROTOCOL_ERROR;
    goto cleanup;
  }
  size_t framed_len = 0U;
  if (!m3ds_net_tcp_frame_encode(
          pkt_envelope, pkt_envelope_len, tx_buf, sizeof(tx_buf), &framed_len)) {
    status = M3DS_HANDSHAKE_PROTOCOL_ERROR;
    goto cleanup;
  }
  if (!tcp_send_all(transport, tx_buf, framed_len)) {
    status = M3DS_HANDSHAKE_TRANSPORT_ERROR;
    goto cleanup;
  }

  /* Step 4: Read TCP packets until StreamBegin (accepted) or kick / disconnect. */
  for (int guard = 0; guard < 32; ++guard) {
    size_t frame_len = 0U;
    status = read_next_tcp_frame(transport, frame_buf, sizeof(frame_buf), &frame_len, timeout_ms);
    if (status != M3DS_HANDSHAKE_OK_WORLD_STREAM) {
      goto cleanup;
    }

    if (frame_buf[0] == (uint8_t)M3DS_NET_FRAMEWORK_SENTINEL) {
      M3dsNetFrameworkMsg fw;
      if (m3ds_net_decode_framework(frame_buf, frame_len, &fw) &&
          fw.type == M3DS_FW_PING && !fw.is_reply) {
        M3dsNetFrameworkMsg pong = fw;
        pong.is_reply = true;
        uint8_t pong_raw[8];
        size_t pong_raw_len = 0U;
        if (m3ds_net_encode_framework(&pong, pong_raw, sizeof(pong_raw), &pong_raw_len) &&
            m3ds_net_tcp_frame_encode(pong_raw, pong_raw_len, tx_buf, sizeof(tx_buf), &framed_len)) {
          (void)tcp_send_all(transport, tx_buf, framed_len);
          out_result->pings_answered++;
        }
      }
      continue;
    }

    uint8_t pkt_id = 0U;
    size_t pkt_payload_len = 0U;
    if (!m3ds_net_decode_packet(
            frame_buf, frame_len, &pkt_id, payload_buf, sizeof(payload_buf), &pkt_payload_len)) {
      status = M3DS_HANDSHAKE_PROTOCOL_ERROR;
      goto cleanup;
    }
    out_result->first_server_packet_id = pkt_id;
    out_result->first_server_payload_len = pkt_payload_len;

    if (pkt_id == M3DS_PKT_STREAM_BEGIN) {
      M3dsNetStreamBegin begin;
      if (!m3ds_net_decode_stream_begin(payload_buf, pkt_payload_len, &begin)) {
        status = M3DS_HANDSHAKE_PROTOCOL_ERROR;
        goto cleanup;
      }
      out_result->stream_id = begin.id;
      out_result->stream_total = begin.total;
      out_result->stream_type = begin.type;
      status = M3DS_HANDSHAKE_OK_WORLD_STREAM;
      goto cleanup;
    }

    /* Any other application packet (e.g. Call.kick) indicates server rejection/notice. */
    status = M3DS_HANDSHAKE_SERVER_REJECTED;
    goto cleanup;
  }

  status = M3DS_HANDSHAKE_TIMEOUT;

cleanup:
  if (udp_opened) {
    transport->udp_close(transport->user_data);
  }
  transport->tcp_close(transport->user_data);
  out_result->status = status;
  return status == M3DS_HANDSHAKE_OK_WORLD_STREAM;
}
