#include "platform/settings_store.h"

#include <stdio.h>
#include <string.h>

#include "platform/sd_paths.h"

static bool file_exists(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        return false;
    }
    fclose(f);
    return true;
}

typedef enum ReadStatus {
    READ_OK = 0,
    READ_MISSING = 1,
    READ_ERROR = 2,
    READ_TOO_LARGE = 3
} ReadStatus;

static ReadStatus read_file_status(
    const char *path,
    void *out,
    size_t capacity,
    size_t *out_size) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        return READ_MISSING;
    }
    if (fseek(f, 0L, SEEK_END) != 0) {
        fclose(f);
        return READ_ERROR;
    }
    long length = ftell(f);
    if (length < 0) {
        fclose(f);
        return READ_ERROR;
    }
    if ((size_t)length > capacity) {
        fclose(f);
        return READ_TOO_LARGE;
    }
    rewind(f);
    size_t read_bytes = fread(out, 1u, (size_t)length, f);
    bool ok = ferror(f) == 0 && read_bytes == (size_t)length;
    fclose(f);
    if (!ok) {
        return READ_ERROR;
    }
    if (out_size) {
        *out_size = read_bytes;
    }
    return READ_OK;
}

static bool read_file(
    const char *path,
    void *out,
    size_t capacity,
    size_t *out_size) {
    return read_file_status(path, out, capacity, out_size) == READ_OK;
}

static bool write_file(const char *path, const void *data, size_t size) {
    FILE *f = fopen(path, "wb");
    if (!f) {
        return false;
    }
    bool ok = size == 0u || fwrite(data, 1u, size, f) == size;
    if (fflush(f) != 0) {
        ok = false;
    }
    if (fclose(f) != 0) {
        ok = false;
    }
    if (!ok) {
        remove(path);
    }
    return ok;
}

static bool write_temp_then_rename(
    const char *path,
    const void *data,
    size_t size) {
    char temp[160];
    int written = snprintf(temp, sizeof(temp), "%s.tmp", path);
    if (written < 0 || (size_t)written >= sizeof(temp)) {
        return false;
    }
    if (!write_file(temp, data, size)) {
        return false;
    }
    /* libctru sdmc rename does not replace an existing destination. */
    if (file_exists(path) && remove(path) != 0) {
        remove(temp);
        return false;
    }
    if (rename(temp, path) != 0) {
        remove(temp);
        return false;
    }
    return true;
}

bool m3ds_settings_store_write(
    const char *root,
    const void *data,
    size_t size) {
    char path[128];
    char backup[128];
    if (!m3ds_sd_settings_path(root, path, sizeof(path)) ||
        !m3ds_sd_settings_backup_path(root, backup, sizeof(backup))) {
        return false;
    }

    char temp[160];
    int written = snprintf(temp, sizeof(temp), "%s.tmp", path);
    if (written < 0 || (size_t)written >= sizeof(temp)) {
        return false;
    }
    if (!write_file(temp, data, size)) {
        return false;
    }

    if (file_exists(path)) {
        (void)remove(backup);
        if (rename(path, backup) != 0) {
            remove(temp);
            return false;
        }
    }
    if (rename(temp, path) != 0) {
        remove(temp);
        return false;
    }
    return true;
}

bool m3ds_settings_store_read(
    const char *root,
    void *out,
    size_t capacity,
    size_t *out_size) {
    if (!out) {
        return false;
    }
    char path[128];
    char backup[128];
    if (!m3ds_sd_settings_path(root, path, sizeof(path)) ||
        !m3ds_sd_settings_backup_path(root, backup, sizeof(backup))) {
        return false;
    }
    ReadStatus primary = read_file_status(path, out, capacity, out_size);
    if (primary == READ_OK) {
        return true;
    }
    if (primary == READ_TOO_LARGE) {
        return false;
    }
    return read_file_status(backup, out, capacity, out_size) == READ_OK;
}

bool m3ds_sector_store_write(
    const char *root,
    const char *planet,
    uint32_t sector_id,
    const void *data,
    size_t size) {
    char path[160];
    if (!m3ds_sd_sector_save_path(
            root, planet, sector_id, path, sizeof(path))) {
        return false;
    }
    return write_temp_then_rename(path, data, size);
}

bool m3ds_sector_store_read(
    const char *root,
    const char *planet,
    uint32_t sector_id,
    void *out,
    size_t capacity,
    size_t *out_size) {
    if (!out) {
        return false;
    }
    char path[160];
    if (!m3ds_sd_sector_save_path(
            root, planet, sector_id, path, sizeof(path))) {
        return false;
    }
    return read_file(path, out, capacity, out_size);
}

bool m3ds_storage_probe_roundtrip(
    const char *root,
    size_t *out_size) {
    char path[160];
    if (!m3ds_sd_join(root, "storage-probe.bin", path, sizeof(path))) {
        return false;
    }
    static const unsigned char payload[4] = {'M', '3', 'D', 'S'};
    if (!write_temp_then_rename(path, payload, sizeof(payload))) {
        return false;
    }
    unsigned char readback[8];
    size_t size = 0u;
    bool ok = read_file(path, readback, sizeof(readback), &size) &&
              size == sizeof(payload) &&
              memcmp(readback, payload, sizeof(payload)) == 0;
    remove(path);
    if (ok && out_size) {
        *out_size = size;
    }
    return ok;
}
