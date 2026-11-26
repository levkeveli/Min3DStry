#ifndef MIN3DSTRY_NET_MINDUSTRY_HOST_SOCKET_H
#define MIN3DSTRY_NET_MINDUSTRY_HOST_SOCKET_H

#include "mindustry_host_transport.h"

typedef struct M3dsNetHostSocket M3dsNetHostSocket;

typedef enum M3dsNetHostEventKind {
  M3DS_HOST_EVENT_NONE = 0,
  M3DS_HOST_EVENT_CONNECTED,
  M3DS_HOST_EVENT_TCP,
  M3DS_HOST_EVENT_UDP,
  M3DS_HOST_EVENT_DISCONNECTED,
  M3DS_HOST_EVENT_DISCOVERY
} M3dsNetHostEventKind;

typedef struct M3dsNetHostEvent {
  M3dsNetHostEventKind kind;
  unsigned peer; /* Slot for connected, packet and disconnected events. */
  uint32_t ipv4; /* Network byte order, also available on discovery. */
  size_t size;
} M3dsNetHostEvent;

/* The caller owns the platform socket service. `id_seed` initializes the ArcNet ID sequence. */
M3dsNetHostSocket *m3ds_net_host_socket_open(uint16_t port, unsigned peers, uint32_t id_seed);
void m3ds_net_host_socket_close(M3dsNetHostSocket *host);

/* Non-blocking. Give the caller enough space for one stock incoming payload. */
M3dsNetHostEvent m3ds_net_host_socket_poll(M3dsNetHostSocket *host, uint8_t *payload, size_t capacity);

/* TCP queues whole payloads. A full queue returns false and leaves it intact. */
bool m3ds_net_host_socket_send(M3dsNetHostSocket *host, unsigned peer, const uint8_t *payload,
                               size_t size, bool reliable);
void m3ds_net_host_socket_disconnect(M3dsNetHostSocket *host, unsigned peer);
/* Respond to the current discovery event before polling again. */
bool m3ds_net_host_socket_advertise(M3dsNetHostSocket *host, const uint8_t *data, size_t size);

#endif
