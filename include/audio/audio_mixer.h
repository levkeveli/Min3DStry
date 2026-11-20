#ifndef MIN3DSTRY_AUDIO_AUDIO_MIXER_H
#define MIN3DSTRY_AUDIO_AUDIO_MIXER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define M3DS_AUDIO_DEFAULT_SAMPLE_RATE 22050u
#define M3DS_AUDIO_MAX_CLIPS 16u
#define M3DS_AUDIO_MAX_VOICES 8u
#define M3DS_AUDIO_MAX_CLIP_SAMPLES (22050u * 4u)
#define M3DS_AUDIO_INVALID_CLIP (-1)
#define M3DS_AUDIO_INVALID_VOICE 0u

typedef enum M3dsAudioBus {
    M3DS_AUDIO_BUS_SFX = 0,
    M3DS_AUDIO_BUS_MUSIC = 1,
    M3DS_AUDIO_BUS_COUNT = 2
} M3dsAudioBus;

typedef enum M3dsAudioBackendState {
    M3DS_AUDIO_STATE_UNINITIALIZED = 0,
    M3DS_AUDIO_STATE_READY = 1,
    M3DS_AUDIO_STATE_FALLBACK_NO_DSP = 2,
    M3DS_AUDIO_STATE_DISABLED = 3
} M3dsAudioBackendState;

typedef struct M3dsAudioClipDesc {
    const int16_t *samples;
    uint32_t frame_count;
    uint32_t sample_rate;
    uint8_t channels;
} M3dsAudioClipDesc;

typedef struct M3dsAudioPlayParams {
    M3dsAudioBus bus;
    float volume;
    float pan;
    uint8_t priority;
    bool looping;
} M3dsAudioPlayParams;

typedef struct M3dsAudioStats {
    uint32_t registered_clips;
    uint32_t active_voices;
    uint32_t peak_voices;
    uint32_t total_started;
    uint32_t total_stolen;
    uint32_t total_dropped;
    uint32_t total_completed;
    uint32_t clip_bytes_uploaded;
} M3dsAudioStats;

typedef struct M3dsAudioBackend {
    void *ctx;
    const char *name;
    M3dsAudioBackendState (*init)(void *ctx, uint32_t *out_status_code);
    void (*shutdown)(void *ctx);
    void (*set_master_volume)(void *ctx, float volume);
    bool (*upload_clip)(void *ctx, int clip_id, const M3dsAudioClipDesc *desc);
    void (*unload_clip)(void *ctx, int clip_id);
    bool (*voice_start)(
        void *ctx,
        uint32_t channel,
        int clip_id,
        const M3dsAudioClipDesc *desc,
        float gain_left,
        float gain_right,
        bool looping);
    void (*voice_set_mix)(
        void *ctx,
        uint32_t channel,
        float gain_left,
        float gain_right);
    void (*voice_set_paused)(void *ctx, uint32_t channel, bool paused);
    void (*voice_stop)(void *ctx, uint32_t channel);
    bool (*voice_is_active)(void *ctx, uint32_t channel);
} M3dsAudioBackend;

typedef struct M3dsAudioClipSlot {
    bool in_use;
    bool uploaded;
    M3dsAudioClipDesc desc;
} M3dsAudioClipSlot;

typedef struct M3dsAudioVoiceSlot {
    bool active;
    uint32_t handle;
    int clip_id;
    uint64_t start_seq;
    M3dsAudioPlayParams params;
    bool paused;
} M3dsAudioVoiceSlot;

typedef struct M3dsAudioMixer {
    M3dsAudioBackend backend;
    bool has_backend;
    M3dsAudioBackendState state;
    uint32_t status_code;
    float master_volume;
    float bus_volume[M3DS_AUDIO_BUS_COUNT];
    bool muted;
    bool paused;
    uint32_t next_handle;
    uint64_t next_seq;
    M3dsAudioClipSlot clips[M3DS_AUDIO_MAX_CLIPS];
    M3dsAudioVoiceSlot voices[M3DS_AUDIO_MAX_VOICES];
    M3dsAudioStats stats;
} M3dsAudioMixer;

bool m3ds_audio_mixer_init(M3dsAudioMixer *mixer, const M3dsAudioBackend *backend);
void m3ds_audio_mixer_shutdown(M3dsAudioMixer *mixer);

M3dsAudioBackendState m3ds_audio_mixer_state(const M3dsAudioMixer *mixer);
const char *m3ds_audio_state_name(M3dsAudioBackendState state);
uint32_t m3ds_audio_mixer_status_code(const M3dsAudioMixer *mixer);

int m3ds_audio_mixer_register_clip(
    M3dsAudioMixer *mixer,
    const M3dsAudioClipDesc *desc);
void m3ds_audio_mixer_unregister_clip(M3dsAudioMixer *mixer, int clip_id);

M3dsAudioPlayParams m3ds_audio_default_play_params(M3dsAudioBus bus);
uint32_t m3ds_audio_mixer_play(
    M3dsAudioMixer *mixer,
    int clip_id,
    const M3dsAudioPlayParams *params);
void m3ds_audio_mixer_stop(M3dsAudioMixer *mixer, uint32_t voice_handle);
void m3ds_audio_mixer_stop_bus(M3dsAudioMixer *mixer, M3dsAudioBus bus);
void m3ds_audio_mixer_stop_all(M3dsAudioMixer *mixer);

void m3ds_audio_mixer_set_master_volume(M3dsAudioMixer *mixer, float volume);
float m3ds_audio_mixer_master_volume(const M3dsAudioMixer *mixer);
void m3ds_audio_mixer_set_bus_volume(
    M3dsAudioMixer *mixer,
    M3dsAudioBus bus,
    float volume);
float m3ds_audio_mixer_bus_volume(const M3dsAudioMixer *mixer, M3dsAudioBus bus);

void m3ds_audio_mixer_set_muted(M3dsAudioMixer *mixer, bool muted);
bool m3ds_audio_mixer_is_muted(const M3dsAudioMixer *mixer);

void m3ds_audio_mixer_set_paused(M3dsAudioMixer *mixer, bool paused);
bool m3ds_audio_mixer_is_paused(const M3dsAudioMixer *mixer);

void m3ds_audio_mixer_update(M3dsAudioMixer *mixer);
void m3ds_audio_mixer_get_stats(
    const M3dsAudioMixer *mixer,
    M3dsAudioStats *out_stats);
size_t m3ds_audio_mixer_format_diag(
    const M3dsAudioMixer *mixer,
    char *out_buf,
    size_t out_buf_size);

void m3ds_audio_synth_tone_pcm16(
    int16_t *out_samples,
    uint32_t frame_count,
    uint32_t sample_rate,
    uint32_t freq_hz,
    uint16_t amplitude);

#endif /* MIN3DSTRY_AUDIO_AUDIO_MIXER_H */
