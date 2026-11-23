#include "m3ds_net_bridge.h"
#include "net_3ds.h"

#include <3ds.h>
#include <string.h>

static M3dsNet3dsContext context;
static M3dsNetTransport transport;
static M3dsNetClient *client;
static M3dsNetHandshakeResult handshake;
static Thread keep_alive_worker;
static Handle keep_alive_done;
static LightLock tcp_lock;
static bool rendering_keep_alive;

static void keep_connection_alive(void *arg) {
    M3dsNetClient *connection = arg;
    bool logged = false;
    while (true) {
        Result waited = svcWaitSynchronization(keep_alive_done, INT64_C(3000000000));
        // On success the main thread signaled the event. Only a timeout needs a keepalive.
        if (R_DESCRIPTION(waited) != RD_TIMEOUT) break;
        LightLock_Lock(&tcp_lock);
        bool sent = m3ds_net_client_keep_alive(connection, true);
        LightLock_Unlock(&tcp_lock);
        if (!sent) break;
        if (!logged) {
            const char *marker = rendering_keep_alive ? "UPSTREAM_RENDER_KEEPALIVE transport=tcp" :
                "UPSTREAM_WORLD_KEEPALIVE transport=tcp";
            svcOutputDebugString(marker, strlen(marker));
            logged = true;
        }
    }
}

int32_t m3ds_live_net_begin_keep_alive(int32_t rendering) {
    if (!client || keep_alive_worker) return 0;
    int32_t priority;
    if (R_FAILED(svcGetThreadPriority(&priority, CUR_THREAD_HANDLE))) return 0;
    // A long collection can run without yielding the application core.
    if (priority > 0x18) priority--;
    if (R_FAILED(svcCreateEvent(&keep_alive_done, RESET_STICKY))) return 0;
    LightLock_Init(&tcp_lock);
    rendering_keep_alive = rendering != 0;
    keep_alive_worker = threadCreate(keep_connection_alive, client, 8 * 1024, priority, -2, false);
    if (!keep_alive_worker) svcCloseHandle(keep_alive_done);
    return keep_alive_worker != NULL;
}

void m3ds_live_net_end_keep_alive(void) {
    if (!keep_alive_worker) return;
    svcSignalEvent(keep_alive_done);
    threadJoin(keep_alive_worker, UINT64_MAX);
    threadFree(keep_alive_worker);
    svcCloseHandle(keep_alive_done);
    keep_alive_worker = NULL;
}

void m3ds_live_net_close(void) {
    m3ds_live_net_end_keep_alive();
    if (client) {
        m3ds_net_client_close(client);
        client = NULL;
    }
    if (context.soc_initialized) m3ds_net_3ds_exit(&context);
}

int32_t m3ds_live_net_open(const void *address, int32_t length, int32_t port, int32_t version,
    int32_t kick_text_id, int32_t kick_reason_id) {
    char host[256];
    if (!address || length <= 0 || length >= (int32_t)sizeof(host) ||
            port <= 0 || port > UINT16_MAX || memchr(address, '\0', (size_t)length) ||
            kick_text_id < 0 || kick_text_id > UINT8_MAX || kick_reason_id < 0 || kick_reason_id > UINT8_MAX) {
        return M3DS_HANDSHAKE_INVALID_ARG;
    }
    if (client) m3ds_live_net_close();
    memcpy(host, address, (size_t)length);
    host[length] = '\0';
    if (!m3ds_net_3ds_init(&transport, &context)) {
        return M3DS_HANDSHAKE_TRANSPORT_ERROR;
    }
    const M3dsNetConnectPacket packet = {
        .version = version,
        .version_type = M3DS_NET_UPSTREAM_VERSION_TYPE,
        .name = "Min3DStry ARM11",
        .locale = "en",
        .usid = "arm11-live-probe",
        .uuid_raw = {77, 51, 68, 83, 84, 82, 89, 2},
        .color = UINT32_MAX
    };
    client = m3ds_net_client_open(&transport, host, (uint16_t)port, 5000, &packet,
        (uint8_t)kick_text_id, (uint8_t)kick_reason_id, &handshake);
    if (!client) m3ds_net_3ds_exit(&context);
    return handshake.status;
}

int32_t m3ds_live_net_stream_id(void) { return handshake.stream_id; }
int32_t m3ds_live_net_stream_total(void) { return handshake.stream_total; }
int32_t m3ds_live_net_stream_type(void) { return handshake.stream_type; }
int32_t m3ds_live_net_packet_id(void) { return handshake.first_server_packet_id; }

int32_t m3ds_live_net_receive(void *payload, int32_t capacity, int32_t reliable, int32_t timeout_ms) {
    if (capacity <= 0 || capacity > M3DS_NET_MAX_PACKET_SIZE || timeout_ms < 0) {
        return M3DS_NET_IO_ERROR;
    }
    uint8_t id;
    size_t length;
    // Arc may send a TCP ping reply while reading a frame. Keep the two writers serialized.
    bool worker_tcp = reliable && keep_alive_worker;
    if (worker_tcp) LightLock_Lock(&tcp_lock);
    int result = m3ds_net_client_receive(client, reliable != 0, (uint32_t)timeout_ms,
        &id, payload, (size_t)capacity, &length);
    if (worker_tcp) LightLock_Unlock(&tcp_lock);
    if (result == M3DS_NET_IO_EOF) return -3;
    if (result != 1) return result;
    return ((int32_t)id << 16) | (int32_t)length;
}

int32_t m3ds_live_net_send(const void *payload, int32_t length, int32_t packet_id, int32_t reliable) {
    if (length < 0 || length > M3DS_NET_MAX_PACKET_SIZE || packet_id < 0 || packet_id > UINT8_MAX) {
        return 0;
    }
    bool worker_tcp = reliable && keep_alive_worker;
    if (worker_tcp) LightLock_Lock(&tcp_lock);
    bool sent = m3ds_net_client_send(client, reliable != 0, (uint8_t)packet_id, payload, (size_t)length);
    if (worker_tcp) LightLock_Unlock(&tcp_lock);
    return sent;
}

int32_t m3ds_live_net_keep_alive(int32_t reliable) {
    bool worker_tcp = reliable && keep_alive_worker;
    if (worker_tcp) LightLock_Lock(&tcp_lock);
    bool sent = m3ds_net_client_keep_alive(client, reliable != 0);
    if (worker_tcp) LightLock_Unlock(&tcp_lock);
    return sent;
}

static uint8_t pack_axis(int value) {
    if (value > 127) value = 127;
    if (value < -127) value = -127;
    return (uint8_t)value;
}

int32_t m3ds_live_net_input(void) {
    if (!aptMainLoop()) return -1;
    hidScanInput();
    const u32 keys = hidKeysHeld();
    const u32 pressed = hidKeysDown();
    if (pressed & KEY_START) return -1;

    circlePosition pad = {0};
    hidCircleRead(&pad);
    // Generic direction masks also include digital Circle Pad directions.
    const bool dpad = (keys & (KEY_DLEFT | KEY_DRIGHT | KEY_DUP | KEY_DDOWN)) != 0;
    if (dpad) {
        pad.dx = 127 * ((int)((keys & KEY_DRIGHT) != 0) - (int)((keys & KEY_DLEFT) != 0));
        pad.dy = 127 * ((int)((keys & KEY_DUP) != 0) - (int)((keys & KEY_DDOWN) != 0));
    }
    return (int32_t)pack_axis(pad.dx) | ((int32_t)pack_axis(pad.dy) << 8) |
           ((int32_t)dpad << 16) | ((int32_t)((pressed & KEY_A) != 0) << 17) |
           ((int32_t)((pressed & KEY_R) != 0) << 18) | ((int32_t)((pressed & KEY_B) != 0) << 19) |
           ((int32_t)((keys & KEY_L) != 0) << 21);
}

int32_t m3ds_live_net_touch(void) {
    if (!(hidKeysDown() & KEY_TOUCH)) return -1;
    touchPosition touch;
    hidTouchRead(&touch);
    return (int32_t)touch.px | ((int32_t)touch.py << 16);
}
