#include "m3ds_host_net_bridge.h"
#include "net/mindustry_host_socket.h"
#include "net_3ds.h"

static M3dsNet3dsContext context;
static M3dsNetHostSocket *host;

int32_t m3ds_host_net_open(int32_t port, int32_t peers, uint32_t id_seed) {
    if (host || port <= 0 || port > UINT16_MAX || peers <= 0) return 0;
    M3dsNetTransport client_callbacks;
    if (!m3ds_net_3ds_init(&client_callbacks, &context)) return 0;
    host = m3ds_net_host_socket_open((uint16_t)port, (unsigned)peers, id_seed);
    if (!host) m3ds_net_3ds_exit(&context);
    return host != NULL;
}

int32_t m3ds_host_net_multicast(const char *group, int32_t port) {
    return port > 0 && port <= UINT16_MAX &&
        m3ds_net_host_socket_listen_multicast(host, group, (uint16_t)port);
}

void m3ds_host_net_close(void) {
    if (!host) return;
    m3ds_net_host_socket_close(host);
    host = NULL;
    m3ds_net_3ds_exit(&context);
}

int32_t m3ds_host_net_poll(void *payload, int32_t capacity, uint32_t *details) {
    if (!details || capacity < 0) return M3DS_HOST_EVENT_NONE;
    M3dsNetHostEvent event = m3ds_net_host_socket_poll(host, payload, (size_t)capacity);
    details[0] = event.peer;
    details[1] = event.ipv4;
    details[2] = (uint32_t)event.size;
    return event.kind;
}

int32_t m3ds_host_net_send(int32_t peer, const void *payload, int32_t size, int32_t reliable) {
    return peer >= 0 && size > 0 &&
        m3ds_net_host_socket_send(host, (unsigned)peer, payload, (size_t)size, reliable != 0);
}

void m3ds_host_net_disconnect(int32_t peer) {
    if (peer >= 0) m3ds_net_host_socket_disconnect(host, (unsigned)peer);
}

int32_t m3ds_host_net_advertise(const void *payload, int32_t size) {
    return size > 0 && m3ds_net_host_socket_advertise(host, payload, (size_t)size);
}
