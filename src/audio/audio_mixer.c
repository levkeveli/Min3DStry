#include "audio/audio_mixer.h"

#include <stdio.h>
#include <string.h>

static float m3ds_clamp_float(float val, float lo, float hi) {
    if (!(val >= lo)) {
        return lo;
    }
    if (!(val <= hi)) {
        return hi;
    }
    return val;
}

static bool m3ds_is_valid_bus(M3dsAudioBus bus) {
    return bus == M3DS_AUDIO_BUS_SFX || bus == M3DS_AUDIO_BUS_MUSIC;
}

static void m3ds_compute_voice_gains(
    const M3dsAudioMixer *mixer,
    const M3dsAudioPlayParams *params,
    float *out_left,
    float *out_right) {
    float vol = m3ds_clamp_float(params->volume, 0.0f, 1.0f);
    float pan = m3ds_clamp_float(params->pan, -1.0f, 1.0f);
    float bus_vol = mixer->bus_volume[params->bus];
    float pan_left = (pan <= 0.0f) ? 1.0f : (1.0f - pan);
    float pan_right = (pan >= 0.0f) ? 1.0f : (1.0f + pan);

    *out_left = vol * bus_vol * pan_left;
    *out_right = vol * bus_vol * pan_right;
}

static void m3ds_clear_voice_slot(M3dsAudioMixer *mixer, uint32_t channel) {
    memset(&mixer->voices[channel], 0, sizeof(mixer->voices[channel]));
    if (mixer->stats.active_voices > 0u) {
        mixer->stats.active_voices -= 1u;
    }
}

static void m3ds_stop_voice_channel(M3dsAudioMixer *mixer, uint32_t channel) {
    if (!mixer->voices[channel].active) {
        return;
    }
    if (mixer->state == M3DS_AUDIO_STATE_READY && mixer->backend.voice_stop) {
        mixer->backend.voice_stop(mixer->backend.ctx, channel);
    }
    m3ds_clear_voice_slot(mixer, channel);
}

static uint32_t m3ds_next_voice_handle(M3dsAudioMixer *mixer) {
    uint32_t handle = mixer->next_handle;
    if (handle == M3DS_AUDIO_INVALID_VOICE) {
        handle = 1u;
    }
    mixer->next_handle = handle + 1u;
    if (mixer->next_handle == M3DS_AUDIO_INVALID_VOICE) {
        mixer->next_handle = 1u;
    }
    return handle;
}

bool m3ds_audio_mixer_init(M3dsAudioMixer *mixer, const M3dsAudioBackend *backend) {
    if (!mixer) {
        return false;
    }

    memset(mixer, 0, sizeof(*mixer));
    mixer->master_volume = 1.0f;
    mixer->bus_volume[M3DS_AUDIO_BUS_SFX] = 1.0f;
    mixer->bus_volume[M3DS_AUDIO_BUS_MUSIC] = 0.7f;
    mixer->next_handle = 1u;
    mixer->next_seq = 1u;

    if (!backend) {
        mixer->state = M3DS_AUDIO_STATE_DISABLED;
        return true;
    }

    mixer->backend = *backend;
    mixer->has_backend = true;

    if (mixer->backend.init) {
        mixer->state = mixer->backend.init(mixer->backend.ctx, &mixer->status_code);
    } else {
        mixer->state = M3DS_AUDIO_STATE_DISABLED;
    }

    if (mixer->state == M3DS_AUDIO_STATE_READY && mixer->backend.set_master_volume) {
        mixer->backend.set_master_volume(mixer->backend.ctx, mixer->master_volume);
    }

    return true;
}

void m3ds_audio_mixer_shutdown(M3dsAudioMixer *mixer) {
    if (!mixer || mixer->state == M3DS_AUDIO_STATE_UNINITIALIZED) {
        return;
    }

    m3ds_audio_mixer_stop_all(mixer);

    for (uint32_t i = 0u; i < M3DS_AUDIO_MAX_CLIPS; ++i) {
        if (mixer->clips[i].in_use) {
            if (mixer->clips[i].uploaded &&
                mixer->state == M3DS_AUDIO_STATE_READY &&
                mixer->backend.unload_clip) {
                mixer->backend.unload_clip(mixer->backend.ctx, (int)i);
            }
            memset(&mixer->clips[i], 0, sizeof(mixer->clips[i]));
        }
    }

    mixer->stats.registered_clips = 0u;
    mixer->stats.clip_bytes_uploaded = 0u;

    if (mixer->has_backend && mixer->backend.shutdown) {
        mixer->backend.shutdown(mixer->backend.ctx);
    }

    mixer->state = M3DS_AUDIO_STATE_UNINITIALIZED;
}

M3dsAudioBackendState m3ds_audio_mixer_state(const M3dsAudioMixer *mixer) {
    if (!mixer) {
        return M3DS_AUDIO_STATE_UNINITIALIZED;
    }
    return mixer->state;
}

const char *m3ds_audio_state_name(M3dsAudioBackendState state) {
    switch (state) {
    case M3DS_AUDIO_STATE_READY:
        return "ready";
    case M3DS_AUDIO_STATE_FALLBACK_NO_DSP:
        return "fallback_no_dsp";
    case M3DS_AUDIO_STATE_DISABLED:
        return "disabled";
    case M3DS_AUDIO_STATE_UNINITIALIZED:
    default:
        return "uninitialized";
    }
}

uint32_t m3ds_audio_mixer_status_code(const M3dsAudioMixer *mixer) {
    if (!mixer) {
        return 0u;
    }
    return mixer->status_code;
}

int m3ds_audio_mixer_register_clip(
    M3dsAudioMixer *mixer,
    const M3dsAudioClipDesc *desc) {
    if (!mixer || !desc || mixer->state == M3DS_AUDIO_STATE_UNINITIALIZED) {
        return M3DS_AUDIO_INVALID_CLIP;
    }
    if (!desc->samples ||
        desc->frame_count == 0u ||
        desc->frame_count > M3DS_AUDIO_MAX_CLIP_SAMPLES ||
        desc->sample_rate < 4000u ||
        desc->sample_rate > 48000u ||
        (desc->channels != 1u && desc->channels != 2u)) {
        return M3DS_AUDIO_INVALID_CLIP;
    }

    int free_slot = M3DS_AUDIO_INVALID_CLIP;
    for (uint32_t i = 0u; i < M3DS_AUDIO_MAX_CLIPS; ++i) {
        if (!mixer->clips[i].in_use) {
            free_slot = (int)i;
            break;
        }
    }
    if (free_slot < 0) {
        return M3DS_AUDIO_INVALID_CLIP;
    }

    bool uploaded = false;
    uint32_t clip_bytes =
        desc->frame_count * (uint32_t)desc->channels * (uint32_t)sizeof(int16_t);
    if (mixer->state == M3DS_AUDIO_STATE_READY) {
        if (!mixer->backend.upload_clip ||
            !mixer->backend.upload_clip(mixer->backend.ctx, free_slot, desc)) {
            return M3DS_AUDIO_INVALID_CLIP;
        }
        uploaded = true;
        mixer->stats.clip_bytes_uploaded += clip_bytes;
    }

    mixer->clips[free_slot].in_use = true;
    mixer->clips[free_slot].uploaded = uploaded;
    mixer->clips[free_slot].desc = *desc;
    mixer->stats.registered_clips += 1u;
    return free_slot;
}

void m3ds_audio_mixer_unregister_clip(M3dsAudioMixer *mixer, int clip_id) {
    if (!mixer || clip_id < 0 || (uint32_t)clip_id >= M3DS_AUDIO_MAX_CLIPS) {
        return;
    }
    if (!mixer->clips[clip_id].in_use) {
        return;
    }

    for (uint32_t ch = 0u; ch < M3DS_AUDIO_MAX_VOICES; ++ch) {
        if (mixer->voices[ch].active && mixer->voices[ch].clip_id == clip_id) {
            m3ds_stop_voice_channel(mixer, ch);
        }
    }

    if (mixer->clips[clip_id].uploaded) {
        uint32_t clip_bytes = mixer->clips[clip_id].desc.frame_count *
                              (uint32_t)mixer->clips[clip_id].desc.channels *
                              (uint32_t)sizeof(int16_t);
        if (mixer->state == M3DS_AUDIO_STATE_READY && mixer->backend.unload_clip) {
            mixer->backend.unload_clip(mixer->backend.ctx, clip_id);
        }
        if (mixer->stats.clip_bytes_uploaded >= clip_bytes) {
            mixer->stats.clip_bytes_uploaded -= clip_bytes;
        } else {
            mixer->stats.clip_bytes_uploaded = 0u;
        }
    }

    memset(&mixer->clips[clip_id], 0, sizeof(mixer->clips[clip_id]));
    if (mixer->stats.registered_clips > 0u) {
        mixer->stats.registered_clips -= 1u;
    }
}

M3dsAudioPlayParams m3ds_audio_default_play_params(M3dsAudioBus bus) {
    M3dsAudioPlayParams params;
    M3dsAudioBus normalized_bus = m3ds_is_valid_bus(bus) ? bus : M3DS_AUDIO_BUS_SFX;
    params.bus = normalized_bus;
    params.volume = 1.0f;
    params.pan = 0.0f;
    params.priority = (normalized_bus == M3DS_AUDIO_BUS_MUSIC) ? 200u : 100u;
    params.looping = (normalized_bus == M3DS_AUDIO_BUS_MUSIC);
    return params;
}

uint32_t m3ds_audio_mixer_play(
    M3dsAudioMixer *mixer,
    int clip_id,
    const M3dsAudioPlayParams *params) {
    if (!mixer || clip_id < 0 || (uint32_t)clip_id >= M3DS_AUDIO_MAX_CLIPS) {
        return M3DS_AUDIO_INVALID_VOICE;
    }
    if (!mixer->clips[clip_id].in_use) {
        return M3DS_AUDIO_INVALID_VOICE;
    }
    if (mixer->state != M3DS_AUDIO_STATE_READY) {
        mixer->stats.total_dropped += 1u;
        return M3DS_AUDIO_INVALID_VOICE;
    }

    M3dsAudioPlayParams resolved =
        params ? *params : m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
    if (!m3ds_is_valid_bus(resolved.bus)) {
        resolved.bus = M3DS_AUDIO_BUS_SFX;
    }
    resolved.volume = m3ds_clamp_float(resolved.volume, 0.0f, 1.0f);
    resolved.pan = m3ds_clamp_float(resolved.pan, -1.0f, 1.0f);

    int target_ch = -1;
    for (uint32_t ch = 0u; ch < M3DS_AUDIO_MAX_VOICES; ++ch) {
        if (!mixer->voices[ch].active) {
            target_ch = (int)ch;
            break;
        }
    }

    if (target_ch < 0) {
        int victim_ch = -1;
        uint8_t best_pri = resolved.priority;
        uint64_t best_seq = UINT64_MAX;
        for (uint32_t ch = 0u; ch < M3DS_AUDIO_MAX_VOICES; ++ch) {
            const M3dsAudioVoiceSlot *slot = &mixer->voices[ch];
            if (slot->params.priority <= resolved.priority) {
                if (victim_ch < 0 ||
                    slot->params.priority < best_pri ||
                    (slot->params.priority == best_pri && slot->start_seq < best_seq)) {
                    victim_ch = (int)ch;
                    best_pri = slot->params.priority;
                    best_seq = slot->start_seq;
                }
            }
        }
        if (victim_ch < 0) {
            mixer->stats.total_dropped += 1u;
            return M3DS_AUDIO_INVALID_VOICE;
        }
        m3ds_stop_voice_channel(mixer, (uint32_t)victim_ch);
        mixer->stats.total_stolen += 1u;
        target_ch = victim_ch;
    }

    float gain_left = 0.0f;
    float gain_right = 0.0f;
    m3ds_compute_voice_gains(mixer, &resolved, &gain_left, &gain_right);

    if (!mixer->backend.voice_start ||
        !mixer->backend.voice_start(
            mixer->backend.ctx,
            (uint32_t)target_ch,
            clip_id,
            &mixer->clips[clip_id].desc,
            gain_left,
            gain_right,
            resolved.looping)) {
        mixer->stats.total_dropped += 1u;
        return M3DS_AUDIO_INVALID_VOICE;
    }

    uint32_t handle = m3ds_next_voice_handle(mixer);
    mixer->voices[target_ch].active = true;
    mixer->voices[target_ch].handle = handle;
    mixer->voices[target_ch].clip_id = clip_id;
    mixer->voices[target_ch].start_seq = mixer->next_seq++;
    mixer->voices[target_ch].params = resolved;
    mixer->voices[target_ch].paused = mixer->paused;

    if (mixer->paused && mixer->backend.voice_set_paused) {
        mixer->backend.voice_set_paused(mixer->backend.ctx, (uint32_t)target_ch, true);
    }

    mixer->stats.active_voices += 1u;
    mixer->stats.total_started += 1u;
    if (mixer->stats.active_voices > mixer->stats.peak_voices) {
        mixer->stats.peak_voices = mixer->stats.active_voices;
    }

    return handle;
}

void m3ds_audio_mixer_stop(M3dsAudioMixer *mixer, uint32_t voice_handle) {
    if (!mixer || voice_handle == M3DS_AUDIO_INVALID_VOICE) {
        return;
    }
    for (uint32_t ch = 0u; ch < M3DS_AUDIO_MAX_VOICES; ++ch) {
        if (mixer->voices[ch].active && mixer->voices[ch].handle == voice_handle) {
            m3ds_stop_voice_channel(mixer, ch);
            return;
        }
    }
}

void m3ds_audio_mixer_stop_bus(M3dsAudioMixer *mixer, M3dsAudioBus bus) {
    if (!mixer || !m3ds_is_valid_bus(bus)) {
        return;
    }
    for (uint32_t ch = 0u; ch < M3DS_AUDIO_MAX_VOICES; ++ch) {
        if (mixer->voices[ch].active && mixer->voices[ch].params.bus == bus) {
            m3ds_stop_voice_channel(mixer, ch);
        }
    }
}

void m3ds_audio_mixer_stop_all(M3dsAudioMixer *mixer) {
    if (!mixer) {
        return;
    }
    for (uint32_t ch = 0u; ch < M3DS_AUDIO_MAX_VOICES; ++ch) {
        if (mixer->voices[ch].active) {
            m3ds_stop_voice_channel(mixer, ch);
        }
    }
}

void m3ds_audio_mixer_set_master_volume(M3dsAudioMixer *mixer, float volume) {
    if (!mixer) {
        return;
    }
    mixer->master_volume = m3ds_clamp_float(volume, 0.0f, 1.0f);
    if (mixer->state == M3DS_AUDIO_STATE_READY && mixer->backend.set_master_volume) {
        float effective = mixer->muted ? 0.0f : mixer->master_volume;
        mixer->backend.set_master_volume(mixer->backend.ctx, effective);
    }
}

float m3ds_audio_mixer_master_volume(const M3dsAudioMixer *mixer) {
    return mixer ? mixer->master_volume : 0.0f;
}

void m3ds_audio_mixer_set_bus_volume(
    M3dsAudioMixer *mixer,
    M3dsAudioBus bus,
    float volume) {
    if (!mixer || !m3ds_is_valid_bus(bus)) {
        return;
    }
    mixer->bus_volume[bus] = m3ds_clamp_float(volume, 0.0f, 1.0f);
    if (mixer->state == M3DS_AUDIO_STATE_READY && mixer->backend.voice_set_mix) {
        for (uint32_t ch = 0u; ch < M3DS_AUDIO_MAX_VOICES; ++ch) {
            if (mixer->voices[ch].active && mixer->voices[ch].params.bus == bus) {
                float gain_left = 0.0f;
                float gain_right = 0.0f;
                m3ds_compute_voice_gains(
                    mixer, &mixer->voices[ch].params, &gain_left, &gain_right);
                mixer->backend.voice_set_mix(
                    mixer->backend.ctx, ch, gain_left, gain_right);
            }
        }
    }
}

float m3ds_audio_mixer_bus_volume(const M3dsAudioMixer *mixer, M3dsAudioBus bus) {
    if (!mixer || !m3ds_is_valid_bus(bus)) {
        return 0.0f;
    }
    return mixer->bus_volume[bus];
}

void m3ds_audio_mixer_set_muted(M3dsAudioMixer *mixer, bool muted) {
    if (!mixer) {
        return;
    }
    mixer->muted = muted;
    if (mixer->state == M3DS_AUDIO_STATE_READY && mixer->backend.set_master_volume) {
        float effective = mixer->muted ? 0.0f : mixer->master_volume;
        mixer->backend.set_master_volume(mixer->backend.ctx, effective);
    }
}

bool m3ds_audio_mixer_is_muted(const M3dsAudioMixer *mixer) {
    return mixer ? mixer->muted : false;
}

void m3ds_audio_mixer_set_paused(M3dsAudioMixer *mixer, bool paused) {
    if (!mixer) {
        return;
    }
    mixer->paused = paused;
    for (uint32_t ch = 0u; ch < M3DS_AUDIO_MAX_VOICES; ++ch) {
        if (mixer->voices[ch].active) {
            mixer->voices[ch].paused = paused;
            if (mixer->state == M3DS_AUDIO_STATE_READY &&
                mixer->backend.voice_set_paused) {
                mixer->backend.voice_set_paused(mixer->backend.ctx, ch, paused);
            }
        }
    }
}

bool m3ds_audio_mixer_is_paused(const M3dsAudioMixer *mixer) {
    return mixer ? mixer->paused : false;
}

void m3ds_audio_mixer_update(M3dsAudioMixer *mixer) {
    if (!mixer ||
        mixer->state != M3DS_AUDIO_STATE_READY ||
        !mixer->backend.voice_is_active) {
        return;
    }

    for (uint32_t ch = 0u; ch < M3DS_AUDIO_MAX_VOICES; ++ch) {
        if (mixer->voices[ch].active && !mixer->voices[ch].paused) {
            if (!mixer->backend.voice_is_active(mixer->backend.ctx, ch)) {
                m3ds_clear_voice_slot(mixer, ch);
                mixer->stats.total_completed += 1u;
            }
        }
    }
}

void m3ds_audio_mixer_get_stats(
    const M3dsAudioMixer *mixer,
    M3dsAudioStats *out_stats) {
    if (!out_stats) {
        return;
    }
    if (!mixer) {
        memset(out_stats, 0, sizeof(*out_stats));
        return;
    }
    *out_stats = mixer->stats;
}

size_t m3ds_audio_mixer_format_diag(
    const M3dsAudioMixer *mixer,
    char *out_buf,
    size_t out_buf_size) {
    if (!out_buf || out_buf_size == 0u) {
        return 0u;
    }
    if (!mixer) {
        int written = snprintf(
            out_buf,
            out_buf_size,
            "backend=none state=uninitialized rc=0x00000000 rate=%u voices=0/%u clips=0",
            M3DS_AUDIO_DEFAULT_SAMPLE_RATE,
            M3DS_AUDIO_MAX_VOICES);
        return (written < 0) ? 0u : (size_t)written;
    }

    const char *bname =
        (mixer->has_backend && mixer->backend.name) ? mixer->backend.name : "none";
    int written = snprintf(
        out_buf,
        out_buf_size,
        "backend=%s state=%s rc=0x%08lx rate=%u voices=%lu/%u clips=%lu",
        bname,
        m3ds_audio_state_name(mixer->state),
        (unsigned long)mixer->status_code,
        M3DS_AUDIO_DEFAULT_SAMPLE_RATE,
        (unsigned long)mixer->stats.active_voices,
        M3DS_AUDIO_MAX_VOICES,
        (unsigned long)mixer->stats.registered_clips);
    if (written < 0) {
        out_buf[0] = '\0';
        return 0u;
    }
    return (size_t)written;
}

void m3ds_audio_synth_tone_pcm16(
    int16_t *out_samples,
    uint32_t frame_count,
    uint32_t sample_rate,
    uint32_t freq_hz,
    uint16_t amplitude) {
    if (!out_samples || frame_count == 0u || sample_rate == 0u) {
        return;
    }
    if (freq_hz == 0u || amplitude == 0u) {
        memset(out_samples, 0, (size_t)frame_count * sizeof(int16_t));
        return;
    }

    uint32_t amp = (amplitude > 32767u) ? 32767u : (uint32_t)amplitude;
    uint32_t atk = frame_count / 16u;
    uint32_t rel = frame_count / 4u;

    for (uint32_t i = 0u; i < frame_count; ++i) {
        uint64_t phase =
            ((uint64_t)i * (uint64_t)freq_hz) % (uint64_t)sample_rate;
        uint64_t p4 = phase * 4u;
        int64_t val = 0;

        if (p4 < (uint64_t)sample_rate) {
            val = (int64_t)((p4 * (uint64_t)amp) / (uint64_t)sample_rate);
        } else if (p4 < 3u * (uint64_t)sample_rate) {
            val = (int64_t)amp -
                  (int64_t)(((p4 - (uint64_t)sample_rate) * (uint64_t)amp) /
                            (uint64_t)sample_rate);
        } else {
            val = -(int64_t)amp +
                  (int64_t)(((p4 - 3u * (uint64_t)sample_rate) * (uint64_t)amp) /
                            (uint64_t)sample_rate);
        }

        if (atk > 0u && i < atk) {
            val = (val * (int64_t)i) / (int64_t)atk;
        } else if (rel > 0u && i >= frame_count - rel) {
            uint32_t rem = (frame_count - 1u) - i;
            val = (val * (int64_t)rem) / (int64_t)rel;
        }

        if (val > 32767) {
            val = 32767;
        } else if (val < -32767) {
            val = -32767;
        }
        out_samples[i] = (int16_t)val;
    }
}
