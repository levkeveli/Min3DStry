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
int32_t m3ds_live_net_begin_keep_alive(int32_t rendering);
void m3ds_live_net_end_keep_alive(void);
/* Signed X/Y, then D-pad/A/R/B/X/L/Y in bits 16–22, or -1 when leaving. */
int32_t m3ds_live_net_input(void);
/* X and Y in the low and high halves on a fresh touch, or -1. */
int32_t m3ds_live_net_touch(void);
void m3ds_live_net_close(void);
