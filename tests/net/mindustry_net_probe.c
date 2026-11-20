#define _POSIX_C_SOURCE 200112L

#include "net/mindustry_net.h"

#include <errno.h>
#include <netdb.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

typedef struct PosixNetCtx {
  int tcp_fd;
  int udp_fd;
} PosixNetCtx;

static int wait_readable(int fd, uint32_t timeout_ms) {
  struct pollfd pfd;
  pfd.fd = fd;
  pfd.events = POLLIN;
  pfd.revents = 0;
  int rc = poll(&pfd, 1U, (int)timeout_ms);
  if (rc == 0) {
    return M3DS_NET_IO_TIMEOUT;
  }
  return rc < 0 ? M3DS_NET_IO_ERROR : 1;
}

static bool posix_tcp_connect(
    void *user_data, const char *host, uint16_t port, uint32_t timeout_ms) {
  (void)timeout_ms;
  PosixNetCtx *ctx = (PosixNetCtx *)user_data;
  char port_str[16];
  snprintf(port_str, sizeof(port_str), "%u", (unsigned int)port);
  struct addrinfo hints;
  memset(&hints, 0, sizeof(hints));
  hints.ai_family = AF_INET;
  hints.ai_socktype = SOCK_STREAM;
  struct addrinfo *res = NULL;
  if (getaddrinfo(host, port_str, &hints, &res) != 0 || !res) {
    return false;
  }
  int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
  if (fd < 0 || connect(fd, res->ai_addr, res->ai_addrlen) != 0) {
    if (fd >= 0) {
      close(fd);
    }
    freeaddrinfo(res);
    return false;
  }
  freeaddrinfo(res);
  ctx->tcp_fd = fd;
  return true;
}

static int posix_tcp_send(void *user_data, const uint8_t *data, size_t len) {
  PosixNetCtx *ctx = (PosixNetCtx *)user_data;
  ssize_t n = send(ctx->tcp_fd, data, len, 0);
  return n < 0 ? M3DS_NET_IO_ERROR : (int)n;
}

static int posix_tcp_recv(
    void *user_data, uint8_t *out_buf, size_t max_len, uint32_t timeout_ms) {
  PosixNetCtx *ctx = (PosixNetCtx *)user_data;
  int ready = wait_readable(ctx->tcp_fd, timeout_ms);
  if (ready <= 0) {
    return ready;
  }
  ssize_t n = recv(ctx->tcp_fd, out_buf, max_len, 0);
  if (n == 0) {
    return M3DS_NET_IO_EOF;
  }
  return n < 0 ? M3DS_NET_IO_ERROR : (int)n;
}

static void posix_tcp_close(void *user_data) {
  PosixNetCtx *ctx = (PosixNetCtx *)user_data;
  if (ctx->tcp_fd >= 0) {
    close(ctx->tcp_fd);
    ctx->tcp_fd = -1;
  }
}

static bool posix_udp_open(void *user_data, const char *host, uint16_t port) {
  PosixNetCtx *ctx = (PosixNetCtx *)user_data;
  char port_str[16];
  snprintf(port_str, sizeof(port_str), "%u", (unsigned int)port);
  struct addrinfo hints;
  memset(&hints, 0, sizeof(hints));
  hints.ai_family = AF_INET;
  hints.ai_socktype = SOCK_DGRAM;
  struct addrinfo *res = NULL;
  if (getaddrinfo(host, port_str, &hints, &res) != 0 || !res) {
    return false;
  }
  int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
  if (fd < 0 || connect(fd, res->ai_addr, res->ai_addrlen) != 0) {
    if (fd >= 0) {
      close(fd);
    }
    freeaddrinfo(res);
    return false;
  }
  freeaddrinfo(res);
  ctx->udp_fd = fd;
  return true;
}

static int posix_udp_send(void *user_data, const uint8_t *data, size_t len) {
  PosixNetCtx *ctx = (PosixNetCtx *)user_data;
  ssize_t n = send(ctx->udp_fd, data, len, 0);
  return n < 0 ? M3DS_NET_IO_ERROR : (int)n;
}

static int posix_udp_recv(
    void *user_data, uint8_t *out_buf, size_t max_len, uint32_t timeout_ms) {
  PosixNetCtx *ctx = (PosixNetCtx *)user_data;
  int ready = wait_readable(ctx->udp_fd, timeout_ms);
  if (ready <= 0) {
    return ready;
  }
  ssize_t n = recv(ctx->udp_fd, out_buf, max_len, 0);
  return n < 0 ? M3DS_NET_IO_ERROR : (int)n;
}

static void posix_udp_close(void *user_data) {
  PosixNetCtx *ctx = (PosixNetCtx *)user_data;
  if (ctx->udp_fd >= 0) {
    close(ctx->udp_fd);
    ctx->udp_fd = -1;
  }
}

int main(int argc, char *argv[]) {
  if (argc < 4) {
    fprintf(stderr, "Usage: %s <ping|handshake> <host> <port> [build] [name]\n", argv[0]);
    return 2;
  }

  const char *mode = argv[1];
  const char *host = argv[2];
  uint16_t port = (uint16_t)atoi(argv[3]);
  int32_t build = argc >= 5 ? (int32_t)atoi(argv[4]) : M3DS_NET_UPSTREAM_BUILD;
  const char *name = argc >= 6 ? argv[5] : "Min3DStryProbe";

  PosixNetCtx ctx = {-1, -1};
  M3dsNetTransport tr = {
      &ctx,
      posix_tcp_connect,
      posix_tcp_send,
      posix_tcp_recv,
      posix_tcp_close,
      posix_udp_open,
      posix_udp_send,
      posix_udp_recv,
      posix_udp_close};

  if (strcmp(mode, "ping") == 0) {
    M3dsNetHostInfo info;
    if (!m3ds_net_ping_host(&tr, host, port, 3000U, &info)) {
      fprintf(stderr, "PING_FAILED\n");
      return 1;
    }
    printf(
        "PING_OK name=%s map=%s players=%ld wave=%ld version=%ld vertype=%s limit=%ld port=%u\n",
        info.name,
        info.map_name,
        (long)info.players,
        (long)info.wave,
        (long)info.version,
        info.version_type,
        (long)info.player_limit,
        (unsigned int)info.port);
    return 0;
  }

  if (strcmp(mode, "handshake") == 0) {
    M3dsNetConnectPacket pkt;
    memset(&pkt, 0, sizeof(pkt));
    pkt.version = build;
    pkt.version_type = M3DS_NET_UPSTREAM_VERSION_TYPE;
    pkt.name = name;
    pkt.locale = "en_US";
    pkt.usid = "m3ds0001";
    for (uint8_t i = 0U; i < 8U; ++i) {
      pkt.uuid_raw[i] = (uint8_t)(0x41U + i);
    }
    pkt.mobile = true;
    pkt.color = 0xFFB841FFU;
    pkt.mod_count = 0U;

    M3dsNetHandshakeResult res;
    (void)m3ds_net_handshake(&tr, host, port, 5000U, &pkt, &res);
    printf(
        "HANDSHAKE status=%d conn_id=%ld udp=%d stream_id=%ld stream_total=%ld stream_type=%u first_pkt=%u payload_len=%zu pings=%lu\n",
        (int)res.status,
        (long)res.connection_id,
        res.udp_registered ? 1 : 0,
        (long)res.stream_id,
        (long)res.stream_total,
        (unsigned int)res.stream_type,
        (unsigned int)res.first_server_packet_id,
        res.first_server_payload_len,
        (unsigned long)res.pings_answered);
    return 0;
  }

  fprintf(stderr, "Unknown mode: %s\n", mode);
  return 2;
}
