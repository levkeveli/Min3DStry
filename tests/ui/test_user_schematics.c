#include "ui/user_schematics.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures = 0;

#define CHECK(cond)                                                        \
    do {                                                                   \
        if (!(cond)) {                                                     \
            printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);         \
            ++failures;                                                    \
        }                                                                  \
    } while (0)

static uint8_t *read_file(const char *path, size_t *size) {
    FILE *file = fopen(path, "rb");
    if (!file) {
        return NULL;
    }
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        return NULL;
    }
    long length = ftell(file);
    if (length <= 0) {
        fclose(file);
        return NULL;
    }
    rewind(file);
    uint8_t *data = (uint8_t *)malloc((size_t)length);
    if (!data) {
        fclose(file);
        return NULL;
    }
    size_t read = fread(data, 1u, (size_t)length, file);
    fclose(file);
    if (read != (size_t)length) {
        free(data);
        return NULL;
    }
    *size = read;
    return data;
}

static void test_parse_fixture(void) {
    size_t size = 0u;
    uint8_t *data = read_file("tests/fixtures/schematics/battery node.msch", &size);
    CHECK(data != NULL);
    if (!data) {
        return;
    }
    M3dsUserSchematic parsed;
    memset(&parsed, 0, sizeof(parsed));
    CHECK(m3ds_msch_parse(data, size, &parsed));
    CHECK(parsed.width == 5u);
    CHECK(parsed.height == 3u);
    CHECK(parsed.blocks == 2u);
    CHECK(strstr(parsed.summary, "battery-large") != NULL);
    free(data);
}

static void test_rejects_malformed(void) {
    M3dsUserSchematic parsed;
    memset(&parsed, 0, sizeof(parsed));
    const uint8_t junk[] = {'n', 'o', 'p', 'e', 1, 2, 3, 4, 5, 6};
    CHECK(!m3ds_msch_parse(junk, sizeof(junk), &parsed));
    const uint8_t magic_only[] = {'m', 's', 'c', 'h', 1, 0x78, 0x9c};
    CHECK(!m3ds_msch_parse(magic_only, sizeof(magic_only), &parsed));
    CHECK(!m3ds_msch_parse(NULL, 0u, &parsed));
}

static void test_scan_directory(void) {
    M3dsUserSchematic entries[M3DS_USER_SCHEMATIC_MAX];
    size_t count = m3ds_user_schematics_scan("tests/fixtures/schematics", entries,
                                             M3DS_USER_SCHEMATIC_MAX);
    CHECK(count == 2u);
    if (count == 2u) {
        CHECK(strcmp(entries[0].name, "a single large power node") == 0);
        CHECK(strcmp(entries[1].name, "battery node") == 0);
        CHECK(entries[0].width == 2u);
        CHECK(entries[0].blocks == 1u);
        CHECK(entries[1].width == 5u);
        CHECK(entries[1].height == 3u);
    }
    CHECK(m3ds_user_schematics_scan("tests/fixtures/does-not-exist", entries,
                                    M3DS_USER_SCHEMATIC_MAX) == 0u);
    CHECK(m3ds_user_schematics_scan("tests/fixtures/schematics", entries, 0u) ==
          0u);
}

int main(void) {
    test_parse_fixture();
    test_rejects_malformed();
    test_scan_directory();
    if (failures != 0) {
        printf("USER_SCHEMATICS FAIL failures=%d\n", failures);
        return 1;
    }
    printf("USER_SCHEMATICS PASS\n");
    return 0;
}
