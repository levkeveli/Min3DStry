#include "m3ds_live_audio.h"

#ifdef M3DS_LIVE_AUDIO
#include "audio-index.h"
#include "ndsp_backend.h"
#include <stdarg.h>
#include <stdio.h>

static M3dsAudioMixer mixer;
static M3dsNdspContext backend_context;
static M3dsAudioRegistry registry;
static int16_t samples[M3DS_AUDIO_TOTAL_BYTES / sizeof(int16_t)];
static int clip = M3DS_AUDIO_INVALID_CLIP;
static unsigned play_requests;

static void log_audio(const char *format, ...) {
    char text[180];
    va_list args;
    va_start(args, format);
    int length = vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    if (length > 0 && length < (int)sizeof(text)) svcOutputDebugString(text, length);
}

int32_t m3ds_live_audio_open(void) {
    M3dsAudioBackend backend;
    m3ds_ndsp_backend_init(&backend, &backend_context);
    m3ds_audio_mixer_init(&mixer, &backend);
    M3dsAudioRegistryStatus status = m3ds_audio_registry_load(&registry, &mixer,
        m3ds_audio_index, M3DS_AUDIO_CLIP_COUNT, m3ds_audio_read_file_stdio, NULL,
        samples, sizeof(samples));
    clip = m3ds_audio_registry_find(&registry, "shoot.alpha");
    log_audio("UPSTREAM_LIVE_AUDIO_OPEN asset=%s pcmBytes=%u state=%s rc=0x%08lx\n",
        m3ds_audio_registry_status_name(status), (unsigned)registry.used_bytes,
        m3ds_audio_state_name(m3ds_audio_mixer_state(&mixer)),
        (unsigned long)m3ds_audio_mixer_status_code(&mixer));
    return clip != M3DS_AUDIO_INVALID_CLIP;
}

int32_t m3ds_live_audio_play(float volume, float pitch, float pan, int32_t loop) {
    M3dsAudioPlayParams params = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
    params.volume = volume;
    params.pitch = pitch;
    params.pan = pan;
    params.looping = loop != 0;
    uint32_t voice = m3ds_audio_mixer_play(&mixer, clip, &params);
    if (play_requests++ == 0) {
        log_audio("UPSTREAM_LIVE_AUDIO_PLAY id=shoot.alpha volume=%.3f pitch=%.3f pan=%.3f voice=%lu\n",
            (double)volume, (double)pitch, (double)pan, (unsigned long)voice);
    }
    return (int32_t)voice;
}

void m3ds_live_audio_set_mix(int32_t voice, float pan, float volume) {
    m3ds_audio_mixer_set_voice_mix(&mixer, (uint32_t)voice, pan, volume);
}

void m3ds_live_audio_update(void) {
    m3ds_audio_mixer_update(&mixer);
}

void m3ds_live_audio_reset(void) {
    m3ds_audio_mixer_stop_all(&mixer);
}

void m3ds_live_audio_close(void) {
    M3dsAudioStats stats;
    m3ds_audio_mixer_get_stats(&mixer, &stats);
    log_audio("UPSTREAM_LIVE_AUDIO_CLOSE requests=%u started=%lu dropped=%lu\n",
        play_requests, (unsigned long)stats.total_started, (unsigned long)stats.total_dropped);
    m3ds_audio_mixer_shutdown(&mixer);
}

#else
int32_t m3ds_live_audio_open(void) { return 0; }
int32_t m3ds_live_audio_play(float volume, float pitch, float pan, int32_t loop) {
    (void)volume; (void)pitch; (void)pan; (void)loop;
    return 0;
}
void m3ds_live_audio_set_mix(int32_t voice, float pan, float volume) {
    (void)voice; (void)pan; (void)volume;
}
void m3ds_live_audio_update(void) {}
void m3ds_live_audio_reset(void) {}
void m3ds_live_audio_close(void) {}
#endif
