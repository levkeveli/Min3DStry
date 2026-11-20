#include "net/mindustry_net.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static void test_tcp_framing_and_framework(void) {
  uint8_t raw[16];
  size_t raw_len = 0U;

  M3dsNetFrameworkMsg reg_tcp = {M3DS_FW_REGISTER_TCP, 42, false};
  assert(m3ds_net_encode_framework(&reg_tcp, raw, sizeof(raw), &raw_len));
  assert(raw_len == 6U);
  assert(raw[0] == 0xFEU && raw[1] == 4U);
  assert(raw[5] == 42U);

  M3dsNetFrameworkMsg decoded;
  assert(m3ds_net_decode_framework(raw, raw_len, &decoded));
  assert(decoded.type == M3DS_FW_REGISTER_TCP);
  assert(decoded.id == 42);

  uint8_t framed[32];
  size_t framed_len = 0U;
  assert(m3ds_net_tcp_frame_encode(raw, raw_len, framed, sizeof(framed), &framed_len));
  assert(framed_len == 8U);
  uint16_t peek = 0U;
  assert(m3ds_net_tcp_frame_peek_len(framed, framed_len, &peek));
  assert(peek == 6U);

  M3dsNetFrameworkMsg ping = {M3DS_FW_PING, 777, true};
  assert(m3ds_net_encode_framework(&ping, raw, sizeof(raw), &raw_len));
  assert(raw_len == 7U);
  assert(m3ds_net_decode_framework(raw, raw_len, &decoded));
  assert(decoded.type == M3DS_FW_PING && decoded.id == 777 && decoded.is_reply);

  uint8_t disc[4];
  size_t disc_len = 0U;
  assert(m3ds_net_encode_discover_host(disc, sizeof(disc), &disc_len));
  assert(disc_len == 2U && disc[0] == 0xFEU && disc[1] == 0x01U);
}

static size_t append_str8(uint8_t *buf, size_t pos, const char *s) {
  uint8_t n = (uint8_t)strlen(s);
  buf[pos++] = n;
  memcpy(buf + pos, s, n);
  return pos + n;
}

static size_t append_u32_be(uint8_t *buf, size_t pos, uint32_t v) {
  buf[pos++] = (uint8_t)((v >> 24U) & 0xFFU);
  buf[pos++] = (uint8_t)((v >> 16U) & 0xFFU);
  buf[pos++] = (uint8_t)((v >> 8U) & 0xFFU);
  buf[pos++] = (uint8_t)(v & 0xFFU);
  return pos;
}

static void test_host_info_decoder(void) {
  uint8_t wire[128];
  size_t pos = 0U;
  pos = append_str8(wire, pos, "Min3DStry Test Server");
  pos = append_str8(wire, pos, "Glacier");
  pos = append_u32_be(wire, pos, 3U);     /* players */
  pos = append_u32_be(wire, pos, 12U);    /* wave */
  pos = append_u32_be(wire, pos, 160U);   /* version */
  pos = append_str8(wire, pos, "official");
  wire[pos++] = 0U;                       /* survival */
  pos = append_u32_be(wire, pos, 24U);    /* playerLimit */
  pos = append_str8(wire, pos, "v160.8 host");
  pos = append_str8(wire, pos, "");       /* modeName */
  wire[pos++] = 0U;                       /* port 0 -> defaults to 6567 */
  wire[pos++] = 0U;

  M3dsNetHostInfo info;
  assert(m3ds_net_decode_host_info(wire, pos, &info));
  assert(strcmp(info.name, "Min3DStry Test Server") == 0);
  assert(strcmp(info.map_name, "Glacier") == 0);
  assert(info.players == 3);
  assert(info.wave == 12);
  assert(info.version == 160);
  assert(strcmp(info.version_type, "official") == 0);
  assert(info.player_limit == 24);
  assert(info.port == 6567U);

  /* Truncated packet must fail cleanly. */
  assert(!m3ds_net_decode_host_info(wire, pos - 3U, &info));
}

static void test_lz4_and_packet_envelope(void) {
  /* Construct a compressed LZ4 block with literals + RLE overlapping match:
     Literals "AB", then copy offset=2, match_len=6 -> "ABABABAB" (8 bytes).
     token = (2 << 4) | (6 - 4) = 0x22. */
  const uint8_t lz4_src[] = {0x22U, 'A', 'B', 0x02U, 0x00U};
  uint8_t out[16];
  size_t out_len = 0U;
  assert(m3ds_net_lz4_decompress(lz4_src, sizeof(lz4_src), out, sizeof(out), &out_len));
  assert(out_len == 8U);
  assert(memcmp(out, "ABABABAB", 8U) == 0);

  /* Out-of-bounds offset and truncated literal must fail safely. */
  const uint8_t bad_offset[] = {0x10U, 'X', 0x05U, 0x00U};
  assert(!m3ds_net_lz4_decompress(bad_offset, sizeof(bad_offset), out, sizeof(out), &out_len));
  const uint8_t bad_trunc[] = {0x50U, 'X', 'Y'};
  assert(!m3ds_net_lz4_decompress(bad_trunc, sizeof(bad_trunc), out, sizeof(out), &out_len));

  /* Wrap compressed LZ4 payload inside PacketSerializer envelope. */
  uint8_t env[32];
  env[0] = M3DS_PKT_CONNECT_PACKET;
  env[1] = 0U;
  env[2] = 8U; /* uncompressed len */
  env[3] = 1U; /* compressed */
  memcpy(env + 4U, lz4_src, sizeof(lz4_src));

  uint8_t pkt_id = 0U;
  uint8_t dec[16];
  size_t dec_len = 0U;
  assert(m3ds_net_decode_packet(env, 4U + sizeof(lz4_src), &pkt_id, dec, sizeof(dec), &dec_len));
  assert(pkt_id == M3DS_PKT_CONNECT_PACKET);
  assert(dec_len == 8U && memcmp(dec, "ABABABAB", 8U) == 0);

  /* Unsupported compression byte (ArcNetProvider only defines 0 and 1) must fail. */
  env[3] = 2U;
  assert(!m3ds_net_decode_packet(env, 4U + sizeof(lz4_src), &pkt_id, dec, sizeof(dec), &dec_len));
}

static void test_connect_packet_roundtrip(void) {
  M3dsNetConnectPacket pkt;
  memset(&pkt, 0, sizeof(pkt));
  pkt.version = M3DS_NET_UPSTREAM_BUILD;
  pkt.version_type = M3DS_NET_UPSTREAM_VERSION_TYPE;
  pkt.name = "3DS-Player";
  pkt.locale = "en_US";
  pkt.usid = "m3ds-usid-01";
  for (uint8_t i = 0U; i < 8U; ++i) {
    pkt.uuid_raw[i] = (uint8_t)(0x10U + i);
  }
  pkt.mobile = false;
  pkt.color = 0xFFB841FFU;
  pkt.mod_count = 0U;

  uint8_t buf[256];
  size_t len = 0U;
  assert(m3ds_net_encode_connect_packet(&pkt, buf, sizeof(buf), &len));

  M3dsNetDecodedConnect dec;
  assert(m3ds_net_decode_connect_packet(buf, len, &dec));
  assert(dec.version == 160);
  assert(strcmp(dec.version_type, "official") == 0);
  assert(strcmp(dec.name, "3DS-Player") == 0);
  assert(strcmp(dec.locale, "en_US") == 0);
  assert(strcmp(dec.usid, "m3ds-usid-01") == 0);
  assert(memcmp(dec.uuid_raw, pkt.uuid_raw, 8U) == 0);
  assert(dec.crc_valid);
  assert(!dec.mobile);
  assert(dec.color == 0xFFB841FFU);
  assert(dec.mod_count == 0U);
}

typedef struct MockNetState {
  uint8_t rx_stream[2048];
  size_t rx_len;
  size_t rx_pos;
  size_t max_tcp_chunk;
  bool inject_timeout_once;
  size_t timeout_at_rx_pos;

  uint8_t tx_tcp[4096];
  size_t tx_tcp_len;

  uint8_t tx_udp[128];
  size_t tx_udp_len;

  uint8_t rx_udp[1024];
  size_t rx_udp_len;

  bool closed;
} MockNetState;

static void mock_queue_tcp_frame(MockNetState *st, const uint8_t *payload, size_t payload_len) {
  size_t out_len = 0U;
  assert(m3ds_net_tcp_frame_encode(
      payload, payload_len, st->rx_stream + st->rx_len, sizeof(st->rx_stream) - st->rx_len, &out_len));
  st->rx_len += out_len;
}

static bool mock_tcp_connect(void *user_data, const char *host, uint16_t port, uint32_t timeout_ms) {
  (void)user_data;
  (void)host;
  (void)port;
  (void)timeout_ms;
  return true;
}

static int mock_tcp_send(void *user_data, const uint8_t *data, size_t len) {
  MockNetState *st = (MockNetState *)user_data;
  if (st->tx_tcp_len + len > sizeof(st->tx_tcp)) {
    return M3DS_NET_IO_ERROR;
  }
  memcpy(st->tx_tcp + st->tx_tcp_len, data, len);
  st->tx_tcp_len += len;
  return (int)len;
}

static int mock_tcp_recv(void *user_data, uint8_t *out_buf, size_t max_len, uint32_t timeout_ms) {
  (void)timeout_ms;
  MockNetState *st = (MockNetState *)user_data;
  if (st->inject_timeout_once && st->rx_pos == st->timeout_at_rx_pos) {
    st->inject_timeout_once = false;
    return M3DS_NET_IO_TIMEOUT;
  }
  if (st->rx_pos >= st->rx_len) {
    return M3DS_NET_IO_EOF;
  }
  size_t avail = st->rx_len - st->rx_pos;
  size_t n = avail < max_len ? avail : max_len;
  if (st->max_tcp_chunk > 0U && n > st->max_tcp_chunk) {
    n = st->max_tcp_chunk;
  }
  memcpy(out_buf, st->rx_stream + st->rx_pos, n);
  st->rx_pos += n;
  return (int)n;
}

static void mock_tcp_close(void *user_data) {
  MockNetState *st = (MockNetState *)user_data;
  st->closed = true;
}

static bool mock_udp_open(void *user_data, const char *host, uint16_t port) {
  (void)user_data;
  (void)host;
  (void)port;
  return true;
}

static int mock_udp_send(void *user_data, const uint8_t *data, size_t len) {
  MockNetState *st = (MockNetState *)user_data;
  if (len > sizeof(st->tx_udp)) {
    return M3DS_NET_IO_ERROR;
  }
  memcpy(st->tx_udp, data, len);
  st->tx_udp_len = len;
  return (int)len;
}

static int mock_udp_recv(void *user_data, uint8_t *out_buf, size_t max_len, uint32_t timeout_ms) {
  (void)timeout_ms;
  MockNetState *st = (MockNetState *)user_data;
  if (st->rx_udp_len == 0U) {
    return M3DS_NET_IO_TIMEOUT;
  }
  size_t n = st->rx_udp_len < max_len ? st->rx_udp_len : max_len;
  memcpy(out_buf, st->rx_udp, n);
  st->rx_udp_len = 0U;
  return (int)n;
}

static void mock_udp_close(void *user_data) {
  (void)user_data;
}

static size_t append_str8_repeat(uint8_t *buf, size_t pos, uint8_t n, char ch) {
  buf[pos++] = n;
  memset(buf + pos, (unsigned char)ch, n);
  return pos + (size_t)n;
}

static void test_ping_host_large_datagram(void) {
  MockNetState st;
  memset(&st, 0, sizeof(st));

  size_t pos = 0U;
  pos = append_str8_repeat(st.rx_udp, pos, 200U, 'N');
  pos = append_str8_repeat(st.rx_udp, pos, 120U, 'M');
  pos = append_u32_be(st.rx_udp, pos, 7U);     /* players */
  pos = append_u32_be(st.rx_udp, pos, 99U);    /* wave */
  pos = append_u32_be(st.rx_udp, pos, 160U);   /* version */
  pos = append_str8(st.rx_udp, pos, "official");
  st.rx_udp[pos++] = 0U;                       /* gamemode */
  pos = append_u32_be(st.rx_udp, pos, 30U);    /* playerLimit */
  pos = append_str8_repeat(st.rx_udp, pos, 200U, 'D');
  pos = append_str8_repeat(st.rx_udp, pos, 100U, 'X');
  st.rx_udp[pos++] = 0x40U;                    /* port 16567 */
  st.rx_udp[pos++] = 0xB7U;
  assert(pos > 512U && pos < sizeof(st.rx_udp));
  st.rx_udp_len = pos;

  M3dsNetTransport tr = {
      &st,
      mock_tcp_connect,
      mock_tcp_send,
      mock_tcp_recv,
      mock_tcp_close,
      mock_udp_open,
      mock_udp_send,
      mock_udp_recv,
      mock_udp_close};

  M3dsNetHostInfo info;
  assert(m3ds_net_ping_host(&tr, "127.0.0.1", 16567U, 1000U, &info));
  assert(info.players == 7);
  assert(info.wave == 99);
  assert(info.version == 160);
  assert(strcmp(info.version_type, "official") == 0);
  assert(info.player_limit == 30);
  assert(info.port == 16567U);
  assert(strlen(info.name) == sizeof(info.name) - 1U);
  assert(strlen(info.description) == sizeof(info.description) - 1U);
}

static void test_mock_handshake(void) {
  MockNetState st;
  memset(&st, 0, sizeof(st));

  /* Queue server sequence with negative Arc connectionID:
     RegisterTCP(-9) -> RegisterUDP(0) -> KeepAlive -> Ping(13) -> StreamBegin */
  uint8_t msg[16];
  size_t msg_len = 0U;
  M3dsNetFrameworkMsg fw_tcp = {M3DS_FW_REGISTER_TCP, -9, false};
  assert(m3ds_net_encode_framework(&fw_tcp, msg, sizeof(msg), &msg_len));
  mock_queue_tcp_frame(&st, msg, msg_len);

  M3dsNetFrameworkMsg fw_udp = {M3DS_FW_REGISTER_UDP, 0, false};
  assert(m3ds_net_encode_framework(&fw_udp, msg, sizeof(msg), &msg_len));
  mock_queue_tcp_frame(&st, msg, msg_len);

  M3dsNetFrameworkMsg fw_ka = {M3DS_FW_KEEP_ALIVE, 0, false};
  assert(m3ds_net_encode_framework(&fw_ka, msg, sizeof(msg), &msg_len));
  mock_queue_tcp_frame(&st, msg, msg_len);

  M3dsNetFrameworkMsg fw_ping = {M3DS_FW_PING, 13, false};
  assert(m3ds_net_encode_framework(&fw_ping, msg, sizeof(msg), &msg_len));
  mock_queue_tcp_frame(&st, msg, msg_len);

  uint8_t sb_payload[9] = {0, 0, 0, 1, 0, 0, 16, 0, M3DS_PKT_WORLD_STREAM};
  uint8_t sb_env[16];
  size_t sb_env_len = 0U;
  assert(m3ds_net_encode_packet_raw(
      M3DS_PKT_STREAM_BEGIN, sb_payload, sizeof(sb_payload), sb_env, sizeof(sb_env), &sb_env_len));
  mock_queue_tcp_frame(&st, sb_env, sb_env_len);

  M3dsNetTransport tr = {
      &st,
      mock_tcp_connect,
      mock_tcp_send,
      mock_tcp_recv,
      mock_tcp_close,
      mock_udp_open,
      mock_udp_send,
      mock_udp_recv,
      mock_udp_close};

  M3dsNetConnectPacket pkt;
  memset(&pkt, 0, sizeof(pkt));
  pkt.version = M3DS_NET_UPSTREAM_BUILD;
  pkt.version_type = M3DS_NET_UPSTREAM_VERSION_TYPE;
  pkt.name = "Min3DStry";
  pkt.locale = "en";
  pkt.usid = "testusid";
  pkt.color = 0xFFFFFFFFU;

  M3dsNetHandshakeResult res;
  assert(m3ds_net_handshake(&tr, "127.0.0.1", M3DS_NET_DEFAULT_PORT, 1000U, &pkt, &res));
  assert(res.status == M3DS_HANDSHAKE_OK_WORLD_STREAM);
  assert(res.connection_id == -9);
  assert(res.udp_registered);
  assert(res.stream_id == 1);
  assert(res.stream_total == 4096);
  assert(res.stream_type == M3DS_PKT_WORLD_STREAM);
  assert(res.pings_answered == 1U);
  assert(st.closed);

  /* Verify RegisterUDP sent over UDP contained connection_id == -9. */
  M3dsNetFrameworkMsg sent_udp;
  assert(m3ds_net_decode_framework(st.tx_udp, st.tx_udp_len, &sent_udp));
  assert(sent_udp.type == M3DS_FW_REGISTER_UDP && sent_udp.id == -9);
}

static void test_segmented_handshake_and_pings(void) {
  MockNetState st;
  memset(&st, 0, sizeof(st));
  st.max_tcp_chunk = 1U;

  uint8_t msg[16];
  size_t msg_len = 0U;
  M3dsNetFrameworkMsg fw_ping1 = {M3DS_FW_PING, 1, false};
  assert(m3ds_net_encode_framework(&fw_ping1, msg, sizeof(msg), &msg_len));
  mock_queue_tcp_frame(&st, msg, msg_len);

  M3dsNetFrameworkMsg fw_tcp = {M3DS_FW_REGISTER_TCP, -42, false};
  assert(m3ds_net_encode_framework(&fw_tcp, msg, sizeof(msg), &msg_len));
  mock_queue_tcp_frame(&st, msg, msg_len);

  M3dsNetFrameworkMsg fw_ping2 = {M3DS_FW_PING, 2, false};
  assert(m3ds_net_encode_framework(&fw_ping2, msg, sizeof(msg), &msg_len));
  mock_queue_tcp_frame(&st, msg, msg_len);

  size_t udp_ack_start = st.rx_len;
  M3dsNetFrameworkMsg fw_udp = {M3DS_FW_REGISTER_UDP, 0, false};
  assert(m3ds_net_encode_framework(&fw_udp, msg, sizeof(msg), &msg_len));
  mock_queue_tcp_frame(&st, msg, msg_len);
  st.inject_timeout_once = true;
  st.timeout_at_rx_pos = udp_ack_start + 3U;

  M3dsNetFrameworkMsg fw_ping3 = {M3DS_FW_PING, 3, false};
  assert(m3ds_net_encode_framework(&fw_ping3, msg, sizeof(msg), &msg_len));
  mock_queue_tcp_frame(&st, msg, msg_len);

  uint8_t sb_payload[9] = {0, 0, 0, 5, 0, 0, 32, 0, M3DS_PKT_WORLD_STREAM};
  uint8_t sb_env[16];
  size_t sb_env_len = 0U;
  assert(m3ds_net_encode_packet_raw(
      M3DS_PKT_STREAM_BEGIN, sb_payload, sizeof(sb_payload), sb_env, sizeof(sb_env), &sb_env_len));
  mock_queue_tcp_frame(&st, sb_env, sb_env_len);

  M3dsNetTransport tr = {
      &st,
      mock_tcp_connect,
      mock_tcp_send,
      mock_tcp_recv,
      mock_tcp_close,
      mock_udp_open,
      mock_udp_send,
      mock_udp_recv,
      mock_udp_close};

  M3dsNetConnectPacket pkt;
  memset(&pkt, 0, sizeof(pkt));
  pkt.version = M3DS_NET_UPSTREAM_BUILD;
  pkt.version_type = M3DS_NET_UPSTREAM_VERSION_TYPE;
  pkt.name = "Min3DStrySeg";
  pkt.locale = "en";
  pkt.usid = "testusid";
  pkt.color = 0xFFFFFFFFU;

  M3dsNetHandshakeResult res;
  assert(m3ds_net_handshake(&tr, "127.0.0.1", M3DS_NET_DEFAULT_PORT, 1000U, &pkt, &res));
  assert(!st.inject_timeout_once);
  assert(res.status == M3DS_HANDSHAKE_OK_WORLD_STREAM);
  assert(res.connection_id == -42);
  assert(res.udp_registered);
  assert(res.stream_id == 5);
  assert(res.stream_total == 8192);
  assert(res.stream_type == M3DS_PKT_WORLD_STREAM);
  assert(res.pings_answered == 3U);
  assert(st.closed);
}

static void test_early_kick_during_udp_reg(void) {
  MockNetState st;
  memset(&st, 0, sizeof(st));

  uint8_t msg[16];
  size_t msg_len = 0U;
  M3dsNetFrameworkMsg fw_tcp = {M3DS_FW_REGISTER_TCP, 7, false};
  assert(m3ds_net_encode_framework(&fw_tcp, msg, sizeof(msg), &msg_len));
  mock_queue_tcp_frame(&st, msg, msg_len);

  uint8_t kick_payload[1] = {0U};
  uint8_t kick_env[8];
  size_t kick_env_len = 0U;
  assert(m3ds_net_encode_packet_raw(
      66U, kick_payload, sizeof(kick_payload), kick_env, sizeof(kick_env), &kick_env_len));
  mock_queue_tcp_frame(&st, kick_env, kick_env_len);

  M3dsNetTransport tr = {
      &st,
      mock_tcp_connect,
      mock_tcp_send,
      mock_tcp_recv,
      mock_tcp_close,
      mock_udp_open,
      mock_udp_send,
      mock_udp_recv,
      mock_udp_close};

  M3dsNetConnectPacket pkt;
  memset(&pkt, 0, sizeof(pkt));
  pkt.version = M3DS_NET_UPSTREAM_BUILD;
  pkt.version_type = M3DS_NET_UPSTREAM_VERSION_TYPE;
  pkt.name = "Min3DStryKick";
  pkt.locale = "en";
  pkt.usid = "testusid";
  pkt.color = 0xFFFFFFFFU;

  M3dsNetHandshakeResult res;
  assert(!m3ds_net_handshake(&tr, "127.0.0.1", M3DS_NET_DEFAULT_PORT, 1000U, &pkt, &res));
  assert(res.status == M3DS_HANDSHAKE_SERVER_REJECTED);
  assert(res.first_server_packet_id == 66U);
  assert(res.first_server_payload_len == 1U);
  assert(st.closed);
}

static void test_real_upstream_lz4_packet_vector(void) {
  /* Wire bytes generated by pinned v160.8 ArcNetProvider$PacketSerializer (net.jpountz.lz4)
     via UpstreamNetSessionBaseline.java --dump-connect-vector. */
  static const uint8_t wire[] = {
      0x03U, 0x00U, 0x47U, 0x01U, 0xf0U, 0x38U, 0x00U, 0x00U, 0x00U, 0xa0U, 0x01U, 0x00U, 0x08U,
      0x6fU, 0x66U, 0x66U, 0x69U, 0x63U, 0x69U, 0x61U, 0x6cU, 0x01U, 0x00U, 0x0cU, 0x4dU, 0x69U,
      0x6eU, 0x33U, 0x44U, 0x53U, 0x74U, 0x72U, 0x79U, 0x4cU, 0x5aU, 0x34U, 0x01U, 0x00U, 0x05U,
      0x65U, 0x6eU, 0x5fU, 0x55U, 0x53U, 0x01U, 0x00U, 0x08U, 0x6dU, 0x33U, 0x64U, 0x73U, 0x30U,
      0x30U, 0x30U, 0x31U, 0x41U, 0x42U, 0x43U, 0x44U, 0x45U, 0x46U, 0x47U, 0x48U, 0x00U, 0x00U,
      0x00U, 0x00U, 0x68U, 0xdcU, 0xb6U, 0x1cU, 0x01U, 0xffU, 0xb8U, 0x41U, 0xffU, 0x00U};
  assert(wire[3] == 1U);

  uint8_t pkt_id = 0U;
  uint8_t payload[128];
  size_t payload_len = 0U;
  assert(m3ds_net_decode_packet(wire, sizeof(wire), &pkt_id, payload, sizeof(payload), &payload_len));
  assert(pkt_id == M3DS_PKT_CONNECT_PACKET);
  assert(payload_len == 71U);

  M3dsNetDecodedConnect dec;
  assert(m3ds_net_decode_connect_packet(payload, payload_len, &dec));
  assert(dec.version == 160);
  assert(strcmp(dec.version_type, "official") == 0);
  assert(strcmp(dec.name, "Min3DStryLZ4") == 0);
  assert(strcmp(dec.locale, "en_US") == 0);
  assert(strcmp(dec.usid, "m3ds0001") == 0);
  for (uint8_t i = 0U; i < 8U; ++i) {
    assert(dec.uuid_raw[i] == (uint8_t)(0x41U + i));
  }
  assert(dec.crc_valid);
  assert(dec.mobile);
  assert(dec.color == 0xFFB841FFU);
  assert(dec.mod_count == 0U);
}

int main(void) {
  test_tcp_framing_and_framework();
  test_host_info_decoder();
  test_ping_host_large_datagram();
  test_lz4_and_packet_envelope();
  test_connect_packet_roundtrip();
  test_real_upstream_lz4_packet_vector();
  test_mock_handshake();
  test_segmented_handshake_and_pings();
  test_early_kick_during_udp_reg();
  printf("test_mindustry_net: all assertions passed.\n");
  return 0;
}
