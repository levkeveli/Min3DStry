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

static void remove_file(const char *name) {
    char path[256];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    assert(remove(path) == 0);
}

static void test_scan_and_sort(void) {
    make_dir(dir);
    unsigned char raw[16] = {'M', 'S', 'A', 'V', 13, 0, 0, 0};
    write_file("sector-serpulo-170.msav", raw, sizeof(raw));
    unsigned char zlib[16] = {0x78, 0x9c, 0, 0};
    write_file("sector-erekir-64.msav", zlib, sizeof(zlib));
    write_file("notes.txt", raw, 4);
    unsigned char broken[3] = {'M', 'S', 'A'};
    write_file("sector-bad.msav", broken, sizeof(broken));

    M3dsSectorSlot slots[M3DS_SECTOR_CATALOG_MAX];
    size_t count = m3ds_sector_catalog_scan(dir, slots, M3DS_SECTOR_CATALOG_MAX);
    assert(count == 2u);
    assert(strcmp(slots[0].planet, "erekir") == 0);
    assert(slots[0].id == 64u);
    assert(slots[0].compressed);
    assert(!slots[0].valid);
    assert(strcmp(slots[1].planet, "serpulo") == 0);
    assert(slots[1].id == 170u);
    assert(slots[1].valid);
    assert(slots[1].bytes == sizeof(raw));

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
