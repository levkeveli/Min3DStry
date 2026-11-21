#ifndef MIN3DSTRY_AUDIO_AUDIO_STREAM_H
#define MIN3DSTRY_AUDIO_AUDIO_STREAM_H

#include "audio/audio_mixer.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_AUDIO_STREAM_BUFFER_COUNT 2u
#define M3DS_AUDIO_STREAM_CHUNK_FRAMES 4096u
#define M3DS_AUDIO_STREAM_MAX_CHANNELS 2u
#define M3DS_AUDIO_STREAM_MAX_CHUNK_BYTES \
    (M3DS_AUDIO_STREAM_CHUNK_FRAMES * M3DS_AUDIO_STREAM_MAX_CHANNELS * sizeof(int16_t))
/* Dedicated hardware channel above the 8 SFX mixer channels (0..7). */
#define M3DS_AUDIO_STREAM_NDSP_CHANNEL 8u

/* Pinned Mindustry v160.8 (19a0f40d44514f4802a8b79770456e7e32e34da0) Musics.load() order. */
typedef enum M3dsUpstreamMusic {
    M3DS_AUDIO_MUSIC_BOSS1 = 0,
    M3DS_AUDIO_MUSIC_BOSS2 = 1,
    M3DS_AUDIO_MUSIC_EDITOR = 2,
    M3DS_AUDIO_MUSIC_FINE = 3,
    M3DS_AUDIO_MUSIC_GAME1 = 4,
    M3DS_AUDIO_MUSIC_GAME2 = 5,
    M3DS_AUDIO_MUSIC_GAME3 = 6,
    M3DS_AUDIO_MUSIC_GAME4 = 7,
    M3DS_AUDIO_MUSIC_GAME5 = 8,
    M3DS_AUDIO_MUSIC_GAME6 = 9,
    M3DS_AUDIO_MUSIC_GAME7 = 10,
    M3DS_AUDIO_MUSIC_GAME8 = 11,
    M3DS_AUDIO_MUSIC_GAME9 = 12,
    M3DS_AUDIO_MUSIC_LAND = 13,
    M3DS_AUDIO_MUSIC_LAUNCH = 14,
    M3DS_AUDIO_MUSIC_MENU = 15,
    M3DS_AUDIO_MUSIC_COUNT = 16
} M3dsUpstreamMusic;

const char *m3ds_audio_music_name(M3dsUpstreamMusic track);
const char *m3ds_audio_music_upstream_path(M3dsUpstreamMusic track);
const char *m3ds_audio_music_romfs_path(M3dsUpstreamMusic track);
bool m3ds_audio_music_from_name(const char *name, M3dsUpstreamMusic *out_track);

typedef struct M3dsAudioStreamFormat {
    uint32_t sample_rate;
    uint8_t channels;
} M3dsAudioStreamFormat;

/*
 * Pull-based PCM16 frame source. read_frames() writes up to max_frames
 * interleaved PCM16 samples into out_samples, sets *out_frames = 0 at EOF,
 * and returns false only on I/O or decode failure.
 */
typedef struct M3dsAudioStreamSource {
    void *ctx;
    bool (*read_frames)(
        void *ctx,
        int16_t *out_samples,
        uint32_t max_frames,
        uint32_t *out_frames);
    bool (*rewind)(void *ctx);
    void (*close)(void *ctx);
} M3dsAudioStreamSource;

typedef struct M3dsAudioStdioStreamSource {
    FILE *file;
    uint8_t channels;
} M3dsAudioStdioStreamSource;

/*
 * Opens a raw headerless PCM16 little-endian file for pull streaming (host paths
 * or libctru "romfs:/..." paths after romfsInit()). Caller keeps stdio_src alive
 * while the stream uses out_source.
 */
bool m3ds_audio_stream_source_open_stdio(
    M3dsAudioStdioStreamSource *stdio_src,
    const char *path,
    uint8_t channels,
    M3dsAudioStreamSource *out_source);

typedef struct M3dsAudioStreamBackend {
    void *ctx;
    const char *name;
    M3dsAudioBackendState (*init)(void *ctx, uint32_t *out_status_code);
    void (*shutdown)(void *ctx);
    bool (*configure)(
        void *ctx,
        uint32_t sample_rate,
        uint8_t channels,
        float gain_left,
        float gain_right);
    bool (*buffer_is_free)(void *ctx, uint32_t slot);
    bool (*buffer_submit)(
        void *ctx,
        uint32_t slot,
        const int16_t *samples,
        uint32_t frame_count);
    void (*set_mix)(void *ctx, float gain_left, float gain_right);
    void (*set_paused)(void *ctx, bool paused);
    void (*stop)(void *ctx);
} M3dsAudioStreamBackend;

typedef struct M3dsAudioStreamStats {
    uint64_t frames_submitted;
    uint64_t frames_completed;
    uint32_t chunks_submitted;
    uint32_t chunks_completed;
    uint32_t loops_completed;
    uint32_t tracks_completed;
    uint32_t underruns;
    uint32_t read_errors;
    uint32_t dropped_starts;
} M3dsAudioStreamStats;

typedef struct M3dsAudioStream {
    M3dsAudioStreamBackend backend;
    bool has_backend;
    M3dsAudioBackendState state;
    uint32_t status_code;
    M3dsAudioStreamFormat format;
    M3dsAudioStreamSource source;
    bool playing;
    bool paused;
    bool muted;
    bool looping;
    bool eof;
    float volume;
    float master_volume;
    bool slot_in_flight[M3DS_AUDIO_STREAM_BUFFER_COUNT];
    uint32_t slot_frames[M3DS_AUDIO_STREAM_BUFFER_COUNT];
    uint32_t next_slot;
    uint64_t track_frames_completed;
    M3dsAudioStreamStats stats;
    int16_t scratch_pcm[M3DS_AUDIO_STREAM_CHUNK_FRAMES * M3DS_AUDIO_STREAM_MAX_CHANNELS];
} M3dsAudioStream;

bool m3ds_audio_stream_init(
    M3dsAudioStream *stream,
    const M3dsAudioStreamBackend *backend);
void m3ds_audio_stream_shutdown(M3dsAudioStream *stream);

M3dsAudioBackendState m3ds_audio_stream_state(const M3dsAudioStream *stream);
uint32_t m3ds_audio_stream_status_code(const M3dsAudioStream *stream);

bool m3ds_audio_stream_start(
    M3dsAudioStream *stream,
    const M3dsAudioStreamFormat *format,
    const M3dsAudioStreamSource *source,
    bool looping);
void m3ds_audio_stream_stop(M3dsAudioStream *stream);
void m3ds_audio_stream_update(M3dsAudioStream *stream);

void m3ds_audio_stream_set_volume(M3dsAudioStream *stream, float volume);
float m3ds_audio_stream_volume(const M3dsAudioStream *stream);
void m3ds_audio_stream_set_master_volume(M3dsAudioStream *stream, float volume);
float m3ds_audio_stream_master_volume(const M3dsAudioStream *stream);

void m3ds_audio_stream_set_muted(M3dsAudioStream *stream, bool muted);
bool m3ds_audio_stream_is_muted(const M3dsAudioStream *stream);
void m3ds_audio_stream_set_paused(M3dsAudioStream *stream, bool paused);
bool m3ds_audio_stream_is_paused(const M3dsAudioStream *stream);
void m3ds_audio_stream_set_looping(M3dsAudioStream *stream, bool looping);
bool m3ds_audio_stream_is_looping(const M3dsAudioStream *stream);
bool m3ds_audio_stream_is_playing(const M3dsAudioStream *stream);

uint64_t m3ds_audio_stream_position_frames(const M3dsAudioStream *stream);
double m3ds_audio_stream_position_seconds(const M3dsAudioStream *stream);
void m3ds_audio_stream_get_stats(
    const M3dsAudioStream *stream,
    M3dsAudioStreamStats *out_stats);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_AUDIO_AUDIO_STREAM_H */
