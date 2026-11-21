#include "audio/audio_stream.h"

#include <float.h>
#include <stdio.h>
#include <string.h>

typedef struct M3dsMusicCatalogEntry {
    const char *name;
    const char *upstream_path;
    const char *romfs_path;
} M3dsMusicCatalogEntry;

static const M3dsMusicCatalogEntry m3ds_music_catalog[M3DS_AUDIO_MUSIC_COUNT] = {
    {"boss1", "music/boss1.ogg", "romfs:/audio/music/boss1.pcm"},
    {"boss2", "music/boss2.ogg", "romfs:/audio/music/boss2.pcm"},
    {"editor", "music/editor.ogg", "romfs:/audio/music/editor.pcm"},
    {"fine", "music/fine.ogg", "romfs:/audio/music/fine.pcm"},
    {"game1", "music/game1.ogg", "romfs:/audio/music/game1.pcm"},
    {"game2", "music/game2.ogg", "romfs:/audio/music/game2.pcm"},
    {"game3", "music/game3.ogg", "romfs:/audio/music/game3.pcm"},
    {"game4", "music/game4.ogg", "romfs:/audio/music/game4.pcm"},
    {"game5", "music/game5.ogg", "romfs:/audio/music/game5.pcm"},
    {"game6", "music/game6.ogg", "romfs:/audio/music/game6.pcm"},
    {"game7", "music/game7.ogg", "romfs:/audio/music/game7.pcm"},
    {"game8", "music/game8.ogg", "romfs:/audio/music/game8.pcm"},
    {"game9", "music/game9.ogg", "romfs:/audio/music/game9.pcm"},
    {"land", "music/land.ogg", "romfs:/audio/music/land.pcm"},
    {"launch", "music/launch.ogg", "romfs:/audio/music/launch.pcm"},
    {"menu", "music/menu.ogg", "romfs:/audio/music/menu.pcm"},
};

static bool m3ds_stream_is_finite(float val) {
    return val >= -FLT_MAX && val <= FLT_MAX;
}

static float m3ds_stream_sanitize_gain(float val) {
    if (!m3ds_stream_is_finite(val) || val <= 0.0f) {
        return 0.0f;
    }
    if (val >= 1.0f) {
        return 1.0f;
    }
    return val;
}

static float m3ds_stream_effective_gain(const M3dsAudioStream *stream) {
    if (!stream || stream->muted) {
        return 0.0f;
    }
    return stream->master_volume * stream->volume;
}

static void m3ds_stream_apply_mix(M3dsAudioStream *stream) {
    if (!stream || !stream->playing ||
        stream->state != M3DS_AUDIO_STATE_READY ||
        !stream->backend.set_mix) {
        return;
    }
    float gain = m3ds_stream_effective_gain(stream);
    stream->backend.set_mix(stream->backend.ctx, gain, gain);
}

static void m3ds_stream_close_source(M3dsAudioStreamSource *source) {
    if (source && source->close) {
        source->close(source->ctx);
    }
}

static bool m3ds_stream_stdio_read_frames(
    void *raw_ctx,
    int16_t *out_samples,
    uint32_t max_frames,
    uint32_t *out_frames) {
    M3dsAudioStdioStreamSource *src = (M3dsAudioStdioStreamSource *)raw_ctx;
    if (!src || !src->file || !out_samples || !out_frames ||
        (src->channels != 1u && src->channels != M3DS_AUDIO_STREAM_MAX_CHANNELS)) {
        return false;
    }
    if (max_frames == 0u) {
        *out_frames = 0u;
        return true;
    }
    size_t frame_bytes = (size_t)src->channels * sizeof(int16_t);
    size_t got = fread(out_samples, frame_bytes, (size_t)max_frames, src->file);
    if (ferror(src->file) != 0) {
        *out_frames = 0u;
        return false;
    }
    *out_frames = (uint32_t)got;
    return true;
}

static bool m3ds_stream_stdio_rewind(void *raw_ctx) {
    M3dsAudioStdioStreamSource *src = (M3dsAudioStdioStreamSource *)raw_ctx;
    if (!src || !src->file) {
        return false;
    }
    clearerr(src->file);
    return fseek(src->file, 0L, SEEK_SET) == 0;
}

static void m3ds_stream_stdio_close(void *raw_ctx) {
    M3dsAudioStdioStreamSource *src = (M3dsAudioStdioStreamSource *)raw_ctx;
    if (src && src->file) {
        fclose(src->file);
        src->file = NULL;
    }
}

const char *m3ds_audio_music_name(M3dsUpstreamMusic track) {
    if ((unsigned)track >= (unsigned)M3DS_AUDIO_MUSIC_COUNT) {
        return NULL;
    }
    return m3ds_music_catalog[track].name;
}

const char *m3ds_audio_music_upstream_path(M3dsUpstreamMusic track) {
    if ((unsigned)track >= (unsigned)M3DS_AUDIO_MUSIC_COUNT) {
        return NULL;
    }
    return m3ds_music_catalog[track].upstream_path;
}

const char *m3ds_audio_music_romfs_path(M3dsUpstreamMusic track) {
    if ((unsigned)track >= (unsigned)M3DS_AUDIO_MUSIC_COUNT) {
        return NULL;
    }
    return m3ds_music_catalog[track].romfs_path;
}

bool m3ds_audio_music_from_name(const char *name, M3dsUpstreamMusic *out_track) {
    if (!name) {
        return false;
    }
    for (unsigned int i = 0u; i < (unsigned int)M3DS_AUDIO_MUSIC_COUNT; ++i) {
        if (strcmp(m3ds_music_catalog[i].name, name) == 0) {
            if (out_track) {
                *out_track = (M3dsUpstreamMusic)i;
            }
            return true;
        }
    }
    return false;
}

bool m3ds_audio_stream_source_open_stdio(
    M3dsAudioStdioStreamSource *stdio_src,
    const char *path,
    uint8_t channels,
    M3dsAudioStreamSource *out_source) {
    if (!stdio_src || !path || path[0] == '\0' || !out_source ||
        (channels != 1u && channels != M3DS_AUDIO_STREAM_MAX_CHANNELS)) {
        return false;
    }
    FILE *file = fopen(path, "rb");
    if (!file) {
        return false;
    }
    memset(stdio_src, 0, sizeof(*stdio_src));
    memset(out_source, 0, sizeof(*out_source));
    stdio_src->file = file;
    stdio_src->channels = channels;
    out_source->ctx = stdio_src;
    out_source->read_frames = m3ds_stream_stdio_read_frames;
    out_source->rewind = m3ds_stream_stdio_rewind;
    out_source->close = m3ds_stream_stdio_close;
    return true;
}

static bool m3ds_stream_read_next_chunk(
    M3dsAudioStream *stream,
    uint32_t *out_frames) {
    uint32_t frames = 0u;
    if (!stream->source.read_frames(
            stream->source.ctx,
            stream->scratch_pcm,
            M3DS_AUDIO_STREAM_CHUNK_FRAMES,
            &frames) ||
        frames > M3DS_AUDIO_STREAM_CHUNK_FRAMES) {
        stream->stats.read_errors += 1u;
        m3ds_audio_stream_stop(stream);
        return false;
    }

    if (frames == 0u) {
        if (stream->looping && stream->source.rewind) {
            if (!stream->source.rewind(stream->source.ctx) ||
                !stream->source.read_frames(
                    stream->source.ctx,
                    stream->scratch_pcm,
                    M3DS_AUDIO_STREAM_CHUNK_FRAMES,
                    &frames) ||
                frames > M3DS_AUDIO_STREAM_CHUNK_FRAMES) {
                stream->stats.read_errors += 1u;
                m3ds_audio_stream_stop(stream);
                return false;
            }
            if (frames > 0u) {
                stream->stats.loops_completed += 1u;
            } else {
                stream->eof = true;
            }
        } else {
            stream->eof = true;
        }
    }

    *out_frames = frames;
    return true;
}

static bool m3ds_stream_refill(M3dsAudioStream *stream) {
    while (!stream->eof && !stream->slot_in_flight[stream->next_slot]) {
        uint32_t frames = 0u;
        if (!m3ds_stream_read_next_chunk(stream, &frames)) {
            return false;
        }
        if (frames == 0u) {
            break;
        }

        uint32_t slot = stream->next_slot;
        if (!stream->backend.buffer_submit(
                stream->backend.ctx, slot, stream->scratch_pcm, frames)) {
            stream->stats.read_errors += 1u;
            m3ds_audio_stream_stop(stream);
            return false;
        }

        stream->slot_in_flight[slot] = true;
        stream->slot_frames[slot] = frames;
        stream->stats.chunks_submitted += 1u;
        stream->stats.frames_submitted += frames;
        stream->next_slot = (slot + 1u) % M3DS_AUDIO_STREAM_BUFFER_COUNT;
    }
    return true;
}

bool m3ds_audio_stream_init(
    M3dsAudioStream *stream,
    const M3dsAudioStreamBackend *backend) {
    if (!stream) {
        return false;
    }
    memset(stream, 0, sizeof(*stream));
    stream->volume = 0.7f;
    stream->master_volume = 1.0f;

    if (!backend) {
        stream->state = M3DS_AUDIO_STATE_DISABLED;
        return true;
    }

    stream->backend = *backend;
    stream->has_backend = true;
    if (stream->backend.init) {
        stream->state = stream->backend.init(stream->backend.ctx, &stream->status_code);
    } else {
        stream->state = M3DS_AUDIO_STATE_DISABLED;
    }
    return true;
}

void m3ds_audio_stream_shutdown(M3dsAudioStream *stream) {
    if (!stream || stream->state == M3DS_AUDIO_STATE_UNINITIALIZED) {
        return;
    }
    m3ds_audio_stream_stop(stream);
    if (stream->has_backend && stream->backend.shutdown) {
        stream->backend.shutdown(stream->backend.ctx);
    }
    stream->state = M3DS_AUDIO_STATE_UNINITIALIZED;
}

M3dsAudioBackendState m3ds_audio_stream_state(const M3dsAudioStream *stream) {
    return stream ? stream->state : M3DS_AUDIO_STATE_UNINITIALIZED;
}

uint32_t m3ds_audio_stream_status_code(const M3dsAudioStream *stream) {
    return stream ? stream->status_code : 0u;
}

bool m3ds_audio_stream_start(
    M3dsAudioStream *stream,
    const M3dsAudioStreamFormat *format,
    const M3dsAudioStreamSource *source,
    bool looping) {
    if (!stream) {
        if (source) {
            M3dsAudioStreamSource tmp = *source;
            m3ds_stream_close_source(&tmp);
        }
        return false;
    }

    m3ds_audio_stream_stop(stream);

    if (!format ||
        format->sample_rate < 4000u ||
        format->sample_rate > 48000u ||
        (format->channels != 1u && format->channels != M3DS_AUDIO_STREAM_MAX_CHANNELS) ||
        !source || !source->read_frames) {
        if (source) {
            M3dsAudioStreamSource tmp = *source;
            m3ds_stream_close_source(&tmp);
        }
        return false;
    }

    if (stream->state != M3DS_AUDIO_STATE_READY) {
        M3dsAudioStreamSource tmp = *source;
        m3ds_stream_close_source(&tmp);
        stream->stats.dropped_starts += 1u;
        return false;
    }

    float gain = m3ds_stream_effective_gain(stream);
    if (!stream->backend.configure ||
        !stream->backend.buffer_is_free ||
        !stream->backend.buffer_submit ||
        !stream->backend.configure(
            stream->backend.ctx,
            format->sample_rate,
            format->channels,
            gain,
            gain)) {
        M3dsAudioStreamSource tmp = *source;
        m3ds_stream_close_source(&tmp);
        stream->stats.dropped_starts += 1u;
        return false;
    }

    stream->format = *format;
    stream->source = *source;
    stream->playing = true;
    stream->looping = looping;
    stream->eof = false;
    stream->next_slot = 0u;
    stream->track_frames_completed = 0u;
    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        stream->slot_in_flight[i] = false;
        stream->slot_frames[i] = 0u;
    }

    if (!m3ds_stream_refill(stream)) {
        return false;
    }

    if (!stream->slot_in_flight[0] && !stream->slot_in_flight[1]) {
        m3ds_audio_stream_stop(stream);
        return false;
    }

    if (stream->paused && stream->backend.set_paused) {
        stream->backend.set_paused(stream->backend.ctx, true);
    }
    return true;
}

void m3ds_audio_stream_stop(M3dsAudioStream *stream) {
    if (!stream) {
        return;
    }
    if (stream->playing &&
        stream->state == M3DS_AUDIO_STATE_READY &&
        stream->backend.stop) {
        stream->backend.stop(stream->backend.ctx);
    }
    m3ds_stream_close_source(&stream->source);
    memset(&stream->source, 0, sizeof(stream->source));
    stream->playing = false;
    stream->eof = false;
    stream->next_slot = 0u;
    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        stream->slot_in_flight[i] = false;
        stream->slot_frames[i] = 0u;
    }
}

void m3ds_audio_stream_update(M3dsAudioStream *stream) {
    if (!stream || !stream->playing || stream->paused ||
        stream->state != M3DS_AUDIO_STATE_READY ||
        !stream->backend.buffer_is_free) {
        return;
    }

    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        uint32_t slot = (stream->next_slot + i) % M3DS_AUDIO_STREAM_BUFFER_COUNT;
        if (stream->slot_in_flight[slot] &&
            stream->backend.buffer_is_free(stream->backend.ctx, slot)) {
            stream->slot_in_flight[slot] = false;
            stream->track_frames_completed += stream->slot_frames[slot];
            stream->stats.frames_completed += stream->slot_frames[slot];
            stream->slot_frames[slot] = 0u;
            stream->stats.chunks_completed += 1u;
        }
    }

    bool any_in_flight = false;
    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        if (stream->slot_in_flight[i]) {
            any_in_flight = true;
            break;
        }
    }
    if (!stream->eof && !any_in_flight) {
        stream->stats.underruns += 1u;
    }

    if (!m3ds_stream_refill(stream)) {
        return;
    }

    any_in_flight = false;
    for (uint32_t i = 0u; i < M3DS_AUDIO_STREAM_BUFFER_COUNT; ++i) {
        if (stream->slot_in_flight[i]) {
            any_in_flight = true;
            break;
        }
    }
    if (stream->eof && !any_in_flight) {
        m3ds_stream_close_source(&stream->source);
        memset(&stream->source, 0, sizeof(stream->source));
        stream->playing = false;
        stream->eof = false;
        stream->stats.tracks_completed += 1u;
    }
}

void m3ds_audio_stream_set_volume(M3dsAudioStream *stream, float volume) {
    if (!stream) {
        return;
    }
    stream->volume = m3ds_stream_sanitize_gain(volume);
    m3ds_stream_apply_mix(stream);
}

float m3ds_audio_stream_volume(const M3dsAudioStream *stream) {
    return stream ? stream->volume : 0.0f;
}

void m3ds_audio_stream_set_master_volume(M3dsAudioStream *stream, float volume) {
    if (!stream) {
        return;
    }
    stream->master_volume = m3ds_stream_sanitize_gain(volume);
    m3ds_stream_apply_mix(stream);
}

float m3ds_audio_stream_master_volume(const M3dsAudioStream *stream) {
    return stream ? stream->master_volume : 0.0f;
}

void m3ds_audio_stream_set_muted(M3dsAudioStream *stream, bool muted) {
    if (!stream) {
        return;
    }
    stream->muted = muted;
    m3ds_stream_apply_mix(stream);
}

bool m3ds_audio_stream_is_muted(const M3dsAudioStream *stream) {
    return stream ? stream->muted : false;
}

void m3ds_audio_stream_set_paused(M3dsAudioStream *stream, bool paused) {
    if (!stream) {
        return;
    }
    stream->paused = paused;
    if (stream->playing &&
        stream->state == M3DS_AUDIO_STATE_READY &&
        stream->backend.set_paused) {
        stream->backend.set_paused(stream->backend.ctx, paused);
    }
}

bool m3ds_audio_stream_is_paused(const M3dsAudioStream *stream) {
    return stream ? stream->paused : false;
}

void m3ds_audio_stream_set_looping(M3dsAudioStream *stream, bool looping) {
    if (!stream) {
        return;
    }
    stream->looping = looping;
    if (looping) {
        stream->eof = false;
    }
}

bool m3ds_audio_stream_is_looping(const M3dsAudioStream *stream) {
    return stream ? stream->looping : false;
}

bool m3ds_audio_stream_is_playing(const M3dsAudioStream *stream) {
    return stream ? stream->playing : false;
}

uint64_t m3ds_audio_stream_position_frames(const M3dsAudioStream *stream) {
    return stream ? stream->track_frames_completed : 0u;
}

double m3ds_audio_stream_position_seconds(const M3dsAudioStream *stream) {
    if (!stream || stream->format.sample_rate == 0u) {
        return 0.0;
    }
    return (double)stream->track_frames_completed / (double)stream->format.sample_rate;
}

void m3ds_audio_stream_get_stats(
    const M3dsAudioStream *stream,
    M3dsAudioStreamStats *out_stats) {
    if (!out_stats) {
        return;
    }
    if (!stream) {
        memset(out_stats, 0, sizeof(*out_stats));
        return;
    }
    *out_stats = stream->stats;
}
