/*
 * Min3DStry libctru SOC networking transport implementation.
 * Provenance: Mindustry v8 build 160.8 (19a0f40d44514f4802a8b79770456e7e32e34da0, GPLv3)
 * and Arc (7445105cd2683daf5b9ac7033ebb07265d3e814d, Apache-2.0).
 */
#include "net_3ds.h"

#include <3ds.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <malloc.h>
#include <netdb.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define M3DS_SOC_ALIGN 0x1000U
#define M3DS_SOC_BUFFERSIZE 0x100000U

static int wait_fd_readable(int fd, uint32_t timeout_ms) {
  struct pollfd pfd;
  pfd.fd = fd;
  pfd.events = POLLIN;
  pfd.revents = 0;
  int rc = poll(&pfd, 1U, (int)timeout_ms);
  if (rc == 0) {
    return M3DS_NET_IO_TIMEOUT;
  }
  if (rc < 0) {
    return M3DS_NET_IO_ERROR;
  }
  return 1;
}

static void n3ds_tcp_close(void *user_data);
static void n3ds_udp_close(void *user_data);

static bool n3ds_tcp_connect(
    void *user_data,
    const char *host,
    uint16_t port,
    uint32_t timeout_ms) {
  M3dsNet3dsContext *ctx = (M3dsNet3dsContext *)user_data;
  if (!ctx || !ctx->soc_initialized || !host) {
    return false;
  }
  /* Prevent descriptor leaks if a caller reconnects on the same context. */
  n3ds_tcp_close(ctx);

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
  if (fd < 0) {
    freeaddrinfo(res);
    return false;
  }

  int orig_flags = fcntl(fd, F_GETFL, 0);
  bool nonblock_set = (orig_flags >= 0 && fcntl(fd, F_SETFL, orig_flags | O_NONBLOCK) == 0);
  int conn_rc = connect(fd, res->ai_addr, res->ai_addrlen);
  int conn_error = conn_rc == 0 ? 0 : errno;
  freeaddrinfo(res);

  if (conn_rc != 0) {
    // Azahar on Windows reports the pending Winsock connect as EWOULDBLOCK.
    if (!nonblock_set || (conn_error != EINPROGRESS && conn_error != EAGAIN &&
                         conn_error != EWOULDBLOCK)) {
      close(fd);
      return false;
    }
    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = POLLOUT;
    pfd.revents = 0;
    int poll_rc = poll(&pfd, 1U, timeout_ms > 0U ? (int)timeout_ms : 5000);
    if (poll_rc <= 0 || (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
      close(fd);
      return false;
    }
    int so_err = 0;
    socklen_t so_len = (socklen_t)sizeof(so_err);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &so_err, &so_len) != 0 || so_err != 0) {
      close(fd);
      return false;
    }
  }

  if (nonblock_set) {
    (void)fcntl(fd, F_SETFL, orig_flags);
  }
  ctx->tcp_fd = fd;
  return true;
}

static int n3ds_tcp_send(void *user_data, const uint8_t *data, size_t len) {
  M3dsNet3dsContext *ctx = (M3dsNet3dsContext *)user_data;
  if (!ctx || ctx->tcp_fd < 0 || !data) {
    return M3DS_NET_IO_ERROR;
  }
  ssize_t n = send(ctx->tcp_fd, data, len, 0);
  if (n < 0) {
    return M3DS_NET_IO_ERROR;
  }
  return (int)n;
}

static int n3ds_tcp_recv(
    void *user_data,
    uint8_t *out_buf,
    size_t max_len,
    uint32_t timeout_ms) {
  M3dsNet3dsContext *ctx = (M3dsNet3dsContext *)user_data;
  if (!ctx || ctx->tcp_fd < 0 || !out_buf || max_len == 0U) {
    return M3DS_NET_IO_ERROR;
  }
  int ready = wait_fd_readable(ctx->tcp_fd, timeout_ms);
  if (ready <= 0) {
    return ready;
  }
  ssize_t n = recv(ctx->tcp_fd, out_buf, max_len, 0);
  if (n == 0) {
    return M3DS_NET_IO_EOF;
  }
  if (n < 0) {
    return (errno == EAGAIN || errno == EWOULDBLOCK) ? M3DS_NET_IO_TIMEOUT : M3DS_NET_IO_ERROR;
  }
  return (int)n;
}

static void n3ds_tcp_close(void *user_data) {
  M3dsNet3dsContext *ctx = (M3dsNet3dsContext *)user_data;
  if (ctx && ctx->tcp_fd >= 0) {
    close(ctx->tcp_fd);
    ctx->tcp_fd = -1;
  }
}

static bool n3ds_udp_open(void *user_data, const char *host, uint16_t port) {
  M3dsNet3dsContext *ctx = (M3dsNet3dsContext *)user_data;
  if (!ctx || !ctx->soc_initialized || !host) {
    return false;
  }
  /* Prevent descriptor leaks if UDP is reopened on the same context. */
  n3ds_udp_close(ctx);

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
  if (fd < 0) {
    freeaddrinfo(res);
    return false;
  }
  if (connect(fd, res->ai_addr, res->ai_addrlen) != 0) {
    close(fd);
    freeaddrinfo(res);
    return false;
  }
  freeaddrinfo(res);
  ctx->udp_fd = fd;
  return true;
}

static int n3ds_udp_send(void *user_data, const uint8_t *data, size_t len) {
  M3dsNet3dsContext *ctx = (M3dsNet3dsContext *)user_data;
  if (!ctx || ctx->udp_fd < 0 || !data) {
    return M3DS_NET_IO_ERROR;
  }
  ssize_t n = send(ctx->udp_fd, data, len, 0);
  if (n < 0) {
    return M3DS_NET_IO_ERROR;
  }
  return (int)n;
}

static int n3ds_udp_recv(
    void *user_data,
    uint8_t *out_buf,
    size_t max_len,
    uint32_t timeout_ms) {
  M3dsNet3dsContext *ctx = (M3dsNet3dsContext *)user_data;
  if (!ctx || ctx->udp_fd < 0 || !out_buf || max_len == 0U) {
    return M3DS_NET_IO_ERROR;
  }
  int ready = wait_fd_readable(ctx->udp_fd, timeout_ms);
  if (ready <= 0) {
    return ready;
  }
  ssize_t n = recv(ctx->udp_fd, out_buf, max_len, 0);
  if (n < 0) {
    return (errno == EAGAIN || errno == EWOULDBLOCK) ? M3DS_NET_IO_TIMEOUT : M3DS_NET_IO_ERROR;
  }
  return (int)n;
}

static void n3ds_udp_close(void *user_data) {
  M3dsNet3dsContext *ctx = (M3dsNet3dsContext *)user_data;
  if (ctx && ctx->udp_fd >= 0) {
    close(ctx->udp_fd);
    ctx->udp_fd = -1;
  }
}

bool m3ds_net_3ds_init(M3dsNetTransport *transport, M3dsNet3dsContext *ctx) {
  if (!transport || !ctx) {
    return false;
  }
  memset(ctx, 0, sizeof(*ctx));
  ctx->tcp_fd = -1;
  ctx->udp_fd = -1;

  ctx->soc_buffer = memalign(M3DS_SOC_ALIGN, M3DS_SOC_BUFFERSIZE);
  if (!ctx->soc_buffer) {
    return false;
  }
  if (R_FAILED(socInit((u32 *)ctx->soc_buffer, M3DS_SOC_BUFFERSIZE))) {
    free(ctx->soc_buffer);
    ctx->soc_buffer = NULL;
    return false;
  }

  ctx->soc_initialized = true;
  transport->user_data = ctx;
  transport->tcp_connect = n3ds_tcp_connect;
  transport->tcp_send = n3ds_tcp_send;
  transport->tcp_recv = n3ds_tcp_recv;
  transport->tcp_close = n3ds_tcp_close;
  transport->udp_open = n3ds_udp_open;
  transport->udp_send = n3ds_udp_send;
  transport->udp_recv = n3ds_udp_recv;
  transport->udp_close = n3ds_udp_close;
  return true;
}

void m3ds_net_3ds_exit(M3dsNet3dsContext *ctx) {
  if (!ctx) {
    return;
  }
  n3ds_tcp_close(ctx);
  n3ds_udp_close(ctx);
  if (ctx->soc_initialized) {
    socExit();
    ctx->soc_initialized = false;
  }
  if (ctx->soc_buffer) {
    free(ctx->soc_buffer);
    ctx->soc_buffer = NULL;
  }
}
