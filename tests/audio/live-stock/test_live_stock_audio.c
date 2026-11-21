#include "m3ds_live_audio.h"
#include "ndsp_backend.h"
#include "sound-index.h"

#include <assert.h>
#include <stdio.h>

static const size_t budget = 640u * 1024u;
static bool fallback;
static size_t resident_bytes;
static unsigned uploads;
static unsigned evictions;
static float left, right, rate;
static bool paused;

void svcOutputDebugString(const char *text, int length) {
    printf("%.*s", length, text);
}

static M3dsAudioBackendState init(void *ctx, uint32_t *result) {
    (void)ctx;
    *result = 0;
    return fallback ? M3DS_AUDIO_STATE_FALLBACK_NO_DSP : M3DS_AUDIO_STATE_READY;
}

static bool upload(void *opaque, int clip, const M3dsAudioClipDesc *desc) {
    M3dsNdspContext *ctx = opaque;
    assert(ctx->uploaded[clip] == 0 && desc->samples != NULL);
    size_t bytes = desc->frame_count * desc->channels * sizeof(*desc->samples);
    ctx->uploaded[clip] = bytes;
    resident_bytes += bytes;
    assert(resident_bytes <= budget);
    uploads++;
    return true;
}

static void unload(void *opaque, int clip) {
    M3dsNdspContext *ctx = opaque;
    for (size_t i = 0; i < M3DS_AUDIO_MAX_VOICES; i++) assert(ctx->playing[i] != clip);
    assert(ctx->uploaded[clip] > 0);
    resident_bytes -= ctx->uploaded[clip];
    ctx->uploaded[clip] = 0;
    evictions++;
}

static bool start(void *opaque, uint32_t channel, int clip, const M3dsAudioClipDesc *desc,
                  float gain_left, float gain_right, bool loop) {
    M3dsNdspContext *ctx = opaque;
    (void)desc;
    (void)loop;
    assert(ctx->playing[channel] == -1 && ctx->uploaded[clip] > 0);
    ctx->playing[channel] = clip;
    left = gain_left;
    right = gain_right;
    return true;
}

static void stop(void *opaque, uint32_t channel) {
    M3dsNdspContext *ctx = opaque;
    ctx->playing[channel] = -1;
}

static bool active(void *opaque, uint32_t channel) {
    M3dsNdspContext *ctx = opaque;
    return ctx->playing[channel] >= 0;
}

static void mix(void *opaque, uint32_t channel, float gain_left, float gain_right) {
    (void)opaque;
    (void)channel;
    left = gain_left;
    right = gain_right;
}

static void set_rate(void *opaque, uint32_t channel, float playback_rate) {
    (void)opaque;
    (void)channel;
    rate = playback_rate;
}

static void set_paused(void *opaque, uint32_t channel, bool voice_paused) {
    (void)opaque;
    (void)channel;
    paused = voice_paused;
}

void m3ds_ndsp_backend_init(M3dsAudioBackend *backend, M3dsNdspContext *ctx) {
    *ctx = (M3dsNdspContext){0};
    for (size_t i = 0; i < M3DS_AUDIO_MAX_VOICES; i++) ctx->playing[i] = -1;
    *backend = (M3dsAudioBackend){
        .ctx = ctx, .init = init, .upload_clip = upload, .unload_clip = unload,
        .voice_start = start, .voice_stop = stop, .voice_is_active = active,
        .voice_set_mix = mix, .voice_set_rate = set_rate, .voice_set_paused = set_paused,
    };
}

int main(void) {
    assert(m3ds_live_audio_open() == 1 && uploads == 0);
    int32_t gamma = m3ds_live_audio_play(117, 0.4f, 1.5f, 0.5f, 1);
    assert(gamma && m3ds_live_audio_is_active(gamma) && left < right && rate == 33075.0f);
    m3ds_live_audio_set_mix(gamma, -0.5f, 0.6f);
    m3ds_live_audio_set_pitch(gamma, 0.5f);
    assert(left > right && rate == 11025.0f);
    m3ds_live_audio_set_paused(gamma, 1);
    assert(paused);
    m3ds_live_audio_set_paused(gamma, 0);
    assert(!paused);

    int32_t replay = m3ds_live_audio_play(117, 0.4f, 1.0f, 0.0f, 0);
    assert(replay && replay != gamma && uploads == 1);
    m3ds_live_audio_stop(replay);
    assert(!m3ds_live_audio_is_active(replay));
    assert(m3ds_live_audio_play(-1, 1, 1, 0, 0) == 0);
    assert(m3ds_live_audio_play(M3DS_STOCK_SOUND_COUNT, 1, 1, 0, 0) == 0);

    for (unsigned id = 0; id < M3DS_STOCK_SOUND_COUNT; id++) {
        if (id == 117) continue;
        int32_t voice = m3ds_live_audio_play((int32_t)id, 0.5f, 1.0f, 0.0f, 0);
        assert((voice != 0) == (m3ds_sound_to_clip[id] >= 0));
        if (voice) m3ds_live_audio_stop(voice);
        assert(m3ds_live_audio_is_active(gamma));
    }
    assert(uploads == M3DS_AUDIO_CLIP_COUNT && evictions > 0);
    m3ds_live_audio_reset();
    assert(!m3ds_live_audio_is_active(gamma));
    m3ds_live_audio_close();
    assert(resident_bytes == 0 && uploads == evictions);

    unsigned ready_uploads = uploads;
    fallback = true;
    assert(m3ds_live_audio_open() == 1);
    assert(m3ds_live_audio_play(117, 0.4f, 1.0f, 0.5f, 0) == 0);
    assert(m3ds_live_audio_play(185, 0.5f, 1.0f, 0.0f, 0) == 0);
    m3ds_live_audio_close();
    assert(resident_bytes == 0 && uploads == ready_uploads);
    printf("LIVE_STOCK_AUDIO readyClips=%u evictions=%u fallback=ok\n", ready_uploads, evictions);
    return 0;
}
