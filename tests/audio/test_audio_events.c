#include "audio/audio_events.h"

#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

typedef struct MockCtx {
    bool uploaded[M3DS_AUDIO_MAX_CLIPS];
    int last_clip;
    int active_channel;
    bool last_looping;
    float gain_left;
    float gain_right;
    uint32_t mix_calls;
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
    ctx->gain_left = gain_left;
    ctx->gain_right = gain_right;
    return true;
}

static void mock_voice_set_mix(
    void *raw,
    uint32_t channel,
    float gain_left,
    float gain_right) {
    MockCtx *ctx = (MockCtx *)raw;
    (void)channel;
    ctx->gain_left = gain_left;
    ctx->gain_right = gain_right;
    ctx->mix_calls += 1u;
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
    backend.voice_set_mix = mock_voice_set_mix;
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

static bool approx_equal(float a, float b) {
    float diff = (a > b) ? (a - b) : (b - a);
    return diff < 0.0005f;
}

static void test_upstream_sound_id_mapping_and_spatial_math(void) {
    typedef struct ExpectedSoundId {
        M3dsAudioEvent event;
        int upstream_id;
    } ExpectedSoundId;
    static const ExpectedSoundId expected[] = {
        {M3DS_AUDIO_EVENT_UI_BUTTON, 185},
        {M3DS_AUDIO_EVENT_RESEARCH_UNLOCK, 189},
        {M3DS_AUDIO_EVENT_WAVE_SPAWN, 198},
        {M3DS_AUDIO_EVENT_SHOOT_DUO, 136},
        {M3DS_AUDIO_EVENT_ENEMY_KILL, 192},
        {M3DS_AUDIO_EVENT_CONVEYOR_LOOP, 63},
        {M3DS_AUDIO_EVENT_DRILL_LOOP, 67},
        {M3DS_AUDIO_EVENT_BLOCK_PLACE, 26},
        {M3DS_AUDIO_EVENT_BLOCK_BREAK, 11},
    };
    for (size_t i = 0u; i < sizeof(expected) / sizeof(expected[0]); ++i) {
        assert(m3ds_audio_event_upstream_sound_id(expected[i].event) ==
               expected[i].upstream_id);
        M3dsAudioEvent decoded = M3DS_AUDIO_EVENT_COUNT;
        assert(m3ds_audio_event_from_upstream_sound_id(expected[i].upstream_id, &decoded));
        assert(decoded == expected[i].event);
    }

    assert(m3ds_audio_event_upstream_sound_id(M3DS_AUDIO_EVENT_COUNT) ==
           M3DS_UPSTREAM_SOUND_UNSET);
    M3dsAudioEvent untouched = M3DS_AUDIO_EVENT_COUNT;
    assert(!m3ds_audio_event_from_upstream_sound_id(M3DS_UPSTREAM_SOUND_UNSET, &untouched));
    assert(!m3ds_audio_event_from_upstream_sound_id(0, &untouched));
    assert(!m3ds_audio_event_from_upstream_sound_id(205, &untouched));
    assert(untouched == M3DS_AUDIO_EVENT_COUNT);
    assert(m3ds_audio_event_from_upstream_sound_id(136, NULL));

    /* Arc Sound.calcPan / calcFalloff / calcVolume 1:1 checks (400x240 camera). */
    assert(approx_equal(m3ds_audio_calc_pan(0.0f, 0.0f, 400.0f), 0.0f));
    assert(approx_equal(m3ds_audio_calc_pan(-100.0f, 0.0f, 400.0f), -0.500f));
    assert(approx_equal(m3ds_audio_calc_pan(400.0f, 0.0f, 400.0f), 0.900f));
    assert(approx_equal(m3ds_audio_calc_pan(-400.0f, 0.0f, 400.0f), -0.900f));
    assert(approx_equal(m3ds_audio_calc_pan(50.0f, 0.0f, 0.0f), 0.0f));
    assert(approx_equal(m3ds_audio_calc_pan(NAN, 0.0f, 400.0f), 0.0f));

    assert(approx_equal(
        m3ds_audio_calc_falloff(0.0f, 0.0f, 0.0f, 0.0f,
                                M3DS_UPSTREAM_AUDIO_DEFAULT_FALLOFF, 0.0f),
        1.0f));
    assert(approx_equal(
        m3ds_audio_calc_falloff(30.0f, 40.0f, 0.0f, 0.0f,
                                M3DS_UPSTREAM_AUDIO_DEFAULT_FALLOFF, 50.0f),
        1.0f));
    float far_vol = m3ds_audio_calc_falloff(
        5000.0f, 5000.0f, 0.0f, 0.0f, M3DS_UPSTREAM_AUDIO_DEFAULT_FALLOFF, 0.0f);
    assert(far_vol < M3DS_UPSTREAM_AUDIO_MIN_AUDIBLE_VOLUME);
    assert(approx_equal(far_vol, 0.00032f));
    assert(approx_equal(m3ds_audio_calc_falloff(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f), 0.0f));
    assert(approx_equal(
        m3ds_audio_calc_volume(0.0f, 0.0f, 0.0f, 0.0f,
                               M3DS_UPSTREAM_AUDIO_DEFAULT_FALLOFF, 0.0f, 0.5f),
        0.5f));

    /* Upstream sound ID playback + SoundLoop-style live pan/fade updates. */
    static const int16_t duo_pcm[] = {10, 20, 30, 40};
    static const int16_t conv_pcm[] = {50, 60, 70, 80};
    static const MockFile files[] = {
        {"romfs:/audio/shoot.duo.pcm", duo_pcm, 4u},
        {"romfs:/audio/conveyor.loop.pcm", conv_pcm, 4u},
        {NULL, NULL, 0u},
    };
    static const M3dsAudioIndexEntry entries[] = {
        {"shoot.duo", "romfs:/audio/shoot.duo.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/content/Blocks.java", 3333u},
        {"conveyor.loop", "romfs:/audio/conveyor.loop.pcm", 22050u, 1u, 4u,
         "core/src/mindustry/world/blocks/distribution/Conveyor.java", 49u},
    };
    int16_t storage[16];
    M3dsAudioMixer mixer;
    M3dsAudioBackend backend = make_backend();
    assert(m3ds_audio_mixer_init(&mixer, &backend));
    M3dsAudioRegistry registry;
    assert(m3ds_audio_registry_load(
               &registry, &mixer, entries, 2u, mock_read, (void *)files, storage,
               sizeof(storage)) == M3DS_AUDIO_REGISTRY_OK);

    assert(m3ds_audio_upstream_sound_clip(&registry, 136) ==
           m3ds_audio_event_clip(&registry, M3DS_AUDIO_EVENT_SHOOT_DUO));
    assert(m3ds_audio_upstream_sound_clip(&registry, 999) == M3DS_AUDIO_INVALID_CLIP);
    assert(m3ds_audio_upstream_sound_play(&registry, 999, NULL) == M3DS_AUDIO_INVALID_VOICE);

    M3dsAudioPlayParams conv_p = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
    conv_p.looping = true;
    conv_p.volume = 0.0f;
    conv_p.pan = m3ds_audio_calc_pan(-100.0f, 0.0f, 400.0f);
    uint32_t loop_v = m3ds_audio_upstream_sound_play(&registry, 63, &conv_p);
    assert(loop_v != M3DS_AUDIO_INVALID_VOICE);
    assert(g_ctx.last_looping);
    assert(approx_equal(g_ctx.gain_left, 0.0f));
    assert(approx_equal(g_ctx.gain_right, 0.0f));

    /* Fade in like SoundLoop (baseVolume = 0.019f -> full volume over 20 updates). */
    float fade = 0.0f;
    const float base_vol = 0.019f;
    for (int step = 0; step < 20; ++step) {
        fade += 0.05f;
        if (fade > 1.0f) {
            fade = 1.0f;
        }
        assert(m3ds_audio_mixer_set_voice_mix(
            &mixer, loop_v, fade * base_vol,
            m3ds_audio_calc_pan(-100.0f, 0.0f, 400.0f)));
    }
    assert(approx_equal(g_ctx.gain_left, base_vol));
    assert(approx_equal(g_ctx.gain_right, base_vol * 0.5f));
    assert(g_ctx.mix_calls == 20u);

    m3ds_audio_mixer_shutdown(&mixer);
}

int main(void) {
    test_event_ids_match_trigger_map();
    test_event_play_uses_registry_clip();
    test_event_play_without_registry();
    test_upstream_sound_id_mapping_and_spatial_math();
    puts("audio event tests passed");
    return 0;
}
