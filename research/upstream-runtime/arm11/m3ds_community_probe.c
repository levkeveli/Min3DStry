#include "m3ds_community_probe.h"
#include "net/mindustry_srv.h"
#include "net_3ds.h"

#include <3ds.h>
#include <arpa/inet.h>
#include <string.h>

typedef struct {
    Thread worker;
    char host[256];
    uint16_t port;
    uint8_t packet[512];
    int size;
    int32_t ping;
    char target[M3DS_DNS_NAME_BYTES];
} ServerProbe;

static M3dsNet3dsContext soc;
static M3dsNetTransport transport;
static int32_t priority;
static ServerProbe probes[M3DS_COMMUNITY_PROBE_SLOTS];
static char nameservers[16][INET_ADDRSTRLEN];
static size_t resolver_count;

static void load_nameservers(void) {
    SOCU_DNSTableEntry entries[17] = {0};
    socklen_t size = sizeof(entries);
    resolver_count = 0;
    if (SOCU_GetNetworkOpt(SOL_CONFIG, NETOPT_DNS_TABLE, entries, &size) == 0) {
        size_t count = size / sizeof(entries[0]);
        if (count > sizeof(entries) / sizeof(entries[0])) count = sizeof(entries) / sizeof(entries[0]);
        for (size_t i = 0; i < count && resolver_count < sizeof(nameservers) / sizeof(nameservers[0]); i++) {
            if (entries[i].family != AF_INET || entries[i].ip.s_addr == 0) continue;
            if (inet_ntop(AF_INET, &entries[i].ip, nameservers[resolver_count], INET_ADDRSTRLEN)) resolver_count++;
        }
    }
    if (resolver_count == 0) {
        // Arc's fallback provider uses these when no system resolver is available.
        strcpy(nameservers[resolver_count++], "1.1.1.1");
        strcpy(nameservers[resolver_count++], "8.8.8.8");
    }
}

static void probe_srv(ServerProbe *probe, const M3dsNetTransport *udp) {
    uint8_t query[M3DS_DNS_PACKET_BYTES], reply[M3DS_DNS_PACKET_BYTES];
    M3dsSrvRecord records[M3DS_DNS_MAX_SRV_ANSWERS];
    struct in_addr literal;
    if (inet_aton(probe->host, &literal)) return;
    uint16_t id = (uint16_t)(svcGetSystemTick() ^ (uintptr_t)probe);
    size_t query_size = m3ds_srv_query(probe->host, id, query);
    if (query_size == 0) return;
    for (size_t resolver = 0; resolver < resolver_count; resolver++) {
        if (!udp->udp_open(udp->user_data, nameservers[resolver], 53)) continue;
        int length = M3DS_NET_IO_ERROR;
        if (udp->udp_send(udp->user_data, query, query_size) == (int)query_size) {
            length = udp->udp_recv(udp->user_data, reply, sizeof(reply), 2000);
        }
        udp->udp_close(udp->user_data);
        size_t count;
        if (length <= 0 || !m3ds_srv_decode(reply, (size_t)length, id, records, &count)) continue;
        for (size_t i = 0; i < count; i++) {
            if (!m3ds_srv_target(reply, (size_t)length, &records[i], probe->target)) continue;
            uint64_t started = osGetTime();
            probe->size = m3ds_net_query_host(udp, probe->target, records[i].port,
                2000, probe->packet, sizeof(probe->packet));
            if (probe->size > 0) {
                probe->port = records[i].port;
                probe->ping = (int32_t)(osGetTime() - started);
                return;
            }
        }
        break;
    }
}

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
    if (probe->size <= 0 && probe->port == M3DS_NET_DEFAULT_PORT) probe_srv(probe, &worker_transport);
}

int32_t m3ds_community_probe_open(void) {
    if (soc.soc_initialized) return M3DS_COMMUNITY_PROBE_SLOTS;
    if (R_FAILED(svcGetThreadPriority(&priority, CUR_THREAD_HANDLE))) return 0;
    if (!m3ds_net_3ds_init(&transport, &soc)) return 0;
    load_nameservers();
    return M3DS_COMMUNITY_PROBE_SLOTS;
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
    probe->target[0] = '\0';
    probe->worker = threadCreate(probe_server, probe, 16 * 1024, priority, -2, false);
    return probe->worker != NULL;
}

int32_t m3ds_community_probe_poll(int32_t slot, uint8_t *packet, int32_t capacity,
                                 int32_t metadata[2], uint8_t *target, int32_t target_capacity) {
    if (slot < 0 || slot >= M3DS_COMMUNITY_PROBE_SLOTS || !probes[slot].worker) return -1;
    ServerProbe *probe = &probes[slot];
    if (R_FAILED(threadJoin(probe->worker, 0))) return 0;
    threadFree(probe->worker);
    probe->worker = NULL;
    if (probe->size <= 0 || probe->size > capacity || !packet || !metadata || !target || target_capacity <= 0) return -1;
    size_t name_size = strlen(probe->target) + 1;
    if (name_size > (size_t)target_capacity) return -1;
    memcpy(packet, probe->packet, (size_t)probe->size);
    memcpy(target, probe->target, name_size);
    metadata[0] = probe->ping;
    metadata[1] = probe->port;
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
