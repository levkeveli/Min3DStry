#include "platform/sd_paths.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static void test_settings_paths(void) {
    char path[128];
    assert(m3ds_sd_settings_path(M3DS_SD_ROOT, path, sizeof(path)));
    assert(strcmp(path, "/3ds/min3dstry/settings.bin") == 0);
    assert(m3ds_sd_settings_backup_path(M3DS_SD_ROOT, path, sizeof(path)));
    assert(strcmp(path, "/3ds/min3dstry/settings_backup.bin") == 0);
    assert(m3ds_sd_settings_backups_dir(M3DS_SD_ROOT, path, sizeof(path)));
    assert(strcmp(path, "/3ds/min3dstry/settings_backups") == 0);

    assert(m3ds_sd_settings_path("/3ds/min3dstry/", path, sizeof(path)));
    assert(strcmp(path, "/3ds/min3dstry/settings.bin") == 0);
    assert(m3ds_sd_join(
        "sdmc:/3ds/min3dstry", "storage-probe.bin", path, sizeof(path)));
    assert(strcmp(path, "sdmc:/3ds/min3dstry/storage-probe.bin") == 0);
}

static void test_sector_paths(void) {
    char path[128];
    assert(m3ds_sd_sector_save_path(
        M3DS_SD_ROOT, "serpulo", 170u, path, sizeof(path)));
    assert(strcmp(path, "/3ds/min3dstry/sector-serpulo-170.msav") == 0);
    assert(m3ds_sd_sector_save_path(
        M3DS_SD_ROOT, "erekir", 64u, path, sizeof(path)));
    assert(strcmp(path, "/3ds/min3dstry/sector-erekir-64.msav") == 0);
}

static void test_invalid_paths(void) {
    char path[128];
    assert(!m3ds_sd_join(M3DS_SD_ROOT, "../settings.bin", path, sizeof(path)));
    assert(!m3ds_sd_join(M3DS_SD_ROOT, "/settings.bin", path, sizeof(path)));
    assert(!m3ds_sd_join(M3DS_SD_ROOT, "a/../b", path, sizeof(path)));
    assert(!m3ds_sd_join(M3DS_SD_ROOT, "a\\b", path, sizeof(path)));
    assert(!m3ds_sd_join(M3DS_SD_ROOT, "", path, sizeof(path)));
    assert(!m3ds_sd_join(NULL, "settings.bin", path, sizeof(path)));
    assert(!m3ds_sd_join("/3ds/../min3dstry", "settings.bin", path, sizeof(path)));
    assert(!m3ds_sd_join("foo:bar", "settings.bin", path, sizeof(path)));
    assert(!m3ds_sd_join("/3ds:min3dstry", "settings.bin", path, sizeof(path)));
    assert(!m3ds_sd_join("sdmc:", "settings.bin", path, sizeof(path)));
    assert(!m3ds_sd_join("sdmc:/3ds/../min3dstry", "settings.bin", path, sizeof(path)));
    assert(!m3ds_sd_join(M3DS_SD_ROOT, "settings.bin", path, 5u));

    assert(!m3ds_sd_sector_save_path(
        M3DS_SD_ROOT, "../serpulo", 170u, path, sizeof(path)));
    assert(!m3ds_sd_sector_save_path(
        M3DS_SD_ROOT, "serpulo/x", 170u, path, sizeof(path)));
    assert(!m3ds_sd_sector_save_path(
        M3DS_SD_ROOT, "", 170u, path, sizeof(path)));
    assert(!m3ds_sd_sector_save_path(
        NULL, "serpulo", 170u, path, sizeof(path)));
    assert(!m3ds_sd_sector_save_path(
        M3DS_SD_ROOT, "serpulo", 170u, path, 8u));
}

int main(void) {
    test_settings_paths();
    test_sector_paths();
    test_invalid_paths();
    puts("sd path tests passed");
    return 0;
}
