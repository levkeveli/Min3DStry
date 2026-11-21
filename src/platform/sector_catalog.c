#include "platform/sector_catalog.h"

#include "platform/save_slot.h"

#include <dirent.h>
#include <stdio.h>
#include <string.h>

static int compare_slots(const void *left, const void *right) {
    const M3dsSectorSlot *a = (const M3dsSectorSlot *)left;
    const M3dsSectorSlot *b = (const M3dsSectorSlot *)right;
    int planet = strcmp(a->planet, b->planet);
    if (planet != 0) {
        return planet;
    }
    return a->id < b->id ? -1 : (a->id > b->id ? 1 : 0);
}

/* Keeps the catalog sorted and deterministic regardless of readdir order. */
static void insert_sorted(
    M3dsSectorSlot *slots, size_t *count, size_t capacity,
    const M3dsSectorSlot *slot) {
    size_t position = 0u;
    while (position < *count && compare_slots(&slots[position], slot) < 0) {
        ++position;
    }
    if (position >= capacity) {
        return;
    }
    size_t last = *count < capacity ? *count : capacity - 1u;
    while (last > position) {
        slots[last] = slots[last - 1u];
        --last;
    }
    slots[position] = *slot;
    if (*count < capacity) {
        ++*count;
    }
}

size_t m3ds_sector_catalog_scan(
    const char *dir, M3dsSectorSlot *slots, size_t capacity) {
    if (!dir || !slots || capacity == 0u) {
        return 0u;
    }
    DIR *handle = opendir(dir);
    if (!handle) {
        return 0u;
    }
    size_t count = 0u;
    struct dirent *entry;
    while ((entry = readdir(handle)) != NULL) {
        char planet[M3DS_SECTOR_PLANET_MAX];
        unsigned id = 0u;
        int consumed = 0;
        if (sscanf(entry->d_name, "sector-%15[^-]-%u.msav%n", planet, &id,
                   &consumed) != 2 ||
            entry->d_name[consumed] != '\0') {
            continue;
        }
        char path[512];
        int length = snprintf(path, sizeof(path), "%s/%s", dir, entry->d_name);
        if (length <= 0 || (size_t)length >= sizeof(path)) {
            continue;
        }
        M3dsSaveSlotInfo info;
        if (!m3ds_save_slot_probe(path, &info) || !info.present) {
            continue;
        }
        M3dsSectorSlot slot;
        memset(&slot, 0, sizeof(slot));
        snprintf(slot.planet, sizeof(slot.planet), "%.15s", planet);
        slot.id = (uint32_t)id;
        slot.valid = info.valid;
        slot.compressed = info.compressed;
        slot.has_meta = info.has_meta;
        slot.wave = info.wave;
        snprintf(slot.map_name, sizeof(slot.map_name), "%.47s", info.map_name);
        slot.bytes = info.bytes;
        insert_sorted(slots, &count, capacity, &slot);
    }
    closedir(handle);
    return count;
}

bool m3ds_sector_catalog_label(
    const M3dsSectorSlot *slot, char *out, size_t capacity) {
    if (!slot || !out || capacity == 0u) {
        return false;
    }
    int written;
    if (slot->has_meta) {
        written = snprintf(out, capacity, "%.15s-%u %.47s w%u",
                           slot->planet, (unsigned)slot->id, slot->map_name,
                           (unsigned)slot->wave);
    } else {
        written = snprintf(out, capacity, "%.15s-%u %s %uB",
                           slot->planet, (unsigned)slot->id,
                           slot->valid ? "v"
                               : (slot->compressed ? "zlib" : "invalid"),
                           (unsigned)slot->bytes);
    }
    return written > 0 && (size_t)written < capacity;
}
