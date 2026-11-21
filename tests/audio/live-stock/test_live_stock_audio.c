#include "m3ds_live_audio.h"
#include "ndsp_backend.h"
#include "ndsp_stream_backend.h"
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
static M3dsNdspStreamContext *lanes[4];
static uint64_t streamed_frames;

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

static bool configure_stream(void *opaque, uint32_t sample_rate, uint8_t channels,
                             float gain_left, float gain_right) {
    M3dsNdspStreamContext *ctx = opaque;
    *ctx = (M3dsNdspStreamContext){.rate = sample_rate, .channels = channels,
                                 .left = gain_left, .right = gain_right};
    return true;
}

static bool submit_chunk(void *opaque, uint32_t slot, const int16_t *samples, uint32_t frames) {
    M3dsNdspStreamContext *ctx = opaque;
    assert(samples && frames && frames <= M3DS_AUDIO_STREAM_CHUNK_FRAMES && !ctx->pending[slot]);
    ctx->pending[slot] = true;
    streamed_frames += frames;
    return true;
}

static bool complete_chunk(void *opaque, uint32_t slot) {
    M3dsNdspStreamContext *ctx = opaque;
    if (!ctx->advance || ctx->paused || slot != ctx->next_slot) return false;
    assert(ctx->pending[slot]);
    ctx->pending[slot] = false;
    ctx->advance = false;
    ctx->next_slot = (slot + 1u) % M3DS_AUDIO_STREAM_BUFFER_COUNT;
    return true;
}

static void stream_mix(void *opaque, float gain_left, float gain_right) {
    M3dsNdspStreamContext *ctx = opaque;
    ctx->left = gain_left;
    ctx->right = gain_right;
}

static void stream_rate(void *opaque, float sample_rate) {
    ((M3dsNdspStreamContext *)opaque)->rate = sample_rate;
}

static void stream_pause(void *opaque, bool value) {
    ((M3dsNdspStreamContext *)opaque)->paused = value;
}

static void stream_stop(void *opaque) {
    *(M3dsNdspStreamContext *)opaque = (M3dsNdspStreamContext){0};
}

void m3ds_ndsp_stream_backend_init_on_channel(
    M3dsAudioStreamBackend *backend, M3dsNdspStreamContext *ctx,
    const M3dsNdspContext *ndsp_ctx, uint32_t channel) {
    const uint32_t first = M3DS_AUDIO_STREAM_NDSP_CHANNEL + 1u;
    assert(ndsp_ctx && channel >= first && channel - first < sizeof(lanes) / sizeof(lanes[0]));
    *ctx = (M3dsNdspStreamContext){0};
    lanes[channel - first] = ctx;
    *backend = (M3dsAudioStreamBackend){
        .ctx = ctx, .init = init, .configure = configure_stream, .buffer_submit = submit_chunk,
        .buffer_is_free = complete_chunk, .set_mix = stream_mix, .set_rate = stream_rate,
        .set_paused = stream_pause, .stop = stream_stop,
    };
}

static void tick_streams(void) {
    for (size_t i = 0; i < sizeof(lanes) / sizeof(lanes[0]); i++) lanes[i]->advance = true;
    m3ds_live_audio_update();
}

static void verify_stream(unsigned id, const M3dsAudioIndexEntry *entry) {
    uint64_t before = streamed_frames;
    int32_t voice = m3ds_live_audio_play((int32_t)id, 0.5f, 1.25f, 0.25f, 0);
    assert(voice > 0 && m3ds_live_audio_is_active(voice));
    assert(lanes[0]->channels == entry->channels && lanes[0]->rate == entry->rate * 1.25f);
    assert(lanes[0]->left == 0.375f && lanes[0]->right == 0.5f);
    assert(streamed_frames - before == 2u * M3DS_AUDIO_STREAM_CHUNK_FRAMES);
    for (unsigned i = 0; i < entry->frames / M3DS_AUDIO_STREAM_CHUNK_FRAMES + 3u; i++) tick_streams();
    assert(!m3ds_live_audio_is_active(voice));
    assert(streamed_frames - before == entry->frames);
}

static void verify_parallel_streams(void) {
    const int ids[] = {6, 7, 35, 54};
    int32_t voices[4];
    uint64_t before = streamed_frames;
    uint64_t file_frames = 0;
    unsigned max_frames = 0;
    for (size_t i = 0; i < sizeof(ids) / sizeof(ids[0]); i++) {
        unsigned frames = m3ds_audio_index[ids[i]].frames;
        if (frames > max_frames) max_frames = frames;
        file_frames += frames;
        voices[i] = m3ds_live_audio_play(ids[i], 0.5f, 1.f, 0.f, 1);
        assert(voices[i] > 0);
        for (size_t j = 0; j < i; j++) assert(voices[i] != voices[j]);
    }
    assert(m3ds_live_audio_play(59, 0.5f, 1.f, 0.f, 0) == 0);
    m3ds_live_audio_set_mix(voices[0], -0.5f, 0.5f);
    m3ds_live_audio_set_pitch(voices[0], 0.5f);
    assert(lanes[0]->left == 0.5f && lanes[0]->right == 0.25f && lanes[0]->rate == 11025.0f);
    m3ds_live_audio_set_paused(voices[0], 1);
    assert(lanes[0]->paused);
    m3ds_live_audio_set_paused(voices[0], 0);
    for (unsigned i = 0; i < max_frames / M3DS_AUDIO_STREAM_CHUNK_FRAMES + 3u; i++) tick_streams();
    assert(streamed_frames - before > file_frames);
    m3ds_live_audio_stop(voices[0]);
    assert(!m3ds_live_audio_is_active(voices[0]));
    int32_t replacement = m3ds_live_audio_play(59, 0.5f, 1.f, 0.f, 1);
    assert(replacement > 0 && replacement != voices[0]);
    m3ds_live_audio_stop(voices[0]);
    assert(m3ds_live_audio_is_active(replacement));
    for (size_t i = 1; i < sizeof(voices) / sizeof(voices[0]); i++) {
        assert(m3ds_live_audio_is_active(voices[i]));
    }
    m3ds_live_audio_reset();
    assert(!m3ds_live_audio_is_active(replacement));
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

    unsigned long_count = 0;
    for (unsigned id = 0; id < M3DS_STOCK_SOUND_COUNT; id++) {
        if (id == 117) continue;
        const M3dsAudioIndexEntry *entry = &m3ds_audio_index[id];
        if (entry->frames > M3DS_AUDIO_MAX_CLIP_SAMPLES) {
            verify_stream(id, entry);
            long_count++;
        } else {
            int32_t voice = m3ds_live_audio_play((int32_t)id, 0.5f, 1.0f, 0.0f, 0);
            assert(voice != 0);
            m3ds_live_audio_stop(voice);
        }
        assert(m3ds_live_audio_is_active(gamma));
    }
    assert(uploads == M3DS_STOCK_SHORT_COUNT && long_count == 11 && evictions > 0);
    verify_parallel_streams();
    m3ds_live_audio_reset();
    assert(!m3ds_live_audio_is_active(gamma));
    m3ds_live_audio_close();
    assert(resident_bytes == 0 && uploads == evictions);

    unsigned ready_uploads = uploads;
    fallback = true;
    assert(m3ds_live_audio_open() == 1);
    assert(m3ds_live_audio_play(117, 0.4f, 1.0f, 0.5f, 0) == 0);
    assert(m3ds_live_audio_play(185, 0.5f, 1.0f, 0.0f, 0) == 0);
    uint64_t ready_frames = streamed_frames;
    assert(m3ds_live_audio_play(200, 0.5f, 1.0f, 0.25f, 1) == 0);
    assert(streamed_frames == ready_frames);
    m3ds_live_audio_close();
    assert(resident_bytes == 0 && uploads == ready_uploads);
    printf("LIVE_STOCK_AUDIO readyClips=%u streamed=%u evictions=%u fallback=ok\n",
           ready_uploads, long_count, evictions);
    return 0;
}
