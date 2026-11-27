#if defined(__linux__) && !defined(_DEFAULT_SOURCE)
#define _DEFAULT_SOURCE
#endif

#include "net/mindustry_host_socket.h"

#include <limits.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
typedef SOCKET HostFd;
typedef int HostAddressLength;
#define HOST_INVALID INVALID_SOCKET
#define host_close closesocket
static bool would_block(void) { return WSAGetLastError() == WSAEWOULDBLOCK; }
static bool nonblocking(HostFd fd) {
  u_long option = 1;
  return ioctlsocket(fd, FIONBIO, &option) == 0;
}
#else
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>
typedef int HostFd;
typedef socklen_t HostAddressLength;
#define HOST_INVALID (-1)
#define host_close close
static bool would_block(void) { return errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR; }
static bool nonblocking(HostFd fd) {
  int flags = fcntl(fd, F_GETFL, 0);
  return flags >= 0 && fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}
#endif

#ifdef MSG_NOSIGNAL
#define HOST_TCP_FLAGS MSG_NOSIGNAL
#else
#define HOST_TCP_FLAGS 0
#endif

/* Stock ArcNetProvider's client object buffer size plus write headroom. */
#define HOST_QUEUE_SIZE (25000U + 16000U)

typedef struct HostPeer {
  HostFd fd;
  M3dsNetHostPeer registration;
  struct sockaddr_in address, udp;
  size_t received, sent, queued;
  bool closing;
  uint8_t input[M3DS_HOST_MAX_INCOMING_FRAME + 2U];
  uint8_t output[HOST_QUEUE_SIZE];
} HostPeer;

struct M3dsNetHostSocket {
  HostFd tcp, udp, multicast;
  HostPeer **peers;
  unsigned count, next;
  uint32_t id;
  struct sockaddr_in discovery;
  bool discovery_pending, prefer_udp, prefer_multicast;
  uint8_t udp_input[M3DS_HOST_MAX_INCOMING_FRAME + 1U];
};

static HostFd bind_socket(uint16_t port, int kind, bool reuse_address) {
  HostFd fd = socket(AF_INET, kind, 0);
  if (fd == HOST_INVALID) return HOST_INVALID;
  int reuse = 1;
  if (reuse_address) (void)setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, (const char *)&reuse, sizeof(reuse));
  struct sockaddr_in address = {0};
  address.sin_family = AF_INET;
  address.sin_port = htons(port);
  address.sin_addr.s_addr = htonl(INADDR_ANY);
  if (bind(fd, (struct sockaddr *)&address, sizeof(address)) != 0 || !nonblocking(fd)) {
    host_close(fd);
    return HOST_INVALID;
  }
  return fd;
}

M3dsNetHostSocket *m3ds_net_host_socket_open(uint16_t port, unsigned peers, uint32_t id_seed) {
  if (!port || !peers || peers > INT_MAX) return NULL;
  M3dsNetHostSocket *host = calloc(1, sizeof(*host));
  if (!host) return NULL;
  host->tcp = host->udp = host->multicast = HOST_INVALID;
  host->peers = calloc(peers, sizeof(*host->peers));
  if (!host->peers) {
    free(host);
    return NULL;
  }
  host->count = peers;
  host->id = id_seed;
  host->tcp = bind_socket(port, SOCK_STREAM, true);
  host->udp = bind_socket(port, SOCK_DGRAM, false);
  if (host->tcp == HOST_INVALID || host->udp == HOST_INVALID || listen(host->tcp, (int)peers) != 0) {
    m3ds_net_host_socket_close(host);
    return NULL;
  }
  return host;
}

bool m3ds_net_host_socket_listen_multicast(M3dsNetHostSocket *host, const char *group, uint16_t port) {
#ifdef IP_ADD_MEMBERSHIP
  if (!host || !group || !port || host->multicast != HOST_INVALID) return false;
  struct ip_mreq membership = {0};
  if (inet_pton(AF_INET, group, &membership.imr_multiaddr) != 1) return false;
  membership.imr_interface.s_addr = htonl(INADDR_ANY);
  HostFd fd = bind_socket(port, SOCK_DGRAM, true);
  if (fd == HOST_INVALID) return false;
  if (setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, (const char *)&membership, sizeof(membership)) != 0) {
    host_close(fd);
    return false;
  }
  host->multicast = fd;
  return true;
#else
  (void)host;
  (void)group;
  (void)port;
  return false;
#endif
}

static void release_peer(M3dsNetHostSocket *host, unsigned index) {
  HostPeer *peer = host->peers[index];
  host_close(peer->fd);
  free(peer);
  host->peers[index] = NULL;
}

void m3ds_net_host_socket_close(M3dsNetHostSocket *host) {
  if (!host) return;
  for (unsigned i = 0; i < host->count; ++i) {
    if (host->peers[i]) release_peer(host, i);
  }
  if (host->tcp != HOST_INVALID) host_close(host->tcp);
  if (host->udp != HOST_INVALID) host_close(host->udp);
  if (host->multicast != HOST_INVALID) host_close(host->multicast);
  free(host->peers);
  free(host);
}

static void accept_peer(M3dsNetHostSocket *host) {
  unsigned index = 0;
  while (index < host->count && host->peers[index]) ++index;
  if (index == host->count) return;
  struct sockaddr_in address;
  HostAddressLength size = sizeof(address);
  HostFd fd = accept(host->tcp, (struct sockaddr *)&address, &size);
  if (fd == HOST_INVALID) return;
  if (!nonblocking(fd)) {
    host_close(fd);
    return;
  }
#ifdef SO_NOSIGPIPE
  int enabled = 1;
  (void)setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, (const char *)&enabled, sizeof(enabled));
#endif
  HostPeer *peer = calloc(1, sizeof(*peer));
  if (!peer) {
    host_close(fd);
    return;
  }
  peer->fd = fd;
  peer->address = address;
  host->id = host->id * UINT32_C(1664525) + UINT32_C(1013904223);
  m3ds_net_host_begin(&peer->registration, (int32_t)host->id, peer->output);
  peer->queued = M3DS_HOST_REGISTRATION_FRAME_SIZE;
  host->peers[index] = peer;
}

static M3dsNetHostEvent peer_event(M3dsNetHostEventKind kind, unsigned index, const HostPeer *peer, size_t size) {
  return (M3dsNetHostEvent){kind, index, peer->address.sin_addr.s_addr, size};
}

static M3dsNetHostEvent poll_datagram(M3dsNetHostSocket *host, HostFd fd, uint8_t *out) {
  struct sockaddr_in address;
  HostAddressLength addr_size = sizeof(address);
  uint8_t *payload = host->udp_input;
  int n = (int)recvfrom(fd, (char *)payload, sizeof(host->udp_input), 0,
                         (struct sockaddr *)&address, &addr_size);
  if (n <= 0 || (size_t)n > M3DS_HOST_MAX_INCOMING_FRAME) return (M3dsNetHostEvent){0};
  M3dsNetFrameworkMsg message;
  bool framework = m3ds_net_decode_framework(payload, (size_t)n, &message);
  if (framework && message.type == M3DS_FW_DISCOVER_HOST && n == 2) {
    host->discovery = address;
    host->discovery_pending = true;
    return (M3dsNetHostEvent){.kind = M3DS_HOST_EVENT_DISCOVERY, .ipv4 = address.sin_addr.s_addr};
  }
  if (fd != host->udp) return (M3dsNetHostEvent){0};
  for (unsigned i = 0; i < host->count; ++i) {
    HostPeer *peer = host->peers[i];
    if (!peer || peer->address.sin_addr.s_addr != address.sin_addr.s_addr) continue;
    if (peer->closing) continue;
    if (peer->registration.registered) {
      if (peer->udp.sin_port == address.sin_port) {
        if (framework && message.type == M3DS_FW_REGISTER_UDP) return (M3dsNetHostEvent){0};
        memcpy(out, payload, (size_t)n);
        return peer_event(M3DS_HOST_EVENT_UDP, i, peer, (size_t)n);
      }
      continue;
    }
    if (peer->queued == 0 && m3ds_net_host_register_udp(&peer->registration, payload, (size_t)n, peer->output)) {
      peer->udp = address;
      peer->queued = M3DS_HOST_REGISTRATION_FRAME_SIZE;
      return peer_event(M3DS_HOST_EVENT_CONNECTED, i, peer, 0);
    }
  }
  return (M3dsNetHostEvent){0};
}

static M3dsNetHostEvent poll_udp(M3dsNetHostSocket *host, uint8_t *out) {
  if (host->multicast == HOST_INVALID) return poll_datagram(host, host->udp, out);
  host->prefer_multicast = !host->prefer_multicast;
  HostFd first = host->prefer_multicast ? host->multicast : host->udp;
  HostFd second = host->prefer_multicast ? host->udp : host->multicast;
  M3dsNetHostEvent event = poll_datagram(host, first, out);
  return event.kind == M3DS_HOST_EVENT_NONE ? poll_datagram(host, second, out) : event;
}

static M3dsNetHostEvent drop_peer(M3dsNetHostSocket *host, unsigned index) {
  HostPeer *peer = host->peers[index];
  M3dsNetHostEvent event = peer_event(M3DS_HOST_EVENT_DISCONNECTED, index, peer, 0);
  if (!peer->registration.registered) event.kind = M3DS_HOST_EVENT_NONE;
  release_peer(host, index);
  return event;
}

static M3dsNetHostEvent poll_peer(M3dsNetHostSocket *host, unsigned i, uint8_t *out) {
  HostPeer *peer = host->peers[i];
  if (peer->sent < peer->queued) {
    int n = (int)send(peer->fd, (const char *)peer->output + peer->sent,
                     (int)(peer->queued - peer->sent), HOST_TCP_FLAGS);
    if (n < 0 && !would_block()) return drop_peer(host, i);
    if (n > 0) peer->sent += (size_t)n;
    if (peer->sent == peer->queued) peer->sent = peer->queued = 0;
  }
  if (peer->closing) {
    if (peer->queued == 0) return drop_peer(host, i);
    return (M3dsNetHostEvent){0};
  }
  const uint8_t *payload;
  size_t size, consumed;
  M3dsNetHostFrameResult state =
      m3ds_net_host_tcp_payload(peer->input, peer->received, &payload, &size, &consumed);
  if (state == M3DS_HOST_FRAME_INCOMPLETE) {
    size_t room = sizeof(peer->input) - peer->received;
    int n = (int)recv(peer->fd, (char *)peer->input + peer->received, (int)room, 0);
    if (n == 0 || (n < 0 && !would_block())) return drop_peer(host, i);
    if (n > 0) {
      peer->received += (size_t)n;
      state = m3ds_net_host_tcp_payload(peer->input, peer->received, &payload, &size, &consumed);
    }
  }
  if (state == M3DS_HOST_FRAME_INVALID) return drop_peer(host, i);
  if (state != M3DS_HOST_FRAME_READY) return (M3dsNetHostEvent){0};
  if (!peer->registration.registered) return drop_peer(host, i);
  memcpy(out, payload, size);
  peer->received -= consumed;
  memmove(peer->input, peer->input + consumed, peer->received);
  return peer_event(M3DS_HOST_EVENT_TCP, i, peer, size);
}

M3dsNetHostEvent m3ds_net_host_socket_poll(M3dsNetHostSocket *host, uint8_t *payload, size_t capacity) {
  if (!host || !payload || capacity < M3DS_HOST_MAX_INCOMING_FRAME) return (M3dsNetHostEvent){0};
  host->discovery_pending = false;
  accept_peer(host);
  host->prefer_udp = !host->prefer_udp;
  if (host->prefer_udp) {
    M3dsNetHostEvent udp = poll_udp(host, payload);
    if (udp.kind != M3DS_HOST_EVENT_NONE) return udp;
  }
  for (unsigned step = 0; step < host->count; ++step) {
    unsigned index = host->next;
    host->next = (index + 1U) % host->count;
    if (host->peers[index]) {
      M3dsNetHostEvent event = poll_peer(host, index, payload);
      if (event.kind != M3DS_HOST_EVENT_NONE) return event;
    }
  }
  return host->prefer_udp ? (M3dsNetHostEvent){0} : poll_udp(host, payload);
}

bool m3ds_net_host_socket_send(M3dsNetHostSocket *host, unsigned index, const uint8_t *payload,
                               size_t size, bool reliable) {
  if (!host || index >= host->count || !payload || !size || size > M3DS_NET_MAX_PACKET_SIZE) return false;
  HostPeer *peer = host->peers[index];
  if (!peer || peer->closing || !peer->registration.registered) return false;
  if (!reliable) {
    int n = (int)sendto(host->udp, (const char *)payload, (int)size, 0,
                       (const struct sockaddr *)&peer->udp, sizeof(peer->udp));
    return n >= 0 && (size_t)n == size;
  }
  size_t pending = peer->queued - peer->sent;
  if (pending + size + 2U > sizeof(peer->output)) return false;
  if (peer->sent) memmove(peer->output, peer->output + peer->sent, pending);
  peer->sent = 0;
  peer->queued = pending;
  size_t framed;
  if (!m3ds_net_tcp_frame_encode(payload, size, peer->output + pending, sizeof(peer->output) - pending, &framed)) {
    return false;
  }
  peer->queued += framed;
  return true;
}

void m3ds_net_host_socket_disconnect(M3dsNetHostSocket *host, unsigned peer) {
  if (host && peer < host->count && host->peers[peer]) host->peers[peer]->closing = true;
}

bool m3ds_net_host_socket_advertise(M3dsNetHostSocket *host, const uint8_t *data, size_t size) {
  if (!host || !data || !size || size > 512U || !host->discovery_pending) return false;
  host->discovery_pending = false;
  int n = (int)sendto(host->udp, (const char *)data, (int)size, 0,
                     (const struct sockaddr *)&host->discovery, sizeof(host->discovery));
  return n >= 0 && (size_t)n == size;
}
