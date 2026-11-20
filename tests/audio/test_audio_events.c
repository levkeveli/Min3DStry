#include "audio/audio_events.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

typedef struct MockCtx {
    bool uploaded[M3DS_AUDIO_MAX_CLIPS];
    int last_clip;
    int active_channel;
    bool last_looping;
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
    MockCtx *ctx = (MockCtx *)raw;
    (void)desc;
    ctx->uploaded[clip_id] = true;
    return true;
}

static void mock_unload(void *raw, int clip_id) {
    MockCtx *ctx = (MockCtx *)raw;
    ctx->uploaded[clip_id] = false;
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
    (void)looping;
    if (!desc || !desc->samples) {
        return false;
    }
    ctx->last_clip = clip_id;
    ctx->active_channel = (int)channel;
    ctx->last_looping = looping;
    return true;
}

static void mock_voice_stop(void *raw, uint32_t channel) {
    MockCtx *ctx = (MockCtx *)raw;
    (void)channel;
    ctx->active_channel = -1;
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

static void test_event_ids_match_trigger_map(void) {
    assert(strcmp(m3ds_audio_event_id(M3DS_AUDIO_EVENT_UI_BUTTON), "ui.button") == 0);
    assert(strcmp(m3ds_audio_event_id(M3DS_AUDIO_EVENT_RESEARCH_UNLOCK), "ui.unlock") == 0);
    assert(strcmp(m3ds_audio_event_id(M3DS_AUDIO_EVENT_WAVE_SPAWN), "wave.spawn") == 0);
    assert(strcmp(m3ds_audio_event_id(M3DS_AUDIO_EVENT_SHOOT_DUO), "shoot.duo") == 0);
    assert(strcmp(m3ds_audio_event_id(M3DS_AUDIO_EVENT_ENEMY_KILL), "enemy.kill") == 0);
    assert(strcmp(m3ds_audio_event_id(M3DS_AUDIO_EVENT_CONVEYOR_LOOP), "conveyor.loop") == 0);
    assert(strcmp(m3ds_audio_event_id(M3DS_AUDIO_EVENT_DRILL_LOOP), "drill.loop") == 0);
    assert(strcmp(m3ds_audio_event_id(M3DS_AUDIO_EVENT_BLOCK_PLACE), "block.place") == 0);
    assert(strcmp(m3ds_audio_event_id(M3DS_AUDIO_EVENT_BLOCK_BREAK), "block.break") == 0);
    assert(m3ds_audio_event_id(M3DS_AUDIO_EVENT_COUNT) == NULL);
    assert(m3ds_audio_event_id((M3dsAudioEvent)99) == NULL);
}

static void test_event_play_uses_registry_clip(void) {
    static const int16_t button[] = {1, 2, 3, 4};
    static const int16_t unlock[] = {5, 6, 7, 8};
    static const int16_t wave[] = {9, 10, 11, 12};
    static const int16_t shoot[] = {13, 14, 15, 16};
    static const int16_t kill[] = {17, 18, 19, 20};
    static const int16_t conveyor[] = {21, 22, 23, 24};
    static const int16_t drill[] = {25, 26, 27, 28};
    static const int16_t place[] = {29, 30, 31, 32};
    static const int16_t break_sfx[] = {33, 34, 35, 36};
    static const MockFile files[] = {
        {"romfs:/audio/ui.button.pcm", button, 4u},
        {"romfs:/audio/ui.unlock.pcm", unlock, 4u},
        {"romfs:/audio/wave.spawn.pcm", wave, 4u},
        {"romfs:/audio/shoot.duo.pcm", shoot, 4u},
        {"romfs:/audio/enemy.kill.pcm", kill, 4u},
        {"romfs:/audio/conveyor.loop.pcm", conveyor, 4u},
        {"romfs:/audio/drill.loop.pcm", drill, 4u},
        {"romfs:/audio/block.place.pcm", place, 4u},
        {"romfs:/audio/block.break.pcm", break_sfx, 4u},
        {NULL, NULL, 0u},
    };
    static const M3dsAudioIndexEntry entries[] = {
        {"ui.button", "romfs:/audio/ui.button.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/core/UI.java", 146u},
        {"ui.unlock", "romfs:/audio/ui.unlock.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/ui/dialogs/ResearchDialog.java", 623u},
        {"wave.spawn", "romfs:/audio/wave.spawn.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/core/Control.java", 129u},
        {"shoot.duo", "romfs:/audio/shoot.duo.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/content/Blocks.java", 3333u},
        {"enemy.kill", "romfs:/audio/enemy.kill.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/type/UnitType.java", 944u},
        {"conveyor.loop", "romfs:/audio/conveyor.loop.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/world/blocks/distribution/Conveyor.java", 49u},
        {"drill.loop", "romfs:/audio/drill.loop.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/world/blocks/production/Drill.java", 83u},
        {"block.place", "romfs:/audio/block.place.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/world/Block.java", 1371u},
        {"block.break", "romfs:/audio/block.break.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/world/Block.java", 1378u},
    };
    int16_t storage[36];
    M3dsAudioMixer mixer;
    M3dsAudioBackend backend = make_backend();
    assert(m3ds_audio_mixer_init(&mixer, &backend));

    M3dsAudioRegistry registry;
    assert(m3ds_audio_registry_load(
               &registry, &mixer, entries, 9u, mock_read, (void *)files, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_OK);

    typedef struct ExpectedEvent {
        M3dsAudioEvent event;
        bool loops;
    } ExpectedEvent;
    static const ExpectedEvent events[] = {
        {M3DS_AUDIO_EVENT_UI_BUTTON, false},
        {M3DS_AUDIO_EVENT_RESEARCH_UNLOCK, false},
        {M3DS_AUDIO_EVENT_WAVE_SPAWN, false},
        {M3DS_AUDIO_EVENT_SHOOT_DUO, false},
        {M3DS_AUDIO_EVENT_ENEMY_KILL, false},
        {M3DS_AUDIO_EVENT_CONVEYOR_LOOP, true},
        {M3DS_AUDIO_EVENT_DRILL_LOOP, true},
        {M3DS_AUDIO_EVENT_BLOCK_PLACE, false},
        {M3DS_AUDIO_EVENT_BLOCK_BREAK, false},
    };
    for (size_t i = 0u; i < sizeof(events) / sizeof(events[0]); ++i) {
        m3ds_audio_mixer_stop_all(&mixer);
        M3dsAudioPlayParams params = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
        params.volume = 0.5f;
        params.looping = events[i].loops;
        uint32_t voice = m3ds_audio_event_play(&registry, events[i].event, &params);
        assert(voice != M3DS_AUDIO_INVALID_VOICE);
        int expected = m3ds_audio_event_clip(&registry, events[i].event);
        assert(expected >= 0);
        assert(expected ==
               m3ds_audio_registry_find(&registry, m3ds_audio_event_id(events[i].event)));
        assert(g_ctx.last_clip == expected);
        assert(g_ctx.last_looping == events[i].loops);
        assert(mixer.voices[g_ctx.active_channel].active);
        assert(mixer.voices[g_ctx.active_channel].clip_id == expected);
    }

    assert(m3ds_audio_event_play(&registry, M3DS_AUDIO_EVENT_COUNT, NULL) ==
           M3DS_AUDIO_INVALID_VOICE);
    assert(m3ds_audio_event_clip(&registry, M3DS_AUDIO_EVENT_COUNT) ==
           M3DS_AUDIO_INVALID_CLIP);
    assert(m3ds_audio_event_play(NULL, M3DS_AUDIO_EVENT_UI_BUTTON, NULL) ==
           M3DS_AUDIO_INVALID_VOICE);
    assert(m3ds_audio_event_clip(NULL, M3DS_AUDIO_EVENT_UI_BUTTON) ==
           M3DS_AUDIO_INVALID_CLIP);

    m3ds_audio_mixer_shutdown(&mixer);
}

static void test_event_play_without_registry(void) {
    M3dsAudioMixer mixer;
    M3dsAudioBackend backend = make_backend();
    assert(m3ds_audio_mixer_init(&mixer, &backend));

    M3dsAudioRegistry registry;
    memset(&registry, 0, sizeof(registry));
    assert(m3ds_audio_event_play(&registry, M3DS_AUDIO_EVENT_WAVE_SPAWN, NULL) ==
           M3DS_AUDIO_INVALID_VOICE);
    assert(m3ds_audio_event_clip(&registry, M3DS_AUDIO_EVENT_WAVE_SPAWN) ==
           M3DS_AUDIO_INVALID_CLIP);

    m3ds_audio_mixer_shutdown(&mixer);
}

int main(void) {
    test_event_ids_match_trigger_map();
    test_event_play_uses_registry_clip();
    test_event_play_without_registry();
    puts("audio event tests passed");
    return 0;
}
