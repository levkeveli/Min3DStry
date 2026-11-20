#ifndef MIN3DSTRY_NET_MINDUSTRY_NET_H
#define MIN3DSTRY_NET_MINDUSTRY_NET_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_NET_DEFAULT_PORT 6567U
#define M3DS_NET_UPSTREAM_BUILD 160
#define M3DS_NET_UPSTREAM_VERSION_TYPE "official"

#define M3DS_NET_FRAMEWORK_SENTINEL 0xFEU
#define M3DS_NET_MAX_PACKET_SIZE 32768U
#define M3DS_NET_MAX_MODS 16U

typedef enum M3dsNetFrameworkId {
  M3DS_FW_PING = 0,
  M3DS_FW_DISCOVER_HOST = 1,
  M3DS_FW_KEEP_ALIVE = 2,
  M3DS_FW_REGISTER_UDP = 3,
  M3DS_FW_REGISTER_TCP = 4
} M3dsNetFrameworkId;

typedef enum M3dsNetPacketId {
  M3DS_PKT_STREAM_BEGIN = 0,
  M3DS_PKT_STREAM_CHUNK = 1,
  M3DS_PKT_WORLD_STREAM = 2,
  M3DS_PKT_CONNECT_PACKET = 3,
  M3DS_PKT_ASSET_REQ_STREAM = 4,
  M3DS_PKT_ASSET_STREAM = 5,
  M3DS_PKT_TEXTURE_STREAM = 6
} M3dsNetPacketId;

typedef struct M3dsNetFrameworkMsg {
  M3dsNetFrameworkId type;
  int32_t id;
  bool is_reply;
} M3dsNetFrameworkMsg;

typedef struct M3dsNetHostInfo {
  char name[104];
  char map_name[68];
  int32_t players;
  int32_t wave;
  int32_t version;
  char version_type[36];
  uint8_t gamemode;
  int32_t player_limit;
  char description[104];
  char mode_name[56];
  uint16_t port;
} M3dsNetHostInfo;

typedef struct M3dsNetConnectPacket {
  int32_t version;
  const char *version_type;
  const char *name;
  const char *locale;
  const char *usid;
  uint8_t uuid_raw[8];
  bool mobile;
  uint32_t color;
  uint8_t mod_count;
  const char *mods[M3DS_NET_MAX_MODS];
} M3dsNetConnectPacket;

typedef struct M3dsNetDecodedConnect {
  int32_t version;
  char version_type[64];
  char name[64];
  char locale[32];
  char usid[64];
  uint8_t uuid_raw[8];
  uint64_t uuid_crc32;
  bool crc_valid;
  bool mobile;
  uint32_t color;
  uint8_t mod_count;
  char mods[M3DS_NET_MAX_MODS][64];
} M3dsNetDecodedConnect;

typedef struct M3dsNetStreamBegin {
  int32_t id;
  int32_t total;
  uint8_t type;
} M3dsNetStreamBegin;

typedef struct M3dsNetStreamChunk {
  int32_t id;
  uint16_t len;
  const uint8_t *data;
} M3dsNetStreamChunk;

typedef enum M3dsNetIoResult {
  M3DS_NET_IO_TIMEOUT = -2,
  M3DS_NET_IO_ERROR = -1,
  M3DS_NET_IO_EOF = 0
} M3dsNetIoResult;

typedef struct M3dsNetTransport {
  void *user_data;
  bool (*tcp_connect)(void *user_data, const char *host, uint16_t port, uint32_t timeout_ms);
  int (*tcp_send)(void *user_data, const uint8_t *data, size_t len);
  int (*tcp_recv)(void *user_data, uint8_t *out_buf, size_t max_len, uint32_t timeout_ms);
  void (*tcp_close)(void *user_data);

  bool (*udp_open)(void *user_data, const char *host, uint16_t port);
  int (*udp_send)(void *user_data, const uint8_t *data, size_t len);
  int (*udp_recv)(void *user_data, uint8_t *out_buf, size_t max_len, uint32_t timeout_ms);
  void (*udp_close)(void *user_data);
} M3dsNetTransport;

typedef enum M3dsNetHandshakeStatus {
  M3DS_HANDSHAKE_OK_WORLD_STREAM = 0,
  M3DS_HANDSHAKE_SERVER_REJECTED,
  M3DS_HANDSHAKE_DISCONNECTED,
  M3DS_HANDSHAKE_TIMEOUT,
  M3DS_HANDSHAKE_PROTOCOL_ERROR,
  M3DS_HANDSHAKE_TRANSPORT_ERROR,
  M3DS_HANDSHAKE_INVALID_ARG
} M3dsNetHandshakeStatus;

typedef struct M3dsNetHandshakeResult {
  M3dsNetHandshakeStatus status;
  int32_t connection_id;
  bool udp_registered;
  int32_t stream_id;
  int32_t stream_total;
  uint8_t stream_type;
  uint8_t first_server_packet_id;
  size_t first_server_payload_len;
  uint32_t pings_answered;
} M3dsNetHandshakeResult;

/* Pure IEEE 802.3 CRC32 used by Mindustry ConnectPacket. */
uint32_t m3ds_net_crc32(const uint8_t *data, size_t len);

/* Pure C11 bounds-checked LZ4 raw block decompressor (for compression == 1 packets). */
bool m3ds_net_lz4_decompress(
    const uint8_t *src,
    size_t src_len,
    uint8_t *dst,
    size_t dst_cap,
    size_t *out_len);

/* ArcNet 2-byte big-endian TCP length framing helpers. */
bool m3ds_net_tcp_frame_encode(
    const uint8_t *payload,
    size_t payload_len,
    uint8_t *out_frame,
    size_t out_cap,
    size_t *out_frame_len);
bool m3ds_net_tcp_frame_peek_len(const uint8_t *buf, size_t buf_len, uint16_t *out_payload_len);

/* ArcNet FrameworkMessage wire codec (0xFE + framework id). */
bool m3ds_net_encode_framework(
    const M3dsNetFrameworkMsg *msg,
    uint8_t *out_buf,
    size_t out_cap,
    size_t *out_len);
bool m3ds_net_decode_framework(
    const uint8_t *buf,
    size_t buf_len,
    M3dsNetFrameworkMsg *out_msg);

/* UDP server discovery request & NetworkIO.writeServerData() response parser. */
bool m3ds_net_encode_discover_host(uint8_t *out_buf, size_t out_cap, size_t *out_len);
bool m3ds_net_decode_host_info(const uint8_t *buf, size_t buf_len, M3dsNetHostInfo *out_info);

/* Mindustry PacketSerializer envelope ([u8 id][u16_be len][u8 compression][data...]). */
bool m3ds_net_encode_packet_raw(
    uint8_t packet_id,
    const uint8_t *payload,
    size_t payload_len,
    uint8_t *out_buf,
    size_t out_cap,
    size_t *out_len);
bool m3ds_net_decode_packet(
    const uint8_t *buf,
    size_t buf_len,
    uint8_t *out_packet_id,
    uint8_t *out_payload,
    size_t out_payload_cap,
    size_t *out_payload_len);

/* Mindustry v160.8 ConnectPacket codec. */
bool m3ds_net_encode_connect_packet(
    const M3dsNetConnectPacket *pkt,
    uint8_t *out_buf,
    size_t out_cap,
    size_t *out_len);
bool m3ds_net_decode_connect_packet(
    const uint8_t *buf,
    size_t buf_len,
    M3dsNetDecodedConnect *out_pkt);

/* StreamBegin / StreamChunk payload decoders. */
bool m3ds_net_decode_stream_begin(
    const uint8_t *payload,
    size_t payload_len,
    M3dsNetStreamBegin *out_begin);
bool m3ds_net_decode_stream_chunk(
    const uint8_t *payload,
    size_t payload_len,
    M3dsNetStreamChunk *out_chunk);

/* Execute a UDP DiscoverHost ping over transport. */
bool m3ds_net_ping_host(
    const M3dsNetTransport *transport,
    const char *host,
    uint16_t port,
    uint32_t timeout_ms,
    M3dsNetHostInfo *out_info);

/* Execute Arc RegisterTCP -> RegisterUDP -> ConnectPacket handshake up to StreamBegin or reject. */
bool m3ds_net_handshake(
    const M3dsNetTransport *transport,
    const char *host,
    uint16_t port,
    uint32_t timeout_ms,
    const M3dsNetConnectPacket *connect_pkt,
    M3dsNetHandshakeResult *out_result);

#ifdef __cplusplus
}
#endif

#endif
