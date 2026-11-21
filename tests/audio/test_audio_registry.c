#include "audio/audio_registry.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct MockPcmFile {
    const char *path;
    const int16_t *samples;
    size_t frames;
    unsigned int channels;
    bool fail;
    bool short_read;
} MockPcmFile;

typedef struct MockReadCtx {
    const MockPcmFile *files;
    size_t count;
    unsigned int calls;
} MockReadCtx;

typedef struct MockBackendCtx {
    bool active[M3DS_AUDIO_MAX_VOICES];
} MockBackendCtx;

static MockBackendCtx g_backend_ctx;

static bool mock_voice_start(
    void *raw,
    uint32_t channel,
    int clip_id,
    const M3dsAudioClipDesc *desc,
    float gain_left,
    float gain_right,
    bool looping) {
    MockBackendCtx *ctx = (MockBackendCtx *)raw;
    (void)clip_id;
    (void)gain_left;
    (void)gain_right;
    (void)looping;
    if (channel >= M3DS_AUDIO_MAX_VOICES || !desc || !desc->samples) {
        return false;
    }
    ctx->active[channel] = true;
    return true;
}

static void mock_voice_stop(void *raw, uint32_t channel) {
    MockBackendCtx *ctx = (MockBackendCtx *)raw;
    if (channel < M3DS_AUDIO_MAX_VOICES) {
        ctx->active[channel] = false;
    }
}

static bool mock_voice_is_active(void *raw, uint32_t channel) {
    MockBackendCtx *ctx = (MockBackendCtx *)raw;
    return channel < M3DS_AUDIO_MAX_VOICES && ctx->active[channel];
}

static bool mock_read(
    const char *path,
    int16_t *samples,
    size_t capacity_bytes,
    size_t *out_bytes,
    void *raw) {
    MockReadCtx *ctx = (MockReadCtx *)raw;
    ctx->calls += 1u;
    for (size_t i = 0u; i < ctx->count; ++i) {
        const MockPcmFile *file = &ctx->files[i];
        if (strcmp(file->path, path) != 0) {
            continue;
        }
        if (file->fail) {
            return false;
        }
        size_t bytes = file->frames * file->channels * sizeof(int16_t);
        if (bytes > capacity_bytes) {
            return false;
        }
        if (file->short_read) {
            bytes /= 2u;
        }
        memcpy(samples, file->samples, bytes);
        *out_bytes = bytes;
        return true;
    }
    return false;
}

static M3dsAudioBackendState mock_init(void *raw, uint32_t *out_rc) {
    (void)raw;
    if (out_rc) {
        *out_rc = 0u;
    }
    return M3DS_AUDIO_STATE_READY;
}

static void mock_shutdown(void *raw) {
    (void)raw;
}

static bool mock_upload(
    void *raw,
    int clip_id,
    const M3dsAudioClipDesc *desc) {
    (void)raw;
    (void)clip_id;
    return desc && desc->samples && desc->frame_count > 0u;
}

static void mock_unload(void *raw, int clip_id) {
    (void)raw;
    (void)clip_id;
}

static M3dsAudioBackend make_backend(void) {
    memset(&g_backend_ctx, 0, sizeof(g_backend_ctx));
    M3dsAudioBackend backend;
    memset(&backend, 0, sizeof(backend));
    backend.ctx = &g_backend_ctx;
    backend.name = "mock";
    backend.init = mock_init;
    backend.shutdown = mock_shutdown;
    backend.upload_clip = mock_upload;
    backend.unload_clip = mock_unload;
    backend.voice_start = mock_voice_start;
    backend.voice_stop = mock_voice_stop;
    backend.voice_is_active = mock_voice_is_active;
    return backend;
}

static void test_load_and_find(void) {
    static const int16_t click[] = {1, -2, 3, -4};
    static const int16_t wave[] = {10, 20, 30, 40, 50, 60};
    static const MockPcmFile files[] = {
        {"romfs:/audio/ui.button.pcm", click, 4u, 1u, false, false},
        {"romfs:/audio/wave.spawn.pcm", wave, 3u, 2u, false, false},
    };
    static const M3dsAudioIndexEntry entries[] = {
        {"ui.button", "romfs:/audio/ui.button.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/core/UI.java", 146u},
        {"wave.spawn", "romfs:/audio/wave.spawn.pcm", 22050u, 2u, 3u,
         "core/src/mindustry/core/Control.java", 129u},
    };
    MockReadCtx read_ctx = {files, 2u, 0u};
    int16_t storage[16];
    M3dsAudioMixer mixer;
    M3dsAudioBackend backend = make_backend();
    assert(m3ds_audio_mixer_init(&mixer, &backend));

    M3dsAudioRegistry registry;
    M3dsAudioRegistryStatus status = m3ds_audio_registry_load(
        &registry,
        &mixer,
        entries,
        2u,
        mock_read,
        &read_ctx,
        storage,
        sizeof(storage));
    assert(status == M3DS_AUDIO_REGISTRY_OK);
    assert(registry.clip_count == 2u);
    assert(registry.entry_count == 2u);
    assert(registry.used_bytes == 4u * sizeof(int16_t) + 3u * 2u * sizeof(int16_t));
    assert(read_ctx.calls == 2u);
    assert(mixer.stats.registered_clips == 2u);

    int button = m3ds_audio_registry_find(&registry, "ui.button");
    int wave_clip = m3ds_audio_registry_find(&registry, "wave.spawn");
    assert(button >= 0);
    assert(wave_clip >= 0);
    assert(button != wave_clip);
    assert(m3ds_audio_registry_find(&registry, "missing") == M3DS_AUDIO_INVALID_CLIP);
    assert(m3ds_audio_registry_find(&registry, NULL) == M3DS_AUDIO_INVALID_CLIP);
    assert(m3ds_audio_registry_find(NULL, "ui.button") == M3DS_AUDIO_INVALID_CLIP);

    assert(mixer.clips[button].in_use);
    assert(mixer.clips[button].desc.frame_count == 4u);
    assert(mixer.clips[button].desc.channels == 1u);
    assert(mixer.clips[button].desc.samples == storage);
    assert(mixer.clips[button].desc.samples[0] == 1);
    assert(mixer.clips[button].desc.samples[3] == -4);
    assert(mixer.clips[wave_clip].desc.samples == storage + 4);
    assert(mixer.clips[wave_clip].desc.samples[0] == 10);
    assert(mixer.clips[wave_clip].desc.samples[5] == 60);

    uint32_t voice = m3ds_audio_mixer_play(&mixer, button, NULL);
    assert(voice != M3DS_AUDIO_INVALID_VOICE);
    m3ds_audio_mixer_shutdown(&mixer);
    assert(m3ds_audio_mixer_state(&mixer) == M3DS_AUDIO_STATE_UNINITIALIZED);
    assert(strcmp(m3ds_audio_registry_status_name(status), "ok") == 0);
}

static void test_invalid_arguments(void) {
    static const int16_t pcm[] = {1, 2};
    static const MockPcmFile files[] = {
        {"pcm:/a", pcm, 2u, 1u, false, false},
    };
    static const M3dsAudioIndexEntry good = {
        "a", "pcm:/a", 22050u, 1u, 2u, "src/A.java", 1u};
    MockReadCtx read_ctx = {files, 1u, 0u};
    int16_t storage[8];
    M3dsAudioMixer mixer;
    M3dsAudioBackend backend = make_backend();
    assert(m3ds_audio_mixer_init(&mixer, &backend));
    M3dsAudioRegistry registry;

    assert(m3ds_audio_registry_load(
               NULL, &mixer, &good, 1u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);
    assert(m3ds_audio_registry_load(
               &registry, NULL, &good, 1u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);
    assert(m3ds_audio_registry_load(
               &registry, &mixer, NULL, 1u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);
    assert(m3ds_audio_registry_load(
               &registry, &mixer, &good, 0u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);
    assert(m3ds_audio_registry_load(
               &registry, &mixer, &good, M3DS_AUDIO_MAX_CLIPS + 1u, mock_read,
               &read_ctx, storage, sizeof(storage)) ==
           M3DS_AUDIO_REGISTRY_ERR_INVALID);
    assert(m3ds_audio_registry_load(
               &registry, &mixer, &good, 1u, NULL, NULL, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);
    assert(m3ds_audio_registry_load(
               &registry, &mixer, &good, 1u, mock_read, &read_ctx, NULL,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);
    assert(read_ctx.calls == 0u);
    assert(mixer.stats.registered_clips == 0u);

    M3dsAudioIndexEntry bad = good;
    bad.rate = 3999u;
    assert(m3ds_audio_registry_load(
               &registry, &mixer, &bad, 1u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);
    bad = good;
    bad.channels = 3u;
    assert(m3ds_audio_registry_load(
               &registry, &mixer, &bad, 1u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);
    bad = good;
    bad.frames = 0u;
    assert(m3ds_audio_registry_load(
               &registry, &mixer, &bad, 1u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);
    bad = good;
    bad.id = "";
    assert(m3ds_audio_registry_load(
               &registry, &mixer, &bad, 1u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);
    bad = good;
    bad.path = NULL;
    assert(m3ds_audio_registry_load(
               &registry, &mixer, &bad, 1u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);

    M3dsAudioIndexEntry duplicate[] = {good, good};
    assert(m3ds_audio_registry_load(
               &registry, &mixer, duplicate, 2u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_INVALID);

    m3ds_audio_mixer_shutdown(&mixer);
    assert(strcmp(m3ds_audio_registry_status_name(M3DS_AUDIO_REGISTRY_ERR_INVALID),
                  "invalid") == 0);
    assert(strcmp(m3ds_audio_registry_status_name(M3DS_AUDIO_REGISTRY_ERR_CAPACITY),
                  "capacity") == 0);
    assert(strcmp(m3ds_audio_registry_status_name(M3DS_AUDIO_REGISTRY_ERR_IO), "io") == 0);
    assert(strcmp(m3ds_audio_registry_status_name(M3DS_AUDIO_REGISTRY_ERR_MIXER),
                  "mixer") == 0);
}

static void test_capacity_and_io_rollback(void) {
    static const int16_t pcm[] = {1, 2, 3, 4, 5, 6, 7, 8};
    static MockPcmFile files[] = {
        {"pcm:/a", pcm, 4u, 1u, false, false},
        {"pcm:/b", pcm, 4u, 1u, false, false},
    };
    static const M3dsAudioIndexEntry entries[] = {
        {"a", "pcm:/a", 22050u, 1u, 4u, "src/A.java", 1u},
        {"b", "pcm:/b", 22050u, 1u, 4u, "src/B.java", 2u},
    };
    MockReadCtx read_ctx = {files, 2u, 0u};
    int16_t storage[8];
    M3dsAudioMixer mixer;
    M3dsAudioBackend backend = make_backend();
    assert(m3ds_audio_mixer_init(&mixer, &backend));
    M3dsAudioRegistry registry;

    /* Exactly one clip fits, so the second one must roll the first back. */
    assert(m3ds_audio_registry_load(
               &registry, &mixer, entries, 2u, mock_read, &read_ctx, storage,
               sizeof(int16_t) * 4u) == M3DS_AUDIO_REGISTRY_ERR_CAPACITY);
    assert(registry.clip_count == 0u);
    assert(registry.used_bytes == 0u);
    assert(mixer.stats.registered_clips == 0u);

    read_ctx.calls = 0u;
    files[1].fail = true;
    assert(m3ds_audio_registry_load(
               &registry, &mixer, entries, 2u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_IO);
    assert(registry.clip_count == 0u);
    assert(mixer.stats.registered_clips == 0u);

    read_ctx.calls = 0u;
    files[1].fail = false;
    files[1].short_read = true;
    assert(m3ds_audio_registry_load(
               &registry, &mixer, entries, 2u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_IO);
    assert(mixer.stats.registered_clips == 0u);

    read_ctx.calls = 0u;
    files[1].short_read = false;
    const M3dsAudioIndexEntry unknown = {
        "missing", "pcm:/missing", 22050u, 1u, 4u, "src/A.java", 1u};
    assert(m3ds_audio_registry_load(
               &registry, &mixer, &unknown, 1u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_IO);

    m3ds_audio_mixer_shutdown(&mixer);
}

static void test_mixer_failure(void) {
    static const int16_t pcm[] = {1, 2};
    static const MockPcmFile files[] = {
        {"pcm:/a", pcm, 2u, 1u, false, false},
    };
    static const M3dsAudioIndexEntry entry = {
        "a", "pcm:/a", 22050u, 1u, 2u, "src/A.java", 1u};
    MockReadCtx read_ctx = {files, 1u, 0u};
    int16_t storage[4];
    M3dsAudioMixer mixer;
    memset(&mixer, 0, sizeof(mixer));
    M3dsAudioRegistry registry;

    assert(m3ds_audio_registry_load(
               &registry, &mixer, &entry, 1u, mock_read, &read_ctx, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_ERR_MIXER);
    assert(registry.clip_count == 0u);
    assert(mixer.stats.registered_clips == 0u);
}

static void test_stdio_reader(void) {
    static const int16_t pcm[] = {7, -8, 9, -10};
    static const M3dsAudioIndexEntry entry = {
        "test.tone", "build/host/audio_registry_test.pcm", 22050u, 2u, 2u,
        "src/Test.java", 9u};
    const char *path = entry.path;
    FILE *file = fopen(path, "wb");
    assert(file);
    assert(fwrite(pcm, sizeof(int16_t), 4u, file) == 4u);
    assert(fclose(file) == 0);

    int16_t storage[4];
    size_t read_bytes = 0u;
    assert(m3ds_audio_read_file_stdio(path, storage, sizeof(storage), &read_bytes, NULL));
    assert(read_bytes == sizeof(storage));
    assert(memcmp(storage, pcm, sizeof(storage)) == 0);

    assert(!m3ds_audio_read_file_stdio(
        path, storage, sizeof(storage) - 2u, &read_bytes, NULL));
    assert(!m3ds_audio_read_file_stdio("build/host/audio_registry_missing.pcm",
                                       storage, sizeof(storage), &read_bytes, NULL));

    M3dsAudioMixer mixer;
    M3dsAudioBackend backend = make_backend();
    assert(m3ds_audio_mixer_init(&mixer, &backend));
    M3dsAudioRegistry registry;
    assert(m3ds_audio_registry_load(
               &registry, &mixer, &entry, 1u, m3ds_audio_read_file_stdio, NULL,
               storage, sizeof(storage)) == M3DS_AUDIO_REGISTRY_OK);
    int clip = m3ds_audio_registry_find(&registry, "test.tone");
    assert(clip >= 0);
    assert(mixer.clips[clip].desc.frame_count == 2u);
    assert(mixer.clips[clip].desc.channels == 2u);
    assert(mixer.clips[clip].desc.samples[0] == 7);
    assert(mixer.clips[clip].desc.samples[3] == -10);
    m3ds_audio_mixer_shutdown(&mixer);
    assert(remove(path) == 0);
}

int main(void) {
    test_load_and_find();
    test_invalid_arguments();
    test_capacity_and_io_rollback();
    test_mixer_failure();
    test_stdio_reader();
    puts("audio registry tests passed");
    return 0;
}
