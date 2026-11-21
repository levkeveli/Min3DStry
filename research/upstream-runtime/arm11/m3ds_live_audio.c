#include "m3ds_live_audio.h"

#ifdef M3DS_LIVE_AUDIO
#include "ndsp_backend.h"
#include "sound-index.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

#define LIVE_PCM_BUDGET (640u * 1024u)

typedef struct ResidentSound {
    const M3dsAudioIndexEntry *entry;
    int16_t *samples;
    int clip;
    size_t bytes;
    uint64_t last_used;
} ResidentSound;

static M3dsAudioMixer mixer;
static M3dsNdspContext backend_context;
static ResidentSound resident[M3DS_AUDIO_MAX_CLIPS];
static size_t resident_bytes;
static uint64_t sequence;
static unsigned cache_hits;
static unsigned cache_loads;
static unsigned cache_evictions;
static unsigned play_requests;

static void log_audio(const char *format, ...) {
    char text[180];
    va_list args;
    va_start(args, format);
    int length = vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    if (length > 0 && length < (int)sizeof(text)) svcOutputDebugString(text, length);
}

static bool sound_in_use(const ResidentSound *sound) {
    for (size_t i = 0; i < M3DS_AUDIO_MAX_VOICES; i++) {
        if (mixer.voices[i].active && mixer.voices[i].clip_id == sound->clip) return true;
    }
    return false;
}

static void release_sound(ResidentSound *sound) {
    m3ds_audio_mixer_unregister_clip(&mixer, sound->clip);
    free(sound->samples);
    resident_bytes -= sound->bytes;
    *sound = (ResidentSound){0};
}

static ResidentSound *find_room(size_t bytes) {
    ResidentSound *empty = NULL;
    ResidentSound *oldest = NULL;
    for (size_t i = 0; i < M3DS_AUDIO_MAX_CLIPS; i++) {
        ResidentSound *sound = &resident[i];
        if (sound->entry == NULL) {
            empty = sound;
        } else if ((oldest == NULL || sound->last_used < oldest->last_used) && !sound_in_use(sound)) {
            oldest = sound;
        }
    }
    if (empty && bytes <= LIVE_PCM_BUDGET - resident_bytes) return empty;
    if (oldest == NULL) return NULL;
    release_sound(oldest);
    cache_evictions++;
    return oldest;
}

static int load_sound(const M3dsAudioIndexEntry *entry) {
    for (size_t i = 0; i < M3DS_AUDIO_MAX_CLIPS; i++) {
        ResidentSound *sound = &resident[i];
        if (sound->entry == entry) {
            sound->last_used = ++sequence;
            cache_hits++;
            return sound->clip;
        }
    }
    if (entry->frames == 0 || entry->frames > M3DS_AUDIO_MAX_CLIP_SAMPLES ||
            (entry->channels != 1 && entry->channels != 2)) return M3DS_AUDIO_INVALID_CLIP;
    size_t bytes = entry->frames * entry->channels * sizeof(int16_t);
    ResidentSound *slot;
    m3ds_audio_mixer_update(&mixer);
    do {
        slot = find_room(bytes);
        if (slot == NULL) return M3DS_AUDIO_INVALID_CLIP;
    } while (bytes > LIVE_PCM_BUDGET - resident_bytes);

    int16_t *samples = malloc(bytes);
    size_t read_bytes = 0;
    if (samples == NULL ||
            !m3ds_audio_read_file_stdio(entry->path, samples, bytes, &read_bytes, NULL) ||
            read_bytes != bytes) {
        free(samples);
        return M3DS_AUDIO_INVALID_CLIP;
    }
    M3dsAudioClipDesc desc = {samples, entry->frames, entry->rate, entry->channels};
    int clip = m3ds_audio_mixer_register_clip(&mixer, &desc);
    if (clip == M3DS_AUDIO_INVALID_CLIP) {
        free(samples);
        return clip;
    }
    *slot = (ResidentSound){entry, samples, clip, bytes, ++sequence};
    resident_bytes += bytes;
    cache_loads++;
    return clip;
}

int32_t m3ds_live_audio_open(void) {
    M3dsAudioBackend backend;
    m3ds_ndsp_backend_init(&backend, &backend_context);
    m3ds_audio_mixer_init(&mixer, &backend);
    log_audio("UPSTREAM_LIVE_AUDIO_OPEN sounds=%u clips=%u pcmBytes=0 state=%s rc=0x%08lx\n",
        M3DS_STOCK_SOUND_COUNT, M3DS_AUDIO_CLIP_COUNT,
        m3ds_audio_state_name(m3ds_audio_mixer_state(&mixer)),
        (unsigned long)m3ds_audio_mixer_status_code(&mixer));
    return 1;
}

int32_t m3ds_live_audio_play(int32_t id, float volume, float pitch, float pan, int32_t loop) {
    if ((uint32_t)id >= M3DS_STOCK_SOUND_COUNT || m3ds_sound_to_clip[id] < 0) return 0;
    unsigned loads_before = cache_loads;
    int clip = load_sound(&m3ds_audio_index[m3ds_sound_to_clip[id]]);
    if (clip == M3DS_AUDIO_INVALID_CLIP) return 0;
    M3dsAudioPlayParams params = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
    params.volume = volume;
    params.pitch = pitch;
    params.pan = pan;
    params.looping = loop != 0;
    uint32_t voice = m3ds_audio_mixer_play(&mixer, clip, &params);
    play_requests++;
    if (cache_loads != loads_before) {
        log_audio("UPSTREAM_LIVE_AUDIO_PLAY id=%ld volume=%.3f pitch=%.3f pan=%.3f voice=%lu pcmBytes=%u\n",
            (long)id, (double)volume, (double)pitch, (double)pan, (unsigned long)voice,
            (unsigned)resident_bytes);
    }
    return (int32_t)voice;
}

int32_t m3ds_live_audio_is_active(int32_t voice) {
    return m3ds_audio_mixer_voice_is_active(&mixer, (uint32_t)voice);
}

void m3ds_live_audio_stop(int32_t voice) {
    m3ds_audio_mixer_stop(&mixer, (uint32_t)voice);
}

void m3ds_live_audio_set_pitch(int32_t voice, float pitch) {
    m3ds_audio_mixer_set_voice_pitch(&mixer, (uint32_t)voice, pitch);
}

void m3ds_live_audio_set_paused(int32_t voice, int32_t paused) {
    m3ds_audio_mixer_set_voice_paused(&mixer, (uint32_t)voice, paused != 0);
}

void m3ds_live_audio_set_mix(int32_t voice, float pan, float volume) {
    m3ds_audio_mixer_set_voice_mix(&mixer, (uint32_t)voice, volume, pan);
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
    log_audio("UPSTREAM_LIVE_AUDIO_CACHE loads=%u hits=%u evictions=%u pcmBytes=%u\n",
        cache_loads, cache_hits, cache_evictions, (unsigned)resident_bytes);
    m3ds_live_audio_reset();
    for (size_t i = 0; i < M3DS_AUDIO_MAX_CLIPS; i++) {
        if (resident[i].entry != NULL) release_sound(&resident[i]);
    }
    m3ds_audio_mixer_shutdown(&mixer);
    sequence = 0;
    cache_hits = cache_loads = cache_evictions = play_requests = 0;
}

#else
int32_t m3ds_live_audio_open(void) { return 0; }
int32_t m3ds_live_audio_play(int32_t id, float volume, float pitch, float pan, int32_t loop) {
    (void)id; (void)volume; (void)pitch; (void)pan; (void)loop;
    return 0;
}
int32_t m3ds_live_audio_is_active(int32_t voice) { (void)voice; return 0; }
void m3ds_live_audio_stop(int32_t voice) { (void)voice; }
void m3ds_live_audio_set_pitch(int32_t voice, float pitch) { (void)voice; (void)pitch; }
void m3ds_live_audio_set_paused(int32_t voice, int32_t paused) { (void)voice; (void)paused; }
void m3ds_live_audio_set_mix(int32_t voice, float pan, float volume) {
    (void)voice; (void)pan; (void)volume;
}
void m3ds_live_audio_update(void) {}
void m3ds_live_audio_reset(void) {}
void m3ds_live_audio_close(void) {}
#endif
