#ifndef M3DS_MINDUSTRY_SRV_H
#define M3DS_MINDUSTRY_SRV_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define M3DS_DNS_PACKET_BYTES 512
#define M3DS_DNS_NAME_BYTES 254
#define M3DS_DNS_MAX_SRV_ANSWERS 32

typedef struct {
  uint16_t priority;
  uint16_t weight;
  uint16_t port;
  uint16_t target_offset;
} M3dsSrvRecord;

size_t m3ds_srv_query(const char *host, uint16_t id, uint8_t packet[M3DS_DNS_PACKET_BYTES]);
bool m3ds_srv_decode(const uint8_t *packet, size_t size, uint16_t id,
                     M3dsSrvRecord records[M3DS_DNS_MAX_SRV_ANSWERS], size_t *count);
bool m3ds_srv_target(const uint8_t *packet, size_t size, const M3dsSrvRecord *record,
                     char target[M3DS_DNS_NAME_BYTES]);

#endif
