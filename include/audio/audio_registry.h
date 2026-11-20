#ifndef MIN3DSTRY_AUDIO_AUDIO_REGISTRY_H
#define MIN3DSTRY_AUDIO_AUDIO_REGISTRY_H

#include "audio/audio_mixer.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct M3dsAudioIndexEntry {
    const char *id;
    const char *path;
    unsigned int rate;
    unsigned int channels;
    unsigned int frames;
    const char *trigger_file;
    unsigned int trigger_line;
} M3dsAudioIndexEntry;

/* Reads a PCM file into caller storage. Returns false on I/O failure. */
typedef bool (*M3dsAudioReadFn)(
    const char *path,
    int16_t *samples,
    size_t capacity_bytes,
    size_t *out_bytes,
    void *ctx);

typedef enum M3dsAudioRegistryStatus {
    M3DS_AUDIO_REGISTRY_OK = 0,
    M3DS_AUDIO_REGISTRY_ERR_INVALID = 1,
    M3DS_AUDIO_REGISTRY_ERR_CAPACITY = 2,
    M3DS_AUDIO_REGISTRY_ERR_IO = 3,
    M3DS_AUDIO_REGISTRY_ERR_MIXER = 4
} M3dsAudioRegistryStatus;

typedef struct M3dsAudioRegistry {
    M3dsAudioMixer *mixer;
    const M3dsAudioIndexEntry *entries;
    size_t entry_count;
    int16_t *storage;
    size_t capacity_bytes;
    size_t used_bytes;
    int clip_ids[M3DS_AUDIO_MAX_CLIPS];
    uint32_t clip_count;
} M3dsAudioRegistry;

M3dsAudioRegistryStatus m3ds_audio_registry_load(
    M3dsAudioRegistry *registry,
    M3dsAudioMixer *mixer,
    const M3dsAudioIndexEntry *entries,
    size_t count,
    M3dsAudioReadFn read_file,
    void *read_ctx,
    int16_t *storage,
    size_t capacity_bytes);

int m3ds_audio_registry_find(const M3dsAudioRegistry *registry, const char *id);
const char *m3ds_audio_registry_status_name(M3dsAudioRegistryStatus status);

/*
 * Reads a raw PCM16 file with standard stdio. Works for host paths and for
 * libctru "romfs:/..." paths once romfsInit() has succeeded.
 */
bool m3ds_audio_read_file_stdio(
    const char *path,
    int16_t *samples,
    size_t capacity_bytes,
    size_t *out_bytes,
    void *ctx);

#endif
