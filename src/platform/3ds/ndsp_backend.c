#include "ndsp_backend.h"

#include <string.h>

static M3dsAudioBackendState m3ds_ndsp_init_cb(
    void *raw_ctx,
    uint32_t *out_status_code) {
    M3dsNdspContext *ctx = (M3dsNdspContext *)raw_ctx;
    if (!ctx) {
        if (out_status_code) {
            *out_status_code = 0u;
        }
        return M3DS_AUDIO_STATE_DISABLED;
    }

    memset(ctx, 0, sizeof(*ctx));
    Result rc = ndspInit();
    ctx->init_result = rc;
    if (out_status_code) {
        *out_status_code = (uint32_t)rc;
    }

    if (R_FAILED(rc)) {
        /* Never call ndspExit or channel APIs when DSP firmware failed to load. */
        ctx->ndsp_ready = false;
        return M3DS_AUDIO_STATE_FALLBACK_NO_DSP;
    }

    ctx->ndsp_ready = true;
    ndspSetOutputMode(NDSP_OUTPUT_STEREO);
    ndspSetClippingMode(NDSP_CLIP_SOFT);
    ndspSetMasterVol(1.0f);

    for (int ch = 0; ch < (int)M3DS_AUDIO_MAX_VOICES; ++ch) {
        ndspChnReset(ch);
        ndspChnInitParams(ch);
        ndspChnSetInterp(ch, NDSP_INTERP_LINEAR);
    }

    return M3DS_AUDIO_STATE_READY;
}

static void m3ds_ndsp_shutdown_cb(void *raw_ctx) {
    M3dsNdspContext *ctx = (M3dsNdspContext *)raw_ctx;
    if (!ctx || !ctx->ndsp_ready) {
        return;
    }

    /* Stop channels before freeing linear memory so DSP DMA cannot read freed buffers. */
    for (int ch = 0; ch < (int)M3DS_AUDIO_MAX_VOICES; ++ch) {
        ndspChnWaveBufClear(ch);
        ndspChnReset(ch);
    }
    memset(ctx->wavebufs, 0, sizeof(ctx->wavebufs));

    for (uint32_t i = 0u; i < M3DS_AUDIO_MAX_CLIPS; ++i) {
        if (ctx->clips[i].linear_pcm) {
            linearFree(ctx->clips[i].linear_pcm);
            ctx->clips[i].linear_pcm = NULL;
            ctx->clips[i].byte_size = 0u;
        }
    }

    ndspExit();
    ctx->ndsp_ready = false;
}

static void m3ds_ndsp_set_master_volume_cb(void *raw_ctx, float volume) {
    const M3dsNdspContext *ctx = (const M3dsNdspContext *)raw_ctx;
    if (!ctx || !ctx->ndsp_ready) {
        return;
    }
    ndspSetMasterVol(volume);
}

static bool m3ds_ndsp_upload_clip_cb(
    void *raw_ctx,
    int clip_id,
    const M3dsAudioClipDesc *desc) {
    M3dsNdspContext *ctx = (M3dsNdspContext *)raw_ctx;
    if (!ctx || !ctx->ndsp_ready || !desc ||
        clip_id < 0 || (uint32_t)clip_id >= M3DS_AUDIO_MAX_CLIPS) {
        return false;
    }

    size_t byte_size =
        (size_t)desc->frame_count * (size_t)desc->channels * sizeof(int16_t);
    int16_t *pcm = (int16_t *)linearAlloc(byte_size);
    if (!pcm) {
        return false;
    }

    memcpy(pcm, desc->samples, byte_size);
    DSP_FlushDataCache(pcm, (u32)byte_size);

    ctx->clips[clip_id].linear_pcm = pcm;
    ctx->clips[clip_id].byte_size = byte_size;
    return true;
}

static void m3ds_ndsp_unload_clip_cb(void *raw_ctx, int clip_id) {
    M3dsNdspContext *ctx = (M3dsNdspContext *)raw_ctx;
    if (!ctx || !ctx->ndsp_ready ||
        clip_id < 0 || (uint32_t)clip_id >= M3DS_AUDIO_MAX_CLIPS) {
        return;
    }

    if (ctx->clips[clip_id].linear_pcm) {
        linearFree(ctx->clips[clip_id].linear_pcm);
        ctx->clips[clip_id].linear_pcm = NULL;
        ctx->clips[clip_id].byte_size = 0u;
    }
}

static void m3ds_ndsp_voice_set_mix_cb(
    void *raw_ctx,
    uint32_t channel,
    float gain_left,
    float gain_right) {
    const M3dsNdspContext *ctx = (const M3dsNdspContext *)raw_ctx;
    if (!ctx || !ctx->ndsp_ready || channel >= M3DS_AUDIO_MAX_VOICES) {
        return;
    }

    float mix[12];
    memset(mix, 0, sizeof(mix));
    mix[0] = gain_left;
    mix[1] = gain_right;
    ndspChnSetMix((int)channel, mix);
}

static bool m3ds_ndsp_voice_start_cb(
    void *raw_ctx,
    uint32_t channel,
    int clip_id,
    const M3dsAudioClipDesc *desc,
    float gain_left,
    float gain_right,
    float pitch,
    bool looping) {
    M3dsNdspContext *ctx = (M3dsNdspContext *)raw_ctx;
    if (!ctx || !ctx->ndsp_ready || !desc ||
        channel >= M3DS_AUDIO_MAX_VOICES ||
        clip_id < 0 || (uint32_t)clip_id >= M3DS_AUDIO_MAX_CLIPS ||
        !ctx->clips[clip_id].linear_pcm) {
        return false;
    }

    int ch = (int)channel;
    ndspChnWaveBufClear(ch);
    ndspChnReset(ch);
    ndspChnInitParams(ch);
    ndspChnSetInterp(ch, NDSP_INTERP_LINEAR);
    ndspChnSetRate(ch, (float)desc->sample_rate * pitch);
    ndspChnSetFormat(
        ch,
        desc->channels == 2u ? NDSP_FORMAT_STEREO_PCM16 : NDSP_FORMAT_MONO_PCM16);

    float mix[12];
    memset(mix, 0, sizeof(mix));
    mix[0] = gain_left;
    mix[1] = gain_right;
    ndspChnSetMix(ch, mix);

    ndspWaveBuf *wb = &ctx->wavebufs[channel];
    memset(wb, 0, sizeof(*wb));
    wb->data_pcm16 = (s16 *)ctx->clips[clip_id].linear_pcm;
    wb->nsamples = (u32)desc->frame_count;
    wb->looping = looping;
    wb->status = NDSP_WBUF_FREE;

    ndspChnWaveBufAdd(ch, wb);
    return true;
}

static void m3ds_ndsp_voice_set_paused_cb(
    void *raw_ctx,
    uint32_t channel,
    bool paused) {
    const M3dsNdspContext *ctx = (const M3dsNdspContext *)raw_ctx;
    if (!ctx || !ctx->ndsp_ready || channel >= M3DS_AUDIO_MAX_VOICES) {
        return;
    }
    ndspChnSetPaused((int)channel, paused);
}

static void m3ds_ndsp_voice_stop_cb(void *raw_ctx, uint32_t channel) {
    M3dsNdspContext *ctx = (M3dsNdspContext *)raw_ctx;
    if (!ctx || !ctx->ndsp_ready || channel >= M3DS_AUDIO_MAX_VOICES) {
        return;
    }
    ndspChnWaveBufClear((int)channel);
    memset(&ctx->wavebufs[channel], 0, sizeof(ctx->wavebufs[channel]));
}

static bool m3ds_ndsp_voice_is_active_cb(void *raw_ctx, uint32_t channel) {
    const M3dsNdspContext *ctx = (const M3dsNdspContext *)raw_ctx;
    if (!ctx || !ctx->ndsp_ready || channel >= M3DS_AUDIO_MAX_VOICES) {
        return false;
    }
    u8 status = ctx->wavebufs[channel].status;
    return status == NDSP_WBUF_QUEUED || status == NDSP_WBUF_PLAYING;
}

void m3ds_ndsp_backend_init(M3dsAudioBackend *backend, M3dsNdspContext *ctx) {
    if (!backend) {
        return;
    }
    memset(backend, 0, sizeof(*backend));
    backend->ctx = ctx;
    backend->name = "ndsp";
    backend->init = m3ds_ndsp_init_cb;
    backend->shutdown = m3ds_ndsp_shutdown_cb;
    backend->set_master_volume = m3ds_ndsp_set_master_volume_cb;
    backend->upload_clip = m3ds_ndsp_upload_clip_cb;
    backend->unload_clip = m3ds_ndsp_unload_clip_cb;
    backend->voice_start = m3ds_ndsp_voice_start_cb;
    backend->voice_set_mix = m3ds_ndsp_voice_set_mix_cb;
    backend->voice_set_paused = m3ds_ndsp_voice_set_paused_cb;
    backend->voice_stop = m3ds_ndsp_voice_stop_cb;
    backend->voice_is_active = m3ds_ndsp_voice_is_active_cb;
}
