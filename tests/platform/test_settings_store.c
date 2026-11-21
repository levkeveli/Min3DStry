#include "platform/settings_store.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

#ifdef _WIN32
#include <direct.h>
static int make_dir(const char *path) { return _mkdir(path); }
#else
static int make_dir(const char *path) { return mkdir(path, 0777); }
#endif

#include "platform/sd_paths.h"

static void remove_paths(void) {
    char path[128];
    char backup[128];
    char sector[160];
    if (m3ds_sd_settings_path("build/host/storage-root", path, sizeof(path))) {
        remove(path);
    }
    if (m3ds_sd_settings_backup_path(
            "build/host/storage-root", backup, sizeof(backup))) {
        remove(backup);
    }
    if (m3ds_sd_sector_save_path(
            "build/host/storage-root", "serpulo", 170u, sector,
            sizeof(sector))) {
        remove(sector);
    }
}

static void test_settings_roundtrip_and_backup(void) {
    const char *root = "build/host/storage-root";
    (void)make_dir("build/host");
    (void)make_dir(root);
    remove_paths();

    assert(m3ds_settings_store_write(root, "alpha", 5u));
    M3dsSettingsStatus status;
    assert(m3ds_settings_store_status(root, &status));
    assert(status.primary_present && !status.backup_present);
    assert(status.primary_bytes == 5u && status.backup_bytes == 0u);
    char buffer[32];
    size_t size = 0u;
    assert(m3ds_settings_store_read(root, buffer, sizeof(buffer), &size));
    assert(size == 5u && memcmp(buffer, "alpha", 5u) == 0);

    assert(m3ds_settings_store_write(root, "beta!", 5u));
    assert(m3ds_settings_store_status(root, &status));
    assert(status.primary_present && status.backup_present);
    assert(status.primary_bytes == 5u && status.backup_bytes == 5u);
    assert(m3ds_settings_store_read(root, buffer, sizeof(buffer), &size));
    assert(size == 5u && memcmp(buffer, "beta!", 5u) == 0);

    char path[128];
    assert(m3ds_sd_settings_path(root, path, sizeof(path)));
    assert(remove(path) == 0);
    assert(m3ds_settings_store_status(root, &status));
    assert(!status.primary_present && status.backup_present);
    assert(status.primary_bytes == 0u && status.backup_bytes == 5u);
    assert(m3ds_settings_store_read(root, buffer, sizeof(buffer), &size));
    assert(size == 5u && memcmp(buffer, "alpha", 5u) == 0);

    assert(!m3ds_settings_store_status(NULL, &status));
    assert(!m3ds_settings_store_status(root, NULL));

    assert(!m3ds_settings_store_read(root, buffer, 2u, &size));
    assert(!m3ds_settings_store_read(root, NULL, sizeof(buffer), &size));

    /* A too-small buffer must not silently fall back to older backup bytes. */
    assert(m3ds_settings_store_write(root, "abc", 3u));
    assert(m3ds_settings_store_write(root, "longer", 6u));
    assert(!m3ds_settings_store_read(root, buffer, 4u, &size));
    assert(m3ds_settings_store_read(root, buffer, sizeof(buffer), &size));
    assert(size == 6u && memcmp(buffer, "longer", 6u) == 0);
    remove_paths();
}

static void test_sector_roundtrip(void) {
    const char *root = "build/host/storage-root";
    const char payload[] = "msav-roundtrip";
    assert(m3ds_sector_store_write(
        root, "serpulo", 170u, payload, sizeof(payload)));
    char buffer[32];
    size_t size = 0u;
    assert(m3ds_sector_store_read(
        root, "serpulo", 170u, buffer, sizeof(buffer), &size));
    assert(size == sizeof(payload));
    assert(memcmp(buffer, payload, sizeof(payload)) == 0);

    assert(m3ds_sector_store_write(
        root, "serpulo", 170u, "second", 6u));
    assert(m3ds_sector_store_read(
        root, "serpulo", 170u, buffer, sizeof(buffer), &size));
    assert(size == 6u && memcmp(buffer, "second", 6u) == 0);

    char path[160];
    assert(m3ds_sd_sector_save_path(
        root, "serpulo", 170u, path, sizeof(path)));
    char temp[192];
    snprintf(temp, sizeof(temp), "%s.tmp", path);
    FILE *leftover = fopen(temp, "rb");
    assert(leftover == NULL);

    char probe_path[192];
    assert(m3ds_sd_join(root, "storage-probe.bin", probe_path, sizeof(probe_path)));
    FILE *stale = fopen(probe_path, "wb");
    assert(stale != NULL);
    assert(fwrite("stale", 1u, 5u, stale) == 5u);
    assert(fclose(stale) == 0);

    assert(!m3ds_sector_store_read(
        root, "serpulo", 171u, buffer, sizeof(buffer), &size));
    assert(!m3ds_sector_store_read(
        root, "serpulo", 170u, buffer, 4u, &size));

    size_t probe_size = 0u;
    assert(m3ds_storage_probe_roundtrip(root, &probe_size));
    assert(probe_size == 4u);
    remove_paths();
}

int main(void) {
    test_settings_roundtrip_and_backup();
    test_sector_roundtrip();
    puts("settings store tests passed");
    return 0;
}
