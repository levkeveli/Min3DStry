#include "m3ds_live_audio.h"

#ifdef M3DS_LIVE_AUDIO
#include "ndsp_backend.h"
#include "ndsp_stream_backend.h"
#include "sound-index.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

#ifdef M3DS_LIVE_MUSIC
#include "tremor_stream_source.h"
static M3dsAudioStream music;
static M3dsNdspStreamContext music_backend;
static M3dsTremorStreamSource music_decoder;
static int32_t music_track = -1;
static bool music_checked[M3DS_AUDIO_MUSIC_COUNT];
#endif

#define LIVE_PCM_BUDGET (640u * 1024u)
#define LIVE_STREAM_LANES 4u
#define STREAM_VOICE_FLAG (1u << 30)

typedef struct ResidentSound {
    const M3dsAudioIndexEntry *entry;
    int16_t *samples;
    int clip;
    size_t bytes;
    uint64_t last_used;
} ResidentSound;

typedef struct LiveSoundStream {
    M3dsAudioStream audio;
    M3dsNdspStreamContext backend;
    M3dsAudioStdioStreamSource file;
    int32_t voice;
} LiveSoundStream;

static M3dsAudioMixer mixer;
static M3dsNdspContext backend_context;
static LiveSoundStream streams[LIVE_STREAM_LANES];
static bool reported_stream[M3DS_STOCK_SOUND_COUNT];
static uint32_t stream_voice_serial;
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

#ifdef M3DS_LIVE_MUSIC
void m3ds_live_music_play(int32_t track, float pan, float volume, int32_t loop) {
    if ((uint32_t)track >= M3DS_AUDIO_MUSIC_COUNT) return;
    M3dsAudioBackendState state = m3ds_audio_stream_state(&music);
    if (state != M3DS_AUDIO_STATE_READY && state != M3DS_AUDIO_STATE_FALLBACK_NO_DSP) return;
    if (state == M3DS_AUDIO_STATE_FALLBACK_NO_DSP && music_checked[track]) return;
    if (music_track == track && m3ds_audio_stream_is_playing(&music) &&
            m3ds_audio_stream_is_paused(&music)) {
        m3ds_audio_stream_set_paused(&music, false);
        return;
    }
    m3ds_audio_stream_stop(&music);
    music_track = -1;
    M3dsAudioStreamFormat format;
    M3dsAudioStreamSource source;
    if (!m3ds_tremor_stream_open_music(&music_decoder, (M3dsUpstreamMusic)track, &format, &source)) {
        log_audio("UPSTREAM_LIVE_MUSIC_ERROR id=%ld stage=open\n", (long)track);
        if (state == M3DS_AUDIO_STATE_FALLBACK_NO_DSP) music_checked[track] = true;
        return;
    }
    uint32_t frames = 0;
    if (state == M3DS_AUDIO_STATE_FALLBACK_NO_DSP) {
        // Verify the real ROMFS asset and decoder without claiming a playing voice.
        bool decoded = source.read_frames(source.ctx, music.scratch_pcm, M3DS_AUDIO_STREAM_CHUNK_FRAMES, &frames);
        source.close(source.ctx);
        music_checked[track] = true;
        if (!decoded || frames == 0) {
            log_audio("UPSTREAM_LIVE_MUSIC_ERROR id=%ld stage=decode\n", (long)track);
            return;
        }
    } else {
        m3ds_audio_stream_set_mix(&music, volume, pan);
        m3ds_audio_stream_set_paused(&music, false);
        uint64_t before = music.stats.frames_submitted;
        if (!m3ds_audio_stream_start(&music, &format, &source, loop != 0)) {
            log_audio("UPSTREAM_LIVE_MUSIC_ERROR id=%ld stage=queue\n", (long)track);
            return;
        }
        frames = (uint32_t)(music.stats.frames_submitted - before);
        music_track = track;
    }
    log_audio("UPSTREAM_LIVE_MUSIC_START id=%ld name=%s rate=%lu channels=%u frames=%u mode=%s volume=%.3f loop=%ld\n",
        (long)track, m3ds_audio_music_name((M3dsUpstreamMusic)track),
        (unsigned long)format.sample_rate, (unsigned)format.channels, (unsigned)frames,
        state == M3DS_AUDIO_STATE_READY ? "ndsp" : "decode-only", (double)volume, (long)loop);
}

void m3ds_live_music_stop(int32_t track) {
    if (track < 0 || music_track != track) return;
    m3ds_audio_stream_stop(&music);
    music_track = -1;
}
int32_t m3ds_live_music_is_playing(int32_t track) {
    return track >= 0 && music_track == track &&
        m3ds_audio_stream_is_playing(&music) && !m3ds_audio_stream_is_paused(&music);
}
void m3ds_live_music_pause(int32_t track, int32_t paused) {
    if (track >= 0 && music_track == track) m3ds_audio_stream_set_paused(&music, paused != 0);
}
void m3ds_live_music_set_mix(int32_t track, float pan, float volume) {
    if (track >= 0 && music_track == track) m3ds_audio_stream_set_mix(&music, volume, pan);
}
void m3ds_live_music_set_looping(int32_t track, int32_t loop) {
    if (track >= 0 && music_track == track) m3ds_audio_stream_set_looping(&music, loop != 0);
}
float m3ds_live_music_position(int32_t track) {
    return track >= 0 && music_track == track ? (float)m3ds_audio_stream_position_seconds(&music) : 0.f;
}
#endif

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

static LiveSoundStream *find_stream_voice(int32_t voice) {
    if (voice < (int32_t)STREAM_VOICE_FLAG) return NULL;
    for (size_t i = 0; i < LIVE_STREAM_LANES; i++) {
        LiveSoundStream *stream = &streams[i];
        if (stream->voice == voice && m3ds_audio_stream_is_playing(&stream->audio)) return stream;
    }
    return NULL;
}

static int32_t play_stream(int32_t id, const M3dsAudioIndexEntry *entry,
                           float volume, float pitch, float pan, bool loop) {
    LiveSoundStream *slot = NULL;
    for (size_t i = 0; i < LIVE_STREAM_LANES; i++) {
        M3dsAudioBackendState state = m3ds_audio_stream_state(&streams[i].audio);
        if ((state == M3DS_AUDIO_STATE_READY || state == M3DS_AUDIO_STATE_FALLBACK_NO_DSP) &&
                !m3ds_audio_stream_is_playing(&streams[i].audio)) {
            slot = &streams[i];
            break;
        }
    }
    if (slot == NULL) return 0;
    M3dsAudioBackendState state = m3ds_audio_stream_state(&slot->audio);
    if (state == M3DS_AUDIO_STATE_FALLBACK_NO_DSP && reported_stream[id]) return 0;
    M3dsAudioStreamSource source;
    if (!m3ds_audio_stream_source_open_stdio(&slot->file, entry->path, entry->channels, &source)) return 0;
    size_t bytes = (size_t)entry->frames * entry->channels * sizeof(int16_t);
    FILE *file = slot->file.file;
    if (fseek(file, 0L, SEEK_END) != 0 || ftell(file) != (long)bytes || !source.rewind(source.ctx)) {
        source.close(source.ctx);
        return 0;
    }

    m3ds_audio_stream_set_mix(&slot->audio, volume, pan);
    m3ds_audio_stream_set_pitch(&slot->audio, pitch);
    m3ds_audio_stream_set_paused(&slot->audio, false);
    M3dsAudioStreamFormat format = {entry->rate, entry->channels};
    bool started = m3ds_audio_stream_start(&slot->audio, &format, &source, loop);
    slot->voice = 0;
    if (started) {
        int32_t voice;
        do {
            stream_voice_serial = (stream_voice_serial + 1u) & (STREAM_VOICE_FLAG - 1u);
            voice = (int32_t)(STREAM_VOICE_FLAG | stream_voice_serial);
        } while (find_stream_voice(voice) != NULL);
        slot->voice = voice;
    }
    if (!reported_stream[id]) {
        reported_stream[id] = true;
        log_audio("UPSTREAM_LIVE_STREAM_PLAY id=%ld volume=%.3f pitch=%.3f pan=%.3f voice=%ld frames=%u\n",
            (long)id, (double)volume, (double)pitch, (double)pan, (long)slot->voice, entry->frames);
    }
    return slot->voice;
}

int32_t m3ds_live_audio_open(void) {
    M3dsAudioBackend backend;
    m3ds_ndsp_backend_init(&backend, &backend_context);
    m3ds_audio_mixer_init(&mixer, &backend);
    int32_t capabilities = 1;
#ifdef M3DS_LIVE_MUSIC
    M3dsAudioStreamBackend soundtrack_backend;
    m3ds_ndsp_stream_backend_init(&soundtrack_backend, &music_backend, &backend_context);
    m3ds_audio_stream_init(&music, &soundtrack_backend);
    capabilities |= 2;
#endif
    for (size_t i = 0; i < LIVE_STREAM_LANES; i++) {
        M3dsAudioStreamBackend stream_backend;
        m3ds_ndsp_stream_backend_init_on_channel(&stream_backend, &streams[i].backend,
            &backend_context, M3DS_AUDIO_STREAM_NDSP_CHANNEL + 1u + (uint32_t)i);
        m3ds_audio_stream_init(&streams[i].audio, &stream_backend);
    }
    log_audio("UPSTREAM_LIVE_AUDIO_OPEN sounds=%u clips=%u pcmBytes=0 state=%s rc=0x%08lx\n",
        M3DS_STOCK_SOUND_COUNT, M3DS_STOCK_SHORT_COUNT,
        m3ds_audio_state_name(m3ds_audio_mixer_state(&mixer)),
        (unsigned long)m3ds_audio_mixer_status_code(&mixer));
    return capabilities;
}

int32_t m3ds_live_audio_play(int32_t id, float volume, float pitch, float pan, int32_t loop) {
    if ((uint32_t)id >= M3DS_STOCK_SOUND_COUNT) return 0;
    const M3dsAudioIndexEntry *entry = &m3ds_audio_index[id];
    if (entry->frames > M3DS_AUDIO_MAX_CLIP_SAMPLES) {
        return play_stream(id, entry, volume, pitch, pan, loop != 0);
    }
    unsigned loads_before = cache_loads;
    int clip = load_sound(entry);
    if (clip == M3DS_AUDIO_INVALID_CLIP) return 0;
    M3dsAudioPlayParams params = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
    params.volume = volume;
    params.pitch = pitch;
    params.pan = pan;
    params.looping = loop != 0;
    while (mixer.next_handle >= STREAM_VOICE_FLAG ||
            m3ds_audio_mixer_voice_is_active(&mixer, mixer.next_handle)) {
        mixer.next_handle = mixer.next_handle >= STREAM_VOICE_FLAG ? 1u : mixer.next_handle + 1u;
    }
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
    return find_stream_voice(voice) != NULL || m3ds_audio_mixer_voice_is_active(&mixer, (uint32_t)voice);
}

void m3ds_live_audio_stop(int32_t voice) {
    LiveSoundStream *stream = find_stream_voice(voice);
    if (stream) m3ds_audio_stream_stop(&stream->audio);
    else m3ds_audio_mixer_stop(&mixer, (uint32_t)voice);
}

void m3ds_live_audio_set_pitch(int32_t voice, float pitch) {
    LiveSoundStream *stream = find_stream_voice(voice);
    if (stream) m3ds_audio_stream_set_pitch(&stream->audio, pitch);
    else m3ds_audio_mixer_set_voice_pitch(&mixer, (uint32_t)voice, pitch);
}

void m3ds_live_audio_set_paused(int32_t voice, int32_t paused) {
    LiveSoundStream *stream = find_stream_voice(voice);
    if (stream) m3ds_audio_stream_set_paused(&stream->audio, paused != 0);
    else m3ds_audio_mixer_set_voice_paused(&mixer, (uint32_t)voice, paused != 0);
}

void m3ds_live_audio_set_mix(int32_t voice, float pan, float volume) {
    LiveSoundStream *stream = find_stream_voice(voice);
    if (stream) m3ds_audio_stream_set_mix(&stream->audio, volume, pan);
    else m3ds_audio_mixer_set_voice_mix(&mixer, (uint32_t)voice, volume, pan);
}

void m3ds_live_audio_update(void) {
    m3ds_audio_mixer_update(&mixer);
    for (size_t i = 0; i < LIVE_STREAM_LANES; i++) m3ds_audio_stream_update(&streams[i].audio);
#ifdef M3DS_LIVE_MUSIC
    m3ds_audio_stream_update(&music);
#endif
}

void m3ds_live_audio_reset(void) {
    m3ds_audio_mixer_stop_all(&mixer);
    for (size_t i = 0; i < LIVE_STREAM_LANES; i++) m3ds_audio_stream_stop(&streams[i].audio);
#ifdef M3DS_LIVE_MUSIC
    m3ds_audio_stream_stop(&music);
    music_track = -1;
#endif
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
    for (size_t i = 0; i < LIVE_STREAM_LANES; i++) m3ds_audio_stream_shutdown(&streams[i].audio);
#ifdef M3DS_LIVE_MUSIC
    m3ds_audio_stream_shutdown(&music);
    for (size_t i = 0; i < M3DS_AUDIO_MUSIC_COUNT; i++) music_checked[i] = false;
    log_audio("UPSTREAM_LIVE_MUSIC_CLOSE track=%ld decoderOpen=%d\n", (long)music_track, music_decoder.vf_open);
#endif
    for (size_t i = 0; i < M3DS_STOCK_SOUND_COUNT; i++) reported_stream[i] = false;
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

#ifndef M3DS_LIVE_MUSIC
void m3ds_live_music_play(int32_t track, float pan, float volume, int32_t loop) {
    (void)track; (void)pan; (void)volume; (void)loop;
}
void m3ds_live_music_stop(int32_t track) { (void)track; }
int32_t m3ds_live_music_is_playing(int32_t track) { (void)track; return 0; }
void m3ds_live_music_pause(int32_t track, int32_t paused) { (void)track; (void)paused; }
void m3ds_live_music_set_mix(int32_t track, float pan, float volume) {
    (void)track; (void)pan; (void)volume;
}
void m3ds_live_music_set_looping(int32_t track, int32_t loop) { (void)track; (void)loop; }
float m3ds_live_music_position(int32_t track) { (void)track; return 0.f; }
#endif
