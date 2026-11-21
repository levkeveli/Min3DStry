#pragma once

#include <stdint.h>

int32_t m3ds_live_net_open(const void *host, int32_t length, int32_t port, int32_t version,
    int32_t kick_text_id, int32_t kick_reason_id);
int32_t m3ds_live_net_stream_id(void);
int32_t m3ds_live_net_stream_total(void);
int32_t m3ds_live_net_stream_type(void);
int32_t m3ds_live_net_packet_id(void);
int32_t m3ds_live_net_receive(void *payload, int32_t capacity, int32_t reliable, int32_t timeout_ms);
int32_t m3ds_live_net_send(const void *payload, int32_t length, int32_t packet_id, int32_t reliable);
int32_t m3ds_live_net_keep_alive(int32_t reliable);
/* Signed X/Y in the low two bytes, D-pad in bit 16, or -1 when leaving. */
int32_t m3ds_live_net_input(void);
void m3ds_live_net_close(void);
