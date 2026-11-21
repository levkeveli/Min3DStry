#include "platform/msav_inflate.h"
#include "platform/save_slot.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static const char *path = "build/host/test-save-slot.msav";

static const uint8_t fixed_stream[16] = {
    0x78, 0xda, 0xf3, 0x0d, 0x76, 0x0c, 0x63, 0x60, 0x60, 0xe0, 0x05, 0x00,
    0x07, 0xf6, 0x01, 0x45,
};

static const uint8_t dynamic_stream[106] = {
    0x78, 0x9c, 0xed, 0xca, 0xcb, 0x0d, 0x82, 0x40, 0x14, 0x00, 0xc0, 0x6d,
    0xc0, 0x1e, 0x5e, 0x05, 0xf6, 0x40, 0x01, 0x9e, 0x4c, 0xb8, 0x2b, 0xa2,
    0x7c, 0xd4, 0x55, 0x10, 0x44, 0xab, 0x37, 0x94, 0x61, 0x32, 0xe7, 0x99,
    0xdd, 0xbe, 0x28, 0x53, 0x4a, 0x9b, 0x57, 0x53, 0xc7, 0x73, 0x6a, 0xab,
    0x3e, 0x8e, 0x43, 0x7e, 0xdf, 0xe3, 0x9c, 0x97, 0xe8, 0xa6, 0xdb, 0x63,
    0x8c, 0x3c, 0xd7, 0x43, 0xac, 0x7c, 0x3d, 0x7c, 0x3f, 0x71, 0xca, 0x97,
    0x6d, 0xc8, 0xb2, 0x2c, 0xcb, 0xb2, 0x2c, 0xcb, 0xb2, 0x2c, 0xcb, 0xb2,
    0x2c, 0xcb, 0xb2, 0x2c, 0xcb, 0xb2, 0x2c, 0xcb, 0xb2, 0x2c, 0xcb, 0xb2,
    0x2c, 0xcb, 0xf2, 0x9f, 0xe6, 0x1f, 0xcf, 0xd1, 0xb9, 0x71,
};

static void write_bytes(const uint8_t *data, size_t size) {
    FILE *file = fopen(path, "wb");
    assert(file != NULL);
    assert(fwrite(data, 1, size, file) == size);
    fclose(file);
}

static void assert_stream_version(const uint8_t *data, size_t size) {
    write_bytes(data, size);
    M3dsSaveSlotInfo info;
    assert(m3ds_save_slot_probe(path, &info));
    assert(info.present);
    assert(info.valid);
    assert(info.version == 13u);
    assert(info.compressed);
    assert(info.bytes == size);
    remove(path);
}

static void test_valid_slot(void) {
    uint8_t data[128];
    memset(data, 0x5a, sizeof(data));
    memcpy(data, "MSAV", 4);
    data[4] = 0;
    data[5] = 0;
    data[6] = 0;
    data[7] = 13;
    write_bytes(data, sizeof(data));
    M3dsSaveSlotInfo info;
    assert(m3ds_save_slot_probe(path, &info));
    assert(info.present);
    assert(info.valid);
    assert(info.version == 13u);
    assert(!info.compressed);
    assert(info.bytes == sizeof(data));
    remove(path);
}

static void test_zlib_header_without_stream(void) {
    uint8_t data[64];
    memset(data, 0, sizeof(data));
    data[0] = 0x78;
    data[1] = 0x9c;
    write_bytes(data, sizeof(data));
    M3dsSaveSlotInfo info;
    assert(m3ds_save_slot_probe(path, &info));
    assert(info.present);
    assert(!info.valid);
    assert(info.compressed);
    assert(info.bytes == sizeof(data));
    remove(path);
}

static void test_compressed_version(void) {
    assert_stream_version(fixed_stream, sizeof(fixed_stream));
    assert_stream_version(dynamic_stream, sizeof(dynamic_stream));

    uint8_t stored[2 + 5 + 72];
    memset(stored, 0, sizeof(stored));
    stored[0] = 0x78;
    stored[1] = 0x01;
    stored[2] = 0x01;
    stored[3] = 0x48;
    stored[4] = 0x00;
    stored[5] = 0xb7;
    stored[6] = 0xff;
    memcpy(stored + 7, "MSAV", 4);
    stored[14] = 13;
    assert_stream_version(stored, sizeof(stored));
}

static void test_inflate_prefix(void) {
    uint8_t out[4];
    size_t size = 0;
    assert(m3ds_msav_inflate_prefix(
        fixed_stream, sizeof(fixed_stream), out, sizeof(out), &size));
    assert(size == sizeof(out));
    assert(memcmp(out, "MSAV", 4) == 0);

    uint8_t short_out[2];
    assert(m3ds_msav_inflate_prefix(
        dynamic_stream, sizeof(dynamic_stream), short_out, sizeof(short_out),
        &size));
    assert(size == 2);
    assert(memcmp(short_out, "MS", 2) == 0);

    assert(!m3ds_msav_inflate_prefix(
        fixed_stream, sizeof(fixed_stream), NULL, 4, &size));
    assert(!m3ds_msav_inflate_prefix(
        fixed_stream, sizeof(fixed_stream), out, 0, &size));
    assert(!m3ds_msav_inflate_prefix(
        fixed_stream, sizeof(fixed_stream), out, 4, NULL));

    const uint8_t bad_header[2] = {0x78, 0x00};
    assert(!m3ds_msav_inflate_prefix(bad_header, 2, out, 4, &size));
    const uint8_t dictionary_header[2] = {0x78, 0x3e};
    assert(!m3ds_msav_inflate_prefix(dictionary_header, 2, out, 4, &size));
    const uint8_t truncated[3] = {0x78, 0x9c, 0x03};
    assert(!m3ds_msav_inflate_prefix(truncated, 3, out, 4, &size));
}

static void test_bad_and_short_headers(void) {
    uint8_t bad[8] = {'X', 'S', 'A', 'V', 13, 0, 0, 0};
    write_bytes(bad, sizeof(bad));
    M3dsSaveSlotInfo info;
    assert(m3ds_save_slot_probe(path, &info));
    assert(info.present);
    assert(!info.valid);
    remove(path);

    uint8_t short_data[3] = {'M', 'S', 'A'};
    write_bytes(short_data, sizeof(short_data));
    assert(m3ds_save_slot_probe(path, &info));
    assert(info.present);
    assert(!info.valid);
    assert(info.bytes == sizeof(short_data));
    remove(path);
}

static void test_missing_and_invalid(void) {
    remove(path);
    M3dsSaveSlotInfo info;
    assert(m3ds_save_slot_probe(path, &info));
    assert(!info.present);
    assert(!info.valid);
    assert(info.bytes == 0u && info.version == 0u);

    assert(!m3ds_save_slot_probe(NULL, &info));
    assert(!m3ds_save_slot_probe(path, NULL));
}

int main(void) {
    test_valid_slot();
    test_zlib_header_without_stream();
    test_compressed_version();
    test_inflate_prefix();
    test_bad_and_short_headers();
    test_missing_and_invalid();
    return 0;
}
