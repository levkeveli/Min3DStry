#include "m3ds_community_probe.h"
#include "net_3ds.h"

#include <3ds.h>
#include <string.h>

typedef struct {
    Thread worker;
    char host[256];
    uint16_t port;
    uint8_t packet[512];
    int size;
    int32_t ping;
} ServerProbe;

static M3dsNet3dsContext soc;
static M3dsNetTransport transport;
static int32_t priority;
static ServerProbe probes[M3DS_COMMUNITY_PROBE_SLOTS];

static void probe_server(void *context) {
    ServerProbe *probe = context;
    // SOC belongs to the batch. Each worker owns only its own UDP socket.
    M3dsNet3dsContext socket = {.tcp_fd = -1, .udp_fd = -1, .soc_initialized = true};
    M3dsNetTransport worker_transport = transport;
    worker_transport.user_data = &socket;
    uint64_t started = osGetTime();
    probe->size = m3ds_net_query_host(&worker_transport, probe->host, probe->port,
        2000, probe->packet, sizeof(probe->packet));
    probe->ping = (int32_t)(osGetTime() - started);
}

int32_t m3ds_community_probe_open(void) {
    if (soc.soc_initialized) return M3DS_COMMUNITY_PROBE_SLOTS;
    if (R_FAILED(svcGetThreadPriority(&priority, CUR_THREAD_HANDLE))) return 0;
    return m3ds_net_3ds_init(&transport, &soc) ? M3DS_COMMUNITY_PROBE_SLOTS : 0;
}

int32_t m3ds_community_probe_start(int32_t slot, const uint8_t *host, int32_t length, int32_t port) {
    if (!soc.soc_initialized || slot < 0 || slot >= M3DS_COMMUNITY_PROBE_SLOTS
            || !host || length <= 0 || (size_t)length >= sizeof(probes[0].host)
            || port <= 0 || port > UINT16_MAX || memchr(host, '\0', (size_t)length)) return 0;
    ServerProbe *probe = &probes[slot];
    if (probe->worker) return 0;
    memcpy(probe->host, host, (size_t)length);
    probe->host[length] = '\0';
    probe->port = (uint16_t)port;
    probe->worker = threadCreate(probe_server, probe, 16 * 1024, priority, -2, false);
    return probe->worker != NULL;
}

int32_t m3ds_community_probe_poll(int32_t slot, uint8_t *packet, int32_t capacity, int32_t *ping) {
    if (slot < 0 || slot >= M3DS_COMMUNITY_PROBE_SLOTS || !probes[slot].worker) return -1;
    ServerProbe *probe = &probes[slot];
    if (R_FAILED(threadJoin(probe->worker, 0))) return 0;
    threadFree(probe->worker);
    probe->worker = NULL;
    if (probe->size <= 0 || probe->size > capacity || !packet || !ping) return -1;
    memcpy(packet, probe->packet, (size_t)probe->size);
    *ping = probe->ping;
    return probe->size;
}

void m3ds_community_probe_close(void) {
    if (!soc.soc_initialized) return;
    for (unsigned int i = 0; i < M3DS_COMMUNITY_PROBE_SLOTS; i++) {
        if (!probes[i].worker) continue;
        threadJoin(probes[i].worker, UINT64_MAX);
        threadFree(probes[i].worker);
        probes[i].worker = NULL;
    }
    m3ds_net_3ds_exit(&soc);
}
