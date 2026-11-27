#include "net/mindustry_srv.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static void write_u16(uint8_t *out, uint16_t value) {
  out[0] = (uint8_t)(value >> 8U);
  out[1] = (uint8_t)value;
}

static size_t append_srv(uint8_t *out, uint16_t priority, uint16_t weight, uint16_t port,
                         const char *label, uint8_t suffix_offset) {
  size_t label_size = strlen(label);
  size_t target_size = 1 + label_size + 2;
  static const uint8_t header[] = {0xc0, 0x0c, 0, 33, 0, 1, 0, 0, 0, 60};
  memcpy(out, header, sizeof(header));
  write_u16(out + 10, (uint16_t)(6 + target_size));
  write_u16(out + 12, priority);
  write_u16(out + 14, weight);
  write_u16(out + 16, port);
  out[18] = (uint8_t)label_size;
  memcpy(out + 19, label, label_size);
  out[19 + label_size] = 0xc0;
  out[20 + label_size] = suffix_offset;
  return 18 + target_size;
}

int main(void) {
  uint8_t query[M3DS_DNS_PACKET_BYTES], reply[M3DS_DNS_PACKET_BYTES];
  M3dsSrvRecord records[M3DS_DNS_MAX_SRV_ANSWERS];
  char target[M3DS_DNS_NAME_BYTES];
  size_t count;
  static const uint8_t question[] = {
    10, '_','m','i','n','d','u','s','t','r','y', 4, '_','t','c','p',
    4, 'd','e','m','o', 7, 'e','x','a','m','p','l','e', 0, 0, 33, 0, 1
  };
  size_t query_size = m3ds_srv_query("demo.example", 0x2345, query);
  assert(query_size == 12 + sizeof(question));
  assert(memcmp(query + 12, question, sizeof(question)) == 0);
  assert(m3ds_srv_query("bad..example", 0x2345, reply) == 0);

  memcpy(reply, query, query_size);
  reply[2] = 0x81;
  reply[3] = 0x80;
  reply[29] = 'D';
  // Include an unrelated IN CNAME record before the SRV records.
  static const uint8_t cname[] = {0xc0, 0x0c, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xc0, 28};
  memcpy(reply + query_size, cname, sizeof(cname));
  size_t end = query_size + sizeof(cname);
  end += append_srv(reply + end, 1, 10, 16687, "first", 33);
  end += append_srv(reply + end, 0, 20, 16688, "second", 33);
  end += append_srv(reply + end, 0, 5, 16689, "third", 33);
  reply[7] = 4;

  assert(m3ds_srv_decode(reply, end, query, query_size, records, &count));
  assert(count == 3);
  assert(records[0].priority == 0 && records[0].weight == 5 && records[0].port == 16689);
  assert(records[1].port == 16688 && records[2].port == 16687);
  assert(m3ds_srv_target(reply, end, &records[0], target));
  assert(strcmp(target, "third.example") == 0);

  assert(!m3ds_srv_decode(reply, end - 1, query, query_size, records, &count));
  reply[29] = 'x';
  assert(!m3ds_srv_decode(reply, end, query, query_size, records, &count));
  reply[29] = 'd';
  reply[1] ^= 1;
  assert(!m3ds_srv_decode(reply, end, query, query_size, records, &count));
  reply[1] ^= 1;
  reply[end - 1] = (uint8_t)(end - 2);
  assert(!m3ds_srv_decode(reply, end, query, query_size, records, &count));
  reply[3] = 0x83;
  reply[7] = 0;
  assert(m3ds_srv_decode(reply, query_size, query, query_size, records, &count) && count == 0);

  puts("Mindustry DNS SRV query, ordering, compressed targets, and malformed packets passed");
}
