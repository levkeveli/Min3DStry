#include "platform/sector_catalog.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

#ifdef _WIN32
#include <direct.h>
#define make_dir(path) _mkdir(path)
#define remove_dir(path) _rmdir(path)
#else
#include <unistd.h>
#define make_dir(path) mkdir(path, 0777)
#define remove_dir(path) rmdir(path)
#endif

static const char *dir = "build/host/sector-catalog";

static void write_file(const char *name, const unsigned char *data, size_t size) {
    char path[256];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    FILE *file = fopen(path, "wb");
    assert(file != NULL);
    assert(fwrite(data, 1, size, file) == size);
    fclose(file);
}

static size_t put_be16(unsigned char *out, unsigned int value) {
    out[0] = (unsigned char)(value >> 8);
    out[1] = (unsigned char)value;
    return 2u;
}

static size_t put_be32(unsigned char *out, unsigned int value) {
    out[0] = (unsigned char)(value >> 24);
    out[1] = (unsigned char)(value >> 16);
    out[2] = (unsigned char)(value >> 8);
    out[3] = (unsigned char)value;
    return 4u;
}

static size_t put_utf(unsigned char *out, const char *text) {
    size_t length = strlen(text);
    size_t written = put_be16(out, (unsigned int)length);
    memcpy(out + written, text, length);
    return written + length;
}

static size_t write_metadata_file(const char *name) {
    unsigned char payload[256];
    unsigned char stream[2 + 5 + sizeof(payload)];
    size_t size = 0u;
    memcpy(payload + size, "MSAV", 4);
    size += 4u;
    size += put_be32(payload + size, 13u);
    size_t length_position = size;
    size += 4u;
    size_t metadata_start = size;
    size += put_be16(payload + size, 2u);
    size += put_utf(payload + size, "mapname");
    size += put_utf(payload + size, "Glacier");
    size += put_utf(payload + size, "wave");
    size += put_utf(payload + size, "7");
    put_be32(payload + length_position,
             (unsigned int)(size - metadata_start));

    size_t total = 0u;
    stream[total++] = 0x78;
    stream[total++] = 0x01;
    stream[total++] = 0x01;
    stream[total++] = (unsigned char)size;
    stream[total++] = (unsigned char)(size >> 8);
    stream[total++] = (unsigned char)(~size);
    stream[total++] = (unsigned char)(~size >> 8);
    memcpy(stream + total, payload, size);
    total += size;
    write_file(name, stream, total);
    return total;
}

static void remove_file(const char *name) {
    char path[256];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    assert(remove(path) == 0);
}

static void test_scan_and_sort(void) {
    make_dir(dir);
    unsigned char raw[16] = {'M', 'S', 'A', 'V', 0, 0, 0, 13};
    write_file("sector-serpulo-170.msav", raw, sizeof(raw));
    size_t erekir_bytes = write_metadata_file("sector-erekir-64.msav");
    write_file("notes.txt", raw, 4);
    unsigned char broken[3] = {'M', 'S', 'A'};
    write_file("sector-bad.msav", broken, sizeof(broken));

    M3dsSectorSlot slots[M3DS_SECTOR_CATALOG_MAX];
    size_t count = m3ds_sector_catalog_scan(dir, slots, M3DS_SECTOR_CATALOG_MAX);
    assert(count == 2u);
    assert(strcmp(slots[0].planet, "erekir") == 0);
    assert(slots[0].id == 64u);
    assert(slots[0].compressed);
    assert(slots[0].valid);
    assert(slots[0].has_meta);
    assert(strcmp(slots[0].map_name, "Glacier") == 0);
    assert(slots[0].wave == 7u);
    assert(slots[0].bytes == erekir_bytes);
    assert(strcmp(slots[1].planet, "serpulo") == 0);
    assert(slots[1].id == 170u);
    assert(slots[1].valid);
    assert(!slots[1].has_meta);
    assert(slots[1].bytes == sizeof(raw));

    char label[64];
    assert(m3ds_sector_catalog_label(&slots[0], label, sizeof(label)));
    assert(strcmp(label, "erekir-64 Glacier w7") == 0);
    assert(m3ds_sector_catalog_label(&slots[1], label, sizeof(label)));
    assert(strcmp(label, "serpulo-170 v 16B") == 0);
    assert(!m3ds_sector_catalog_label(NULL, label, sizeof(label)));
    assert(!m3ds_sector_catalog_label(&slots[0], label, 0u));

    M3dsSectorSlot one[1];
    assert(m3ds_sector_catalog_scan(dir, one, 1u) == 1u);
    assert(strcmp(one[0].planet, "erekir") == 0);

    remove_file("sector-serpulo-170.msav");
    remove_file("sector-erekir-64.msav");
    remove_file("notes.txt");
    remove_file("sector-bad.msav");
    assert(remove_dir(dir) == 0);
}

static void test_missing_and_invalid(void) {
    M3dsSectorSlot slots[M3DS_SECTOR_CATALOG_MAX];
    assert(m3ds_sector_catalog_scan("build/host/does-not-exist", slots,
                                    M3DS_SECTOR_CATALOG_MAX) == 0u);
    assert(m3ds_sector_catalog_scan(NULL, slots, M3DS_SECTOR_CATALOG_MAX) == 0u);
    assert(m3ds_sector_catalog_scan(dir, NULL, M3DS_SECTOR_CATALOG_MAX) == 0u);
    assert(m3ds_sector_catalog_scan(dir, slots, 0u) == 0u);
}

int main(void) {
    test_scan_and_sort();
    test_missing_and_invalid();
    return 0;
}
