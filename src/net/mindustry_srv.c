#include "net/mindustry_srv.h"

#include <stdlib.h>
#include <string.h>

/* SRV fallback follows ArcDns and SRVRecord at Arc 7445105cd2. */
enum { header_bytes = 12, query_srv = 33, internet_class = 1 };

static uint16_t read_u16(const uint8_t *bytes) {
  return (uint16_t)((uint16_t)bytes[0] << 8U | bytes[1]);
}

static bool hostname_byte(uint8_t byte) {
  return (byte >= 'a' && byte <= 'z') || (byte >= 'A' && byte <= 'Z')
      || (byte >= '0' && byte <= '9') || byte == '-' || byte == '_';
}

size_t m3ds_srv_query(const char *host, uint16_t id, uint8_t packet[M3DS_DNS_PACKET_BYTES]) {
  static const uint8_t service[] = {10, '_','m','i','n','d','u','s','t','r','y', 4, '_','t','c','p'};
  if (!host || !*host || !packet) return 0;
  memset(packet, 0, header_bytes);
  packet[0] = (uint8_t)(id >> 8U);
  packet[1] = (uint8_t)id;
  packet[2] = 1;
  packet[5] = 1;
  memcpy(packet + header_bytes, service, sizeof(service));
  size_t end = header_bytes + sizeof(service);
  while (*host) {
    size_t label = end++;
    while (*host && *host != '.') {
      if (end - label > 63 || end - header_bytes >= 254 || !hostname_byte((uint8_t)*host)) return 0;
      packet[end++] = (uint8_t)*host++;
    }
    if (end - label <= 1) return 0;
    packet[label] = (uint8_t)(end - label - 1);
    if (*host == '.') host++;
  }
  packet[end++] = 0;
  packet[end++] = 0;
  packet[end++] = query_srv;
  packet[end++] = 0;
  packet[end++] = internet_class;
  return end;
}

/* Consume a name at its original offset and optionally expand compression pointers. */
static bool read_name(const uint8_t *packet, size_t size, size_t *offset, char *name) {
  size_t position = *offset;
  size_t written = 0;
  bool redirected = false;
  for (unsigned int steps = 0; steps < M3DS_DNS_PACKET_BYTES; steps++) {
    if (position >= size) return false;
    uint8_t label = packet[position++];
    if (label == 0) {
      if (!redirected) *offset = position;
      if (name) name[written] = '\0';
      return true;
    }
    if ((label & 0xc0U) == 0xc0U) {
      if (position >= size) return false;
      size_t target = (size_t)(label & 0x3fU) << 8U | packet[position];
      if (target < header_bytes || target >= position - 1U) return false;
      if (!redirected) *offset = position + 1U;
      position = target;
      redirected = true;
      continue;
    }
    if (label > 63 || label > size - position || written + (written != 0) + label >= M3DS_DNS_NAME_BYTES) {
      return false;
    }
    if (written != 0) {
      if (name) name[written] = '.';
      written++;
    }
    if (name) {
      for (size_t i = 0; i < label; i++) {
        if (!hostname_byte(packet[position + i])) return false;
        name[written + i] = (char)packet[position + i];
      }
    }
    written += label;
    position += label;
  }
  return false;
}

static int compare_record(const void *left, const void *right) {
  const M3dsSrvRecord *a = left;
  const M3dsSrvRecord *b = right;
  return a->priority == b->priority ? (int)a->weight - b->weight : (int)a->priority - b->priority;
}

bool m3ds_srv_decode(const uint8_t *packet, size_t size, uint16_t id,
                     M3dsSrvRecord records[M3DS_DNS_MAX_SRV_ANSWERS], size_t *count) {
  if (!packet || !records || !count || size < header_bytes || size > M3DS_DNS_PACKET_BYTES
      || read_u16(packet) != id || (packet[2] & 0xfaU) != 0x80U
      || read_u16(packet + 4) != 1) return false;
  unsigned int error = packet[3] & 0x0fU;
  if (error != 0 && error != 3) return false;

  *count = 0;
  size_t next = header_bytes;
  if (!read_name(packet, size, &next, NULL) || size - next < 4
      || read_u16(packet + next) != query_srv || read_u16(packet + next + 2) != internet_class) return false;
  next += 4;
  if (error == 3) return true;

  uint16_t answers = read_u16(packet + 6);
  for (uint16_t i = 0; i < answers; i++) {
    if (!read_name(packet, size, &next, NULL) || size - next < 10) return false;
    uint16_t type = read_u16(packet + next);
    uint16_t class = read_u16(packet + next + 2);
    uint16_t length = read_u16(packet + next + 8);
    next += 10;
    if (length > size - next) return false;
    if (type == query_srv && class == internet_class) {
      if (length < 7 || *count == M3DS_DNS_MAX_SRV_ANSWERS) return false;
      size_t target = next + 6;
      if (!read_name(packet, size, &target, NULL) || target != next + length) return false;
      uint16_t port = read_u16(packet + next + 4);
      if (port != 0 && packet[next + 6] != 0) {
        records[(*count)++] = (M3dsSrvRecord){
          .priority = read_u16(packet + next),
          .weight = read_u16(packet + next + 2),
          .port = port,
          .target_offset = (uint16_t)(next + 6),
        };
      }
    }
    next += length;
  }
  qsort(records, *count, sizeof(*records), compare_record);
  return true;
}

bool m3ds_srv_target(const uint8_t *packet, size_t size, const M3dsSrvRecord *record,
                     char target[M3DS_DNS_NAME_BYTES]) {
  if (!packet || !record || !target) return false;
  size_t offset = record->target_offset;
  return read_name(packet, size, &offset, target) && *target != '\0';
}
