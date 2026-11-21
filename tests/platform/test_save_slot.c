#include "platform/save_slot.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static const char *path = "build/host/test-save-slot.msav";

static void write_bytes(const uint8_t *data, size_t size) {
    FILE *file = fopen(path, "wb");
    assert(file != NULL);
    assert(fwrite(data, 1, size, file) == size);
    fclose(file);
}

static void test_valid_slot(void) {
    uint8_t data[128];
    memset(data, 0x5a, sizeof(data));
    memcpy(data, "MSAV", 4);
    data[4] = 13;
    data[5] = 0;
    data[6] = 0;
    data[7] = 0;
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

static void test_zlib_container(void) {
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
    test_zlib_container();
    test_bad_and_short_headers();
    test_missing_and_invalid();
    return 0;
}
