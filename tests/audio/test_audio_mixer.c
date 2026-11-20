#include "audio/audio_mixer.h"

#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

typedef struct MockVoiceState {
    bool active;
    bool paused;
    int clip_id;
    float gain_left;
    float gain_right;
    bool looping;
} MockVoiceState;

typedef struct MockAudioCtx {
    M3dsAudioBackendState init_state;
    uint32_t init_status_code;
    bool init_called;
    bool shutdown_called;
    int shutdown_count;
    float master_volume;
    int master_vol_calls;
    bool uploaded[M3DS_AUDIO_MAX_CLIPS];
    uint32_t upload_calls;
    uint32_t unload_calls;
    uint32_t voice_start_calls;
    uint32_t voice_stop_calls;
    uint32_t voice_mix_calls;
    uint32_t voice_pause_calls;
    MockVoiceState voices[M3DS_AUDIO_MAX_VOICES];
} MockAudioCtx;

static M3dsAudioBackendState mock_init(void *raw, uint32_t *out_rc) {
    MockAudioCtx *ctx = (MockAudioCtx *)raw;
    ctx->init_called = true;
    if (out_rc) {
        *out_rc = ctx->init_status_code;
    }
    return ctx->init_state;
}

static void mock_shutdown(void *raw) {
    MockAudioCtx *ctx = (MockAudioCtx *)raw;
    ctx->shutdown_called = true;
    ctx->shutdown_count += 1;
}

static void mock_set_master_vol(void *raw, float volume) {
    MockAudioCtx *ctx = (MockAudioCtx *)raw;
    ctx->master_volume = volume;
    ctx->master_vol_calls += 1;
}

static bool mock_upload_clip(
    void *raw,
    int clip_id,
    const M3dsAudioClipDesc *desc) {
    MockAudioCtx *ctx = (MockAudioCtx *)raw;
    (void)desc;
    ctx->upload_calls += 1u;
    ctx->uploaded[clip_id] = true;
    return true;
}

static void mock_unload_clip(void *raw, int clip_id) {
    MockAudioCtx *ctx = (MockAudioCtx *)raw;
    ctx->unload_calls += 1u;
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
    MockAudioCtx *ctx = (MockAudioCtx *)raw;
    (void)desc;
    ctx->voice_start_calls += 1u;
    ctx->voices[channel].active = true;
    ctx->voices[channel].paused = false;
    ctx->voices[channel].clip_id = clip_id;
    ctx->voices[channel].gain_left = gain_left;
    ctx->voices[channel].gain_right = gain_right;
    ctx->voices[channel].looping = looping;
    return true;
}

static void mock_voice_set_mix(
    void *raw,
    uint32_t channel,
    float gain_left,
    float gain_right) {
    MockAudioCtx *ctx = (MockAudioCtx *)raw;
    ctx->voice_mix_calls += 1u;
    ctx->voices[channel].gain_left = gain_left;
    ctx->voices[channel].gain_right = gain_right;
}

static void mock_voice_set_paused(void *raw, uint32_t channel, bool paused) {
    MockAudioCtx *ctx = (MockAudioCtx *)raw;
    ctx->voice_pause_calls += 1u;
    ctx->voices[channel].paused = paused;
}

static void mock_voice_stop(void *raw, uint32_t channel) {
    MockAudioCtx *ctx = (MockAudioCtx *)raw;
    ctx->voice_stop_calls += 1u;
    ctx->voices[channel].active = false;
    ctx->voices[channel].paused = false;
}

static bool mock_voice_is_active(void *raw, uint32_t channel) {
    const MockAudioCtx *ctx = (const MockAudioCtx *)raw;
    return ctx->voices[channel].active;
}

static M3dsAudioBackend make_mock_backend(
    MockAudioCtx *ctx,
    M3dsAudioBackendState state,
    uint32_t status_code) {
    memset(ctx, 0, sizeof(*ctx));
    ctx->init_state = state;
    ctx->init_status_code = status_code;
    ctx->master_volume = -1.0f;

    M3dsAudioBackend backend;
    memset(&backend, 0, sizeof(backend));
    backend.ctx = ctx;
    backend.name = "mock";
    backend.init = mock_init;
    backend.shutdown = mock_shutdown;
    backend.set_master_volume = mock_set_master_vol;
    backend.upload_clip = mock_upload_clip;
    backend.unload_clip = mock_unload_clip;
    backend.voice_start = mock_voice_start;
    backend.voice_set_mix = mock_voice_set_mix;
    backend.voice_set_paused = mock_voice_set_paused;
    backend.voice_stop = mock_voice_stop;
    backend.voice_is_active = mock_voice_is_active;
    return backend;
}

static bool float_near(float a, float b) {
    return fabsf(a - b) < 0.001f;
}

static void test_disabled_and_fallback_no_dsp(void) {
    M3dsAudioMixer mixer;
    assert(!m3ds_audio_mixer_init(NULL, NULL));
    assert(m3ds_audio_mixer_init(&mixer, NULL));
    assert(m3ds_audio_mixer_state(&mixer) == M3DS_AUDIO_STATE_DISABLED);
    assert(strcmp(m3ds_audio_state_name(m3ds_audio_mixer_state(&mixer)), "disabled") == 0);

    int16_t pcm[64];
    m3ds_audio_synth_tone_pcm16(pcm, 64u, 22050u, 440u, 8000u);
    M3dsAudioClipDesc desc = {pcm, 64u, 22050u, 1u};
    int clip = m3ds_audio_mixer_register_clip(&mixer, &desc);
    assert(clip >= 0);
    assert(m3ds_audio_mixer_play(&mixer, clip, NULL) == M3DS_AUDIO_INVALID_VOICE);

    M3dsAudioStats stats;
    m3ds_audio_mixer_get_stats(&mixer, &stats);
    assert(stats.clip_bytes_uploaded == 0u);
    assert(stats.total_dropped == 1u);
    m3ds_audio_mixer_shutdown(&mixer);
    assert(m3ds_audio_mixer_state(&mixer) == M3DS_AUDIO_STATE_UNINITIALIZED);

    MockAudioCtx ctx;
    M3dsAudioBackend backend = make_mock_backend(
        &ctx, M3DS_AUDIO_STATE_FALLBACK_NO_DSP, 0xd8e06406u);
    assert(m3ds_audio_mixer_init(&mixer, &backend));
    assert(ctx.init_called);
    assert(ctx.master_vol_calls == 0);
    assert(m3ds_audio_mixer_state(&mixer) == M3DS_AUDIO_STATE_FALLBACK_NO_DSP);
    assert(m3ds_audio_mixer_status_code(&mixer) == 0xd8e06406u);
    assert(strcmp(m3ds_audio_state_name(m3ds_audio_mixer_state(&mixer)), "fallback_no_dsp") == 0);

    int fallback_clip = m3ds_audio_mixer_register_clip(&mixer, &desc);
    assert(fallback_clip == 0);
    assert(ctx.upload_calls == 0u);
    assert(ctx.voice_start_calls == 0u);

    uint32_t dropped_voice = m3ds_audio_mixer_play(&mixer, fallback_clip, NULL);
    assert(dropped_voice == M3DS_AUDIO_INVALID_VOICE);
    assert(ctx.voice_start_calls == 0u);

    char diag[160];
    size_t len = m3ds_audio_mixer_format_diag(&mixer, diag, sizeof(diag));
    assert(len > 0u);
    assert(strcmp(
        diag,
        "backend=mock state=fallback_no_dsp rc=0xd8e06406 rate=22050 voices=0/8 clips=1") == 0);

    m3ds_audio_mixer_get_stats(&mixer, &stats);
    assert(stats.registered_clips == 1u);
    assert(stats.clip_bytes_uploaded == 0u);
    assert(stats.total_dropped == 1u);

    m3ds_audio_mixer_shutdown(&mixer);
    m3ds_audio_mixer_shutdown(&mixer);
    assert(ctx.shutdown_count == 1);
    assert(ctx.unload_calls == 0u);
    assert(m3ds_audio_mixer_state(&mixer) == M3DS_AUDIO_STATE_UNINITIALIZED);
}

static void test_clip_validation_and_lifecycle(void) {
    MockAudioCtx ctx;
    M3dsAudioBackend backend = make_mock_backend(&ctx, M3DS_AUDIO_STATE_READY, 0u);
    M3dsAudioMixer mixer;
    assert(m3ds_audio_mixer_init(&mixer, &backend));

    int16_t mono[32];
    int16_t stereo[64];
    m3ds_audio_synth_tone_pcm16(mono, 32u, 22050u, 440u, 4000u);
    m3ds_audio_synth_tone_pcm16(stereo, 64u, 22050u, 440u, 4000u);

    M3dsAudioClipDesc bad = {NULL, 32u, 22050u, 1u};
    assert(m3ds_audio_mixer_register_clip(&mixer, &bad) == M3DS_AUDIO_INVALID_CLIP);
    bad = (M3dsAudioClipDesc){mono, 0u, 22050u, 1u};
    assert(m3ds_audio_mixer_register_clip(&mixer, &bad) == M3DS_AUDIO_INVALID_CLIP);
    bad = (M3dsAudioClipDesc){mono, M3DS_AUDIO_MAX_CLIP_SAMPLES + 1u, 22050u, 1u};
    assert(m3ds_audio_mixer_register_clip(&mixer, &bad) == M3DS_AUDIO_INVALID_CLIP);
    bad = (M3dsAudioClipDesc){mono, 32u, 3999u, 1u};
    assert(m3ds_audio_mixer_register_clip(&mixer, &bad) == M3DS_AUDIO_INVALID_CLIP);
    bad = (M3dsAudioClipDesc){mono, 32u, 48001u, 1u};
    assert(m3ds_audio_mixer_register_clip(&mixer, &bad) == M3DS_AUDIO_INVALID_CLIP);
    bad = (M3dsAudioClipDesc){mono, 32u, 22050u, 0u};
    assert(m3ds_audio_mixer_register_clip(&mixer, &bad) == M3DS_AUDIO_INVALID_CLIP);
    bad = (M3dsAudioClipDesc){mono, 32u, 22050u, 3u};
    assert(m3ds_audio_mixer_register_clip(&mixer, &bad) == M3DS_AUDIO_INVALID_CLIP);

    M3dsAudioClipDesc good_mono = {mono, 32u, 22050u, 1u};
    M3dsAudioClipDesc good_stereo = {stereo, 32u, 22050u, 2u};
    int c_mono = m3ds_audio_mixer_register_clip(&mixer, &good_mono);
    int c_stereo = m3ds_audio_mixer_register_clip(&mixer, &good_stereo);
    assert(c_mono == 0);
    assert(c_stereo == 1);
    assert(ctx.uploaded[0] && ctx.uploaded[1]);

    uint32_t v = m3ds_audio_mixer_play(&mixer, c_stereo, NULL);
    assert(v != M3DS_AUDIO_INVALID_VOICE);
    assert(ctx.voices[0].active);

    M3dsAudioStats stats;
    m3ds_audio_mixer_get_stats(&mixer, &stats);
    assert(stats.clip_bytes_uploaded == (32u * 1u + 32u * 2u) * 2u);

    m3ds_audio_mixer_unregister_clip(&mixer, c_stereo);
    assert(!ctx.uploaded[1]);
    assert(!ctx.voices[0].active);
    m3ds_audio_mixer_get_stats(&mixer, &stats);
    assert(stats.registered_clips == 1u);
    assert(stats.active_voices == 0u);
    assert(stats.clip_bytes_uploaded == 32u * 2u);

    for (uint32_t i = 1u; i < M3DS_AUDIO_MAX_CLIPS; ++i) {
        assert(m3ds_audio_mixer_register_clip(&mixer, &good_mono) >= 0);
    }
    assert(m3ds_audio_mixer_register_clip(&mixer, &good_mono) == M3DS_AUDIO_INVALID_CLIP);

    m3ds_audio_mixer_shutdown(&mixer);
    assert(ctx.unload_calls == M3DS_AUDIO_MAX_CLIPS + 1u);
}

static void test_pan_bus_mute_and_pause(void) {
    MockAudioCtx ctx;
    M3dsAudioBackend backend = make_mock_backend(&ctx, M3DS_AUDIO_STATE_READY, 0u);
    M3dsAudioMixer mixer;
    assert(m3ds_audio_mixer_init(&mixer, &backend));
    assert(float_near(ctx.master_volume, 1.0f));

    int16_t pcm[32];
    m3ds_audio_synth_tone_pcm16(pcm, 32u, 22050u, 600u, 10000u);
    M3dsAudioClipDesc desc = {pcm, 32u, 22050u, 1u};
    int clip = m3ds_audio_mixer_register_clip(&mixer, &desc);
    assert(clip >= 0);

    M3dsAudioPlayParams p_left = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
    p_left.volume = 0.8f;
    p_left.pan = -2.0f; /* Clamps to -1.0f */
    uint32_t v0 = m3ds_audio_mixer_play(&mixer, clip, &p_left);
    assert(v0 != M3DS_AUDIO_INVALID_VOICE);
    assert(float_near(ctx.voices[0].gain_left, 0.8f));
    assert(float_near(ctx.voices[0].gain_right, 0.0f));

    M3dsAudioPlayParams p_right = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
    p_right.volume = 1.0f;
    p_right.pan = 0.5f;
    uint32_t v1 = m3ds_audio_mixer_play(&mixer, clip, &p_right);
    assert(v1 != M3DS_AUDIO_INVALID_VOICE);
    assert(float_near(ctx.voices[1].gain_left, 0.5f));
    assert(float_near(ctx.voices[1].gain_right, 1.0f));

    M3dsAudioPlayParams p_music = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_MUSIC);
    assert(p_music.looping);
    assert(p_music.priority == 200u);
    uint32_t v2 = m3ds_audio_mixer_play(&mixer, clip, &p_music);
    assert(v2 != M3DS_AUDIO_INVALID_VOICE);
    assert(float_near(ctx.voices[2].gain_left, 0.7f));
    assert(float_near(ctx.voices[2].gain_right, 0.7f));
    assert(ctx.voices[2].looping);

    m3ds_audio_mixer_set_bus_volume(&mixer, M3DS_AUDIO_BUS_SFX, 0.5f);
    assert(float_near(ctx.voices[0].gain_left, 0.4f));
    assert(float_near(ctx.voices[1].gain_left, 0.25f));
    assert(float_near(ctx.voices[1].gain_right, 0.5f));
    assert(float_near(ctx.voices[2].gain_left, 0.7f));

    m3ds_audio_mixer_set_master_volume(&mixer, 0.6f);
    assert(float_near(m3ds_audio_mixer_master_volume(&mixer), 0.6f));
    assert(float_near(ctx.master_volume, 0.6f));

    m3ds_audio_mixer_set_muted(&mixer, true);
    assert(m3ds_audio_mixer_is_muted(&mixer));
    assert(float_near(ctx.master_volume, 0.0f));
    m3ds_audio_mixer_set_master_volume(&mixer, 0.4f);
    assert(float_near(ctx.master_volume, 0.0f));
    m3ds_audio_mixer_set_muted(&mixer, false);
    assert(float_near(ctx.master_volume, 0.4f));

    m3ds_audio_mixer_set_paused(&mixer, true);
    assert(m3ds_audio_mixer_is_paused(&mixer));
    assert(ctx.voices[0].paused && ctx.voices[1].paused && ctx.voices[2].paused);

    /* Newly started voice while paused should immediately pause on the backend. */
    uint32_t v3 = m3ds_audio_mixer_play(&mixer, clip, NULL);
    assert(v3 != M3DS_AUDIO_INVALID_VOICE);
    assert(ctx.voices[3].paused);

    m3ds_audio_mixer_set_paused(&mixer, false);
    assert(!ctx.voices[0].paused && !ctx.voices[3].paused);

    m3ds_audio_mixer_shutdown(&mixer);
}

static void test_voice_stealing_and_update(void) {
    MockAudioCtx ctx;
    M3dsAudioBackend backend = make_mock_backend(&ctx, M3DS_AUDIO_STATE_READY, 0u);
    M3dsAudioMixer mixer;
    assert(m3ds_audio_mixer_init(&mixer, &backend));

    int16_t pcm[32];
    m3ds_audio_synth_tone_pcm16(pcm, 32u, 22050u, 880u, 9000u);
    M3dsAudioClipDesc desc = {pcm, 32u, 22050u, 1u};
    int clip = m3ds_audio_mixer_register_clip(&mixer, &desc);

    uint32_t handles[M3DS_AUDIO_MAX_VOICES];
    for (uint32_t i = 0u; i < M3DS_AUDIO_MAX_VOICES; ++i) {
        M3dsAudioPlayParams p = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
        p.priority = (i == 2u || i == 5u) ? 20u : 150u;
        handles[i] = m3ds_audio_mixer_play(&mixer, clip, &p);
        assert(handles[i] != M3DS_AUDIO_INVALID_VOICE);
    }

    /* Strictly lower incoming priority gets dropped without disturbing pool. */
    M3dsAudioPlayParams low_p = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
    low_p.priority = 10u;
    assert(m3ds_audio_mixer_play(&mixer, clip, &low_p) == M3DS_AUDIO_INVALID_VOICE);

    /* Incoming priority 50 steals oldest lowest-priority voice (channel 2). */
    M3dsAudioPlayParams mid_p = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
    mid_p.priority = 50u;
    mid_p.bus = M3DS_AUDIO_BUS_MUSIC;
    uint32_t stolen_replacement = m3ds_audio_mixer_play(&mixer, clip, &mid_p);
    assert(stolen_replacement != M3DS_AUDIO_INVALID_VOICE);
    assert(ctx.voices[2].active);
    assert(float_near(ctx.voices[2].gain_left, 0.7f));

    /* Stopping stale handle must not kill replacement voice on channel 2. */
    m3ds_audio_mixer_stop(&mixer, handles[2]);
    assert(ctx.voices[2].active);

    /* stop_bus(SFX) leaves channel 2 (MUSIC) playing. */
    m3ds_audio_mixer_stop_bus(&mixer, M3DS_AUDIO_BUS_SFX);
    assert(ctx.voices[2].active);
    for (uint32_t i = 0u; i < M3DS_AUDIO_MAX_VOICES; ++i) {
        if (i != 2u) {
            assert(!ctx.voices[i].active);
        }
    }

    /* Simulated natural completion harvested by m3ds_audio_mixer_update(). */
    ctx.voices[2].active = false;
    m3ds_audio_mixer_update(&mixer);

    M3dsAudioStats stats;
    m3ds_audio_mixer_get_stats(&mixer, &stats);
    assert(stats.active_voices == 0u);
    assert(stats.peak_voices == M3DS_AUDIO_MAX_VOICES);
    assert(stats.total_stolen == 1u);
    assert(stats.total_dropped == 1u);
    assert(stats.total_completed == 1u);

    m3ds_audio_mixer_shutdown(&mixer);
}

static void test_synth_tone_determinism_and_envelope(void) {
    int16_t a[1764];
    int16_t b[1764];
    m3ds_audio_synth_tone_pcm16(a, 1764u, 22050u, 880u, 9000u);
    m3ds_audio_synth_tone_pcm16(b, 1764u, 22050u, 880u, 9000u);
    assert(memcmp(a, b, sizeof(a)) == 0);
    assert(a[0] == 0);
    assert(a[1763] == 0);

    int max_abs = 0;
    for (size_t i = 0u; i < 1764u; ++i) {
        int abs_v = a[i] < 0 ? -a[i] : a[i];
        if (abs_v > max_abs) {
            max_abs = abs_v;
        }
        assert(abs_v <= 9000);
    }
    assert(max_abs > 5000);
}

int main(void) {
    test_disabled_and_fallback_no_dsp();
    test_clip_validation_and_lifecycle();
    test_pan_bus_mute_and_pause();
    test_voice_stealing_and_update();
    test_synth_tone_determinism_and_envelope();
    printf("test_audio_mixer: all assertions passed.\n");
    return 0;
}
