#include "ndsp_stream_backend.h"

#include <string.h>

static void m3ds_ndsp_stream_set_mix_raw(
    int channel,
    float gain_left,
    float gain_right) {
    float mix[12];
    memset(mix, 0, sizeof(mix));
    mix[0] = gain_left;
    mix[1] = gain_right;
    ndspChnSetMix(channel, mix);
}

static void m3ds_ndsp_stream_free_bufs(M3dsNdspStreamContext *ctx) {
    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        if (ctx->linear_bufs[i]) {
            linearFree(ctx->linear_bufs[i]);
            ctx->linear_bufs[i] = NULL;
        }
    }
}

static M3dsAudioBackendState m3ds_ndsp_stream_init_cb(
    void *raw_ctx,
    uint32_t *out_status_code) {
    M3dsNdspStreamContext *ctx = (M3dsNdspStreamContext *)raw_ctx;
    if (!ctx) {
        if (out_status_code) {
            *out_status_code = 0u;
        }
        return M3DS_AUDIO_STATE_DISABLED;
    }

    const M3dsNdspContext *ndsp_ctx = ctx->ndsp_ctx;
    memset(ctx, 0, sizeof(*ctx));
    ctx->ndsp_ctx = ndsp_ctx;
    ctx->channel = M3DS_AUDIO_STREAM_NDSP_CHANNEL;

    if (!ndsp_ctx || !ndsp_ctx->ndsp_ready) {
        if (out_status_code) {
            *out_status_code = ndsp_ctx ? (uint32_t)ndsp_ctx->init_result : 0u;
        }
        return M3DS_AUDIO_STATE_FALLBACK_NO_DSP;
    }

    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        ctx->linear_bufs[i] = (int16_t *)linearAlloc(M3DS_AUDIO_STREAM_MAX_CHUNK_BYTES);
        if (!ctx->linear_bufs[i]) {
            m3ds_ndsp_stream_free_bufs(ctx);
            if (out_status_code) {
                *out_status_code = 0u;
            }
            return M3DS_AUDIO_STATE_DISABLED;
        }
        ctx->wavebufs[i].status = NDSP_WBUF_FREE;
    }

    ctx->ready = true;
    if (out_status_code) {
        *out_status_code = 0u;
    }
    return M3DS_AUDIO_STATE_READY;
}

static void m3ds_ndsp_stream_shutdown_cb(void *raw_ctx) {
    M3dsNdspStreamContext *ctx = (M3dsNdspStreamContext *)raw_ctx;
    if (!ctx || !ctx->ready) {
        return;
    }

    /* Stop the dedicated stream channel before freeing linear ping-pong DMA buffers. */
    ndspChnWaveBufClear((int)ctx->channel);
    ndspChnReset((int)ctx->channel);
    memset(ctx->wavebufs, 0, sizeof(ctx->wavebufs));
    m3ds_ndsp_stream_free_bufs(ctx);
    ctx->ready = false;
}

static bool m3ds_ndsp_stream_configure_cb(
    void *raw_ctx,
    uint32_t sample_rate,
    uint8_t channels,
    float gain_left,
    float gain_right) {
    M3dsNdspStreamContext *ctx = (M3dsNdspStreamContext *)raw_ctx;
    if (!ctx || !ctx->ready || !ctx->ndsp_ctx || !ctx->ndsp_ctx->ndsp_ready ||
        (channels != 1u && channels != M3DS_AUDIO_STREAM_MAX_CHANNELS)) {
        return false;
    }

    int ch = (int)ctx->channel;
    ndspChnWaveBufClear(ch);
    ndspChnReset(ch);
    ndspChnInitParams(ch);
    ndspChnSetInterp(ch, NDSP_INTERP_LINEAR);
    ndspChnSetRate(ch, (float)sample_rate);
    ndspChnSetFormat(
        ch,
        channels == 2u ? NDSP_FORMAT_STEREO_PCM16 : NDSP_FORMAT_MONO_PCM16);
    m3ds_ndsp_stream_set_mix_raw(ch, gain_left, gain_right);

    memset(ctx->wavebufs, 0, sizeof(ctx->wavebufs));
    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        ctx->wavebufs[i].status = NDSP_WBUF_FREE;
    }
    ctx->channels = channels;
    return true;
}

static bool m3ds_ndsp_stream_buffer_is_free_cb(void *raw_ctx, uint32_t slot) {
    const M3dsNdspStreamContext *ctx = (const M3dsNdspStreamContext *)raw_ctx;
    if (!ctx || !ctx->ready || slot >= M3DS_AUDIO_STREAM_BUFFER_COUNT) {
        return false;
    }
    u8 status = ctx->wavebufs[slot].status;
    return status == NDSP_WBUF_FREE || status == NDSP_WBUF_DONE;
}

static bool m3ds_ndsp_stream_buffer_submit_cb(
    void *raw_ctx,
    uint32_t slot,
    const int16_t *samples,
    uint32_t frame_count) {
    M3dsNdspStreamContext *ctx = (M3dsNdspStreamContext *)raw_ctx;
    if (!ctx || !ctx->ready || !ctx->ndsp_ctx || !ctx->ndsp_ctx->ndsp_ready ||
        slot >= M3DS_AUDIO_STREAM_BUFFER_COUNT ||
        !ctx->linear_bufs[slot] || !samples ||
        frame_count == 0u || frame_count > M3DS_AUDIO_STREAM_CHUNK_FRAMES ||
        (ctx->channels != 1u && ctx->channels != M3DS_AUDIO_STREAM_MAX_CHANNELS)) {
        return false;
    }

    size_t byte_size = (size_t)frame_count * (size_t)ctx->channels * sizeof(int16_t);
    memcpy(ctx->linear_bufs[slot], samples, byte_size);
    DSP_FlushDataCache(ctx->linear_bufs[slot], (u32)byte_size);

    ndspWaveBuf *wb = &ctx->wavebufs[slot];
    memset(wb, 0, sizeof(*wb));
    wb->data_pcm16 = (s16 *)ctx->linear_bufs[slot];
    wb->nsamples = (u32)frame_count;
    wb->looping = false;
    wb->status = NDSP_WBUF_FREE;

    ndspChnWaveBufAdd((int)ctx->channel, wb);
    return true;
}

static void m3ds_ndsp_stream_set_mix_cb(
    void *raw_ctx,
    float gain_left,
    float gain_right) {
    const M3dsNdspStreamContext *ctx = (const M3dsNdspStreamContext *)raw_ctx;
    if (!ctx || !ctx->ready || !ctx->ndsp_ctx || !ctx->ndsp_ctx->ndsp_ready) {
        return;
    }
    m3ds_ndsp_stream_set_mix_raw((int)ctx->channel, gain_left, gain_right);
}

static void m3ds_ndsp_stream_set_paused_cb(void *raw_ctx, bool paused) {
    const M3dsNdspStreamContext *ctx = (const M3dsNdspStreamContext *)raw_ctx;
    if (!ctx || !ctx->ready || !ctx->ndsp_ctx || !ctx->ndsp_ctx->ndsp_ready) {
        return;
    }
    ndspChnSetPaused((int)ctx->channel, paused);
}

static void m3ds_ndsp_stream_stop_cb(void *raw_ctx) {
    M3dsNdspStreamContext *ctx = (M3dsNdspStreamContext *)raw_ctx;
    if (!ctx || !ctx->ready || !ctx->ndsp_ctx || !ctx->ndsp_ctx->ndsp_ready) {
        return;
    }
    ndspChnWaveBufClear((int)ctx->channel);
    memset(ctx->wavebufs, 0, sizeof(ctx->wavebufs));
    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        ctx->wavebufs[i].status = NDSP_WBUF_FREE;
    }
}

void m3ds_ndsp_stream_backend_init(
    M3dsAudioStreamBackend *backend,
    M3dsNdspStreamContext *ctx,
    const M3dsNdspContext *ndsp_ctx) {
    if (!backend) {
        return;
    }
    memset(backend, 0, sizeof(*backend));
    if (ctx) {
        memset(ctx, 0, sizeof(*ctx));
        ctx->ndsp_ctx = ndsp_ctx;
        ctx->channel = M3DS_AUDIO_STREAM_NDSP_CHANNEL;
    }
    backend->ctx = ctx;
    backend->name = "ndsp-stream";
    backend->init = m3ds_ndsp_stream_init_cb;
    backend->shutdown = m3ds_ndsp_stream_shutdown_cb;
    backend->configure = m3ds_ndsp_stream_configure_cb;
    backend->buffer_is_free = m3ds_ndsp_stream_buffer_is_free_cb;
    backend->buffer_submit = m3ds_ndsp_stream_buffer_submit_cb;
    backend->set_mix = m3ds_ndsp_stream_set_mix_cb;
    backend->set_paused = m3ds_ndsp_stream_set_paused_cb;
    backend->stop = m3ds_ndsp_stream_stop_cb;
}
