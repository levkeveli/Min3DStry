#include "audio/audio_ambient.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct MockCtx {
    int last_clip;
    bool last_looping;
    int active_channel;
    int stop_calls;
} MockCtx;

static MockCtx g_ctx;

typedef struct MockFile {
    const char *path;
    const int16_t *samples;
    size_t frames;
} MockFile;

static bool mock_read(
    const char *path,
    int16_t *samples,
    size_t capacity_bytes,
    size_t *out_bytes,
    void *raw) {
    const MockFile *files = (const MockFile *)raw;
    for (size_t i = 0u; files[i].path; ++i) {
        if (strcmp(files[i].path, path) != 0) {
            continue;
        }
        size_t bytes = files[i].frames * sizeof(int16_t);
        if (bytes > capacity_bytes) {
            return false;
        }
        memcpy(samples, files[i].samples, bytes);
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
    return desc && desc->samples;
}

static void mock_unload(void *raw, int clip_id) {
    (void)raw;
    (void)clip_id;
}

static bool mock_voice_start(
    void *raw,
    uint32_t channel,
    int clip_id,
    const M3dsAudioClipDesc *desc,
    float gain_left,
    float gain_right,
    bool looping) {
    MockCtx *ctx = (MockCtx *)raw;
    (void)gain_left;
    (void)gain_right;
    if (!desc || !desc->samples) {
        return false;
    }
    ctx->last_clip = clip_id;
    ctx->last_looping = looping;
    ctx->active_channel = (int)channel;
    return true;
}

static void mock_voice_stop(void *raw, uint32_t channel) {
    MockCtx *ctx = (MockCtx *)raw;
    (void)channel;
    ctx->active_channel = -1;
    ctx->stop_calls += 1;
}

static bool mock_voice_is_active(void *raw, uint32_t channel) {
    MockCtx *ctx = (MockCtx *)raw;
    return (int)channel == ctx->active_channel;
}

static M3dsAudioBackend make_backend(void) {
    memset(&g_ctx, 0, sizeof(g_ctx));
    g_ctx.active_channel = -1;
    M3dsAudioBackend backend;
    memset(&backend, 0, sizeof(backend));
    backend.ctx = &g_ctx;
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

static void test_start_and_stop(void) {
    static const int16_t conveyor_pcm[] = {1, 2, 3, 4};
    static const int16_t drill_pcm[] = {5, 6, 7, 8};
    static const MockFile files[] = {
        {"romfs:/audio/conveyor.loop.pcm", conveyor_pcm, 4u},
        {"romfs:/audio/drill.loop.pcm", drill_pcm, 4u},
        {NULL, NULL, 0u},
    };
    static const M3dsAudioIndexEntry entries[] = {
        {"conveyor.loop", "romfs:/audio/conveyor.loop.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/world/blocks/distribution/Conveyor.java", 49u},
        {"drill.loop", "romfs:/audio/drill.loop.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/world/blocks/production/Drill.java", 83u},
    };
    int16_t storage[8];
    M3dsAudioMixer mixer;
    M3dsAudioBackend backend = make_backend();
    assert(m3ds_audio_mixer_init(&mixer, &backend));
    M3dsAudioRegistry registry;
    assert(m3ds_audio_registry_load(
               &registry, &mixer, entries, 2u, mock_read, (void *)files, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_OK);

    M3dsAudioAmbient ambient;
    m3ds_audio_ambient_start(&registry, 0.2f, &ambient);
    assert(ambient.conveyor_voice != M3DS_AUDIO_INVALID_VOICE);
    assert(ambient.drill_voice != M3DS_AUDIO_INVALID_VOICE);
    assert(ambient.conveyor_voice != ambient.drill_voice);
    assert(g_ctx.last_looping);
    assert(g_ctx.active_channel >= 0);
    assert(mixer.voices[g_ctx.active_channel].params.looping);

    m3ds_audio_ambient_stop(&mixer, &ambient);
    assert(ambient.conveyor_voice == M3DS_AUDIO_INVALID_VOICE);
    assert(ambient.drill_voice == M3DS_AUDIO_INVALID_VOICE);
    assert(g_ctx.stop_calls >= 2);
    assert(g_ctx.active_channel < 0);

    /* Repeated and invalid-handle stops must be harmless. */
    m3ds_audio_ambient_stop(&mixer, &ambient);
    M3dsAudioAmbient stale = {12345u, 67890u};
    m3ds_audio_ambient_stop(&mixer, &stale);
    m3ds_audio_ambient_stop(NULL, &stale);
    assert(stale.conveyor_voice == M3DS_AUDIO_INVALID_VOICE);
    assert(stale.drill_voice == M3DS_AUDIO_INVALID_VOICE);

    m3ds_audio_mixer_shutdown(&mixer);
}

static void test_invalid_arguments(void) {
    M3dsAudioAmbient ambient = {1u, 2u};
    m3ds_audio_ambient_start(NULL, 0.2f, &ambient);
    assert(ambient.conveyor_voice == M3DS_AUDIO_INVALID_VOICE);
    assert(ambient.drill_voice == M3DS_AUDIO_INVALID_VOICE);

    m3ds_audio_ambient_start(NULL, 0.2f, NULL);
    m3ds_audio_ambient_stop(NULL, NULL);
}

int main(void) {
    test_start_and_stop();
    test_invalid_arguments();
    puts("audio ambient tests passed");
    return 0;
}
