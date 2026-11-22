#include "ui/user_schematics.h"

#include <ctype.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "platform/msav_inflate.h"

#define MSCH_HEADER_BYTES 5u
#define MSCH_PREFIX_BYTES 4096u
#define MSCH_FILE_LIMIT (64u * 1024u)

static uint16_t read_be16(const uint8_t *data, size_t size, size_t *pos) {
    if (*pos + 2u > size) {
        return 0u;
    }
    uint16_t value = (uint16_t)((data[*pos] << 8) | data[*pos + 1u]);
    *pos += 2u;
    return value;
}

static bool skip_bytes(size_t size, size_t *pos, size_t count) {
    if (*pos + count > size) {
        return false;
    }
    *pos += count;
    return true;
}

bool m3ds_msch_parse(const uint8_t *data, size_t size, M3dsUserSchematic *out) {
    if (!data || !out || size < MSCH_HEADER_BYTES + 6u) {
        return false;
    }
    if (memcmp(data, "msch", 4u) != 0) {
        return false;
    }

    uint8_t prefix[MSCH_PREFIX_BYTES];
    size_t produced = 0u;
    if (!m3ds_msav_inflate_prefix(data + MSCH_HEADER_BYTES,
                                  size - MSCH_HEADER_BYTES, prefix,
                                  sizeof(prefix), &produced)) {
        return false;
    }

    size_t pos = 0u;
    unsigned width = read_be16(prefix, produced, &pos);
    unsigned height = read_be16(prefix, produced, &pos);
    if (width == 0u || height == 0u || pos >= produced) {
        return false;
    }

    unsigned tag_count = prefix[pos++];
    for (unsigned tag = 0u; tag < tag_count; ++tag) {
        unsigned key_len = read_be16(prefix, produced, &pos);
        if (!skip_bytes(produced, &pos, key_len)) {
            return false;
        }
        unsigned value_len = read_be16(prefix, produced, &pos);
        if (!skip_bytes(produced, &pos, value_len)) {
            return false;
        }
    }

    if (pos >= produced) {
        return false;
    }
    unsigned block_count = prefix[pos++];
    if (block_count == 0u) {
        return false;
    }

    out->width = width;
    out->height = height;
    out->blocks = block_count;
    out->summary[0] = '\0';
    size_t summary_used = 0u;
    unsigned listed = 0u;
    for (unsigned block = 0u; block < block_count; ++block) {
        unsigned length = read_be16(prefix, produced, &pos);
        if (pos + length > produced) {
            return false;
        }
        if (listed < 5u) {
            if (listed > 0u && summary_used + 2u < sizeof(out->summary)) {
                out->summary[summary_used++] = ',';
                out->summary[summary_used++] = ' ';
            }
            size_t copy = length;
            if (copy > sizeof(out->summary) - 1u - summary_used) {
                copy = sizeof(out->summary) - 1u - summary_used;
            }
            memcpy(out->summary + summary_used, prefix + pos, copy);
            summary_used += copy;
            out->summary[summary_used] = '\0';
            ++listed;
        }
        pos += length;
    }
    if (listed == 5u && block_count > 5u &&
        summary_used + 5u < sizeof(out->summary)) {
        memcpy(out->summary + summary_used, ", ...", 5u);
        summary_used += 5u;
        out->summary[summary_used] = '\0';
    }
    return true;
}

static void copy_stem(char *out, size_t out_size, const char *filename) {
    size_t length = strlen(filename);
    if (length > 5u && strcmp(filename + length - 5u, ".msch") == 0) {
        length -= 5u;
    }
    if (length >= out_size) {
        length = out_size - 1u;
    }
    memcpy(out, filename, length);
    out[length] = '\0';
}

static int lower_compare(const char *a, const char *b) {
    while (*a && *b) {
        int ca = tolower((unsigned char)*a);
        int cb = tolower((unsigned char)*b);
        if (ca != cb) {
            return ca - cb;
        }
        ++a;
        ++b;
    }
    return (unsigned char)*a - (unsigned char)*b;
}

static void sort_entries(M3dsUserSchematic *entries, size_t count) {
    for (size_t i = 1u; i < count; ++i) {
        M3dsUserSchematic value = entries[i];
        size_t j = i;
        while (j > 0u && lower_compare(entries[j - 1u].name, value.name) > 0) {
            entries[j] = entries[j - 1u];
            --j;
        }
        entries[j] = value;
    }
}

size_t m3ds_user_schematics_scan(const char *directory, M3dsUserSchematic *out,
                                 size_t max) {
    if (!directory || !out || max == 0u) {
        return 0u;
    }
    DIR *dir = opendir(directory);
    if (!dir) {
        return 0u;
    }

    size_t count = 0u;
    struct dirent *entry;
    while (count < max && (entry = readdir(dir)) != NULL) {
        const char *name = entry->d_name;
        size_t length = strlen(name);
        if (length <= 5u || strcmp(name + length - 5u, ".msch") != 0) {
            continue;
        }
        char path[256];
        int written = snprintf(path, sizeof(path), "%s/%s", directory, name);
        if (written < 0 || (size_t)written >= sizeof(path)) {
            continue;
        }
        FILE *file = fopen(path, "rb");
        if (!file) {
            continue;
        }
        if (fseek(file, 0, SEEK_END) != 0) {
            fclose(file);
            continue;
        }
        long file_size = ftell(file);
        if (file_size <= 0 || (unsigned long)file_size > MSCH_FILE_LIMIT) {
            fclose(file);
            continue;
        }
        if (fseek(file, 0, SEEK_SET) != 0) {
            fclose(file);
            continue;
        }
        uint8_t *buffer = (uint8_t *)malloc((size_t)file_size);
        if (!buffer) {
            fclose(file);
            continue;
        }
        size_t read = fread(buffer, 1u, (size_t)file_size, file);
        fclose(file);
        if (read != (size_t)file_size) {
            free(buffer);
            continue;
        }
        M3dsUserSchematic parsed;
        memset(&parsed, 0, sizeof(parsed));
        bool ok = m3ds_msch_parse(buffer, read, &parsed);
        free(buffer);
        if (!ok) {
            continue;
        }
        copy_stem(parsed.name, sizeof(parsed.name), name);
        out[count++] = parsed;
    }
    closedir(dir);
    sort_entries(out, count);
    return count;
}
