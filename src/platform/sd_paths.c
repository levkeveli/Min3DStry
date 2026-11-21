#include "platform/sd_paths.h"

#include <stdio.h>
#include <string.h>

static bool valid_component(const char *value, bool allow_dot) {
    if (!value || value[0] == '\0') {
        return false;
    }
    if (value[0] == '/' || value[0] == '\\') {
        return false;
    }
    for (const char *p = value; *p; ++p) {
        char c = *p;
        bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                  (c >= '0' && c <= '9') || c == '_' || c == '-';
        if (c == '.' && allow_dot) {
            ok = true;
        }
        if (!ok) {
            return false;
        }
    }
    if (strstr(value, "..") != NULL) {
        return false;
    }
    return true;
}

static bool valid_root(const char *value) {
    if (!value || value[0] == '\0' || strstr(value, "..") != NULL) {
        return false;
    }
    for (const char *p = value; *p; ++p) {
        char c = *p;
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' ||
              c == '/' || c == ':')) {
            return false;
        }
    }
    return true;
}

bool m3ds_sd_join(
    const char *root,
    const char *name,
    char *out,
    size_t out_size) {
    if (!valid_root(root) || !valid_component(name, true) ||
        !out || out_size == 0u) {
        return false;
    }
    size_t root_len = strlen(root);
    bool has_slash = root_len > 0u && root[root_len - 1u] == '/';
    int written = snprintf(
        out, out_size, "%s%s%s", root, has_slash ? "" : "/", name);
    return written >= 0 && (size_t)written < out_size;
}

bool m3ds_sd_settings_path(const char *root, char *out, size_t out_size) {
    return m3ds_sd_join(root, "settings.bin", out, out_size);
}

bool m3ds_sd_settings_backup_path(const char *root, char *out, size_t out_size) {
    return m3ds_sd_join(root, "settings_backup.bin", out, out_size);
}

bool m3ds_sd_settings_backups_dir(const char *root, char *out, size_t out_size) {
    return m3ds_sd_join(root, "settings_backups", out, out_size);
}

bool m3ds_sd_sector_save_path(
    const char *root,
    const char *planet,
    uint32_t sector_id,
    char *out,
    size_t out_size) {
    if (!valid_component(planet, false) || !out || out_size == 0u) {
        return false;
    }
    char name[64];
    int written = snprintf(name, sizeof(name), "sector-%s-%u.msav", planet,
                           (unsigned)sector_id);
    if (written < 0 || (size_t)written >= sizeof(name)) {
        return false;
    }
    return m3ds_sd_join(root, name, out, out_size);
}
