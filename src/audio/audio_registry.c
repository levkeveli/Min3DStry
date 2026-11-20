#include "audio/audio_registry.h"

#include <stdio.h>
#include <string.h>

static bool index_entry_valid(const M3dsAudioIndexEntry *entry) {
    if (!entry || !entry->id || entry->id[0] == '\0' || !entry->path ||
        entry->path[0] == '\0') {
        return false;
    }
    if (entry->rate < 4000u || entry->rate > 48000u) {
        return false;
    }
    if (entry->channels != 1u && entry->channels != 2u) {
        return false;
    }
    if (entry->frames == 0u || entry->frames > M3DS_AUDIO_MAX_CLIP_SAMPLES) {
        return false;
    }
    return true;
}

static void registry_rollback(M3dsAudioRegistry *registry) {
    for (uint32_t i = 0u; i < registry->clip_count; ++i) {
        m3ds_audio_mixer_unregister_clip(registry->mixer, registry->clip_ids[i]);
    }
    registry->clip_count = 0u;
    registry->used_bytes = 0u;
}

M3dsAudioRegistryStatus m3ds_audio_registry_load(
    M3dsAudioRegistry *registry,
    M3dsAudioMixer *mixer,
    const M3dsAudioIndexEntry *entries,
    size_t count,
    M3dsAudioReadFn read_file,
    void *read_ctx,
    int16_t *storage,
    size_t capacity_bytes) {
    if (!registry) {
        return M3DS_AUDIO_REGISTRY_ERR_INVALID;
    }

    memset(registry, 0, sizeof(*registry));
    for (uint32_t i = 0u; i < M3DS_AUDIO_MAX_CLIPS; ++i) {
        registry->clip_ids[i] = M3DS_AUDIO_INVALID_CLIP;
    }

    if (!mixer || !entries || count == 0u || count > M3DS_AUDIO_MAX_CLIPS ||
        !read_file || !storage || capacity_bytes < sizeof(int16_t)) {
        return M3DS_AUDIO_REGISTRY_ERR_INVALID;
    }

    registry->mixer = mixer;
    registry->entries = entries;
    registry->entry_count = count;
    registry->storage = storage;
    registry->capacity_bytes = capacity_bytes;

    size_t used_bytes = 0u;
    for (size_t i = 0u; i < count; ++i) {
        const M3dsAudioIndexEntry *entry = &entries[i];
        if (!index_entry_valid(entry)) {
            registry_rollback(registry);
            registry->entry_count = 0u;
            return M3DS_AUDIO_REGISTRY_ERR_INVALID;
        }
        for (size_t j = 0u; j < i; ++j) {
            if (strcmp(entries[j].id, entry->id) == 0) {
                registry_rollback(registry);
                registry->entry_count = 0u;
                return M3DS_AUDIO_REGISTRY_ERR_INVALID;
            }
        }

        size_t frame_bytes =
            (size_t)entry->channels * sizeof(int16_t);
        size_t clip_bytes = (size_t)entry->frames * frame_bytes;
        if (clip_bytes > capacity_bytes - used_bytes) {
            registry_rollback(registry);
            registry->entry_count = 0u;
            return M3DS_AUDIO_REGISTRY_ERR_CAPACITY;
        }

        int16_t *dest = (int16_t *)((unsigned char *)storage + used_bytes);
        size_t read_bytes = 0u;
        if (!read_file(entry->path, dest, clip_bytes, &read_bytes, read_ctx) ||
            read_bytes != clip_bytes) {
            registry_rollback(registry);
            registry->entry_count = 0u;
            return M3DS_AUDIO_REGISTRY_ERR_IO;
        }

        M3dsAudioClipDesc desc;
        desc.samples = dest;
        desc.frame_count = entry->frames;
        desc.sample_rate = entry->rate;
        desc.channels = (uint8_t)entry->channels;

        int clip_id = m3ds_audio_mixer_register_clip(mixer, &desc);
        if (clip_id == M3DS_AUDIO_INVALID_CLIP) {
            registry_rollback(registry);
            registry->entry_count = 0u;
            return M3DS_AUDIO_REGISTRY_ERR_MIXER;
        }

        registry->clip_ids[i] = clip_id;
        registry->clip_count += 1u;
        used_bytes += clip_bytes;
    }

    registry->used_bytes = used_bytes;
    return M3DS_AUDIO_REGISTRY_OK;
}

int m3ds_audio_registry_find(const M3dsAudioRegistry *registry, const char *id) {
    if (!registry || !id) {
        return M3DS_AUDIO_INVALID_CLIP;
    }
    for (size_t i = 0u; i < registry->entry_count; ++i) {
        if (registry->entries[i].id && strcmp(registry->entries[i].id, id) == 0) {
            return registry->clip_ids[i];
        }
    }
    return M3DS_AUDIO_INVALID_CLIP;
}

const char *m3ds_audio_registry_status_name(M3dsAudioRegistryStatus status) {
    switch (status) {
    case M3DS_AUDIO_REGISTRY_OK:
        return "ok";
    case M3DS_AUDIO_REGISTRY_ERR_CAPACITY:
        return "capacity";
    case M3DS_AUDIO_REGISTRY_ERR_IO:
        return "io";
    case M3DS_AUDIO_REGISTRY_ERR_MIXER:
        return "mixer";
    case M3DS_AUDIO_REGISTRY_ERR_INVALID:
    default:
        return "invalid";
    }
}

bool m3ds_audio_read_file_stdio(
    const char *path,
    int16_t *samples,
    size_t capacity_bytes,
    size_t *out_bytes,
    void *ctx) {
    (void)ctx;
    if (!path || !samples || !out_bytes) {
        return false;
    }

    FILE *file = fopen(path, "rb");
    if (!file) {
        return false;
    }

    if (fseek(file, 0L, SEEK_END) != 0) {
        fclose(file);
        return false;
    }
    long size = ftell(file);
    if (size < 0 || (size_t)size > capacity_bytes) {
        fclose(file);
        return false;
    }
    rewind(file);

    size_t read_bytes =
        fread(samples, 1u, (size_t)size, file);
    bool ok = ferror(file) == 0 && read_bytes == (size_t)size;
    fclose(file);
    if (ok) {
        *out_bytes = read_bytes;
    }
    return ok;
}
