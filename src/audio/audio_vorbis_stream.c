#include "audio/audio_vorbis_stream.h"

#include <string.h>

static const char *const m3ds_vorbis_music_romfs_paths[M3DS_AUDIO_MUSIC_COUNT] = {
    "romfs:/audio/music/boss1.ogg",
    "romfs:/audio/music/boss2.ogg",
    "romfs:/audio/music/editor.ogg",
    "romfs:/audio/music/fine.ogg",
    "romfs:/audio/music/game1.ogg",
    "romfs:/audio/music/game2.ogg",
    "romfs:/audio/music/game3.ogg",
    "romfs:/audio/music/game4.ogg",
    "romfs:/audio/music/game5.ogg",
    "romfs:/audio/music/game6.ogg",
    "romfs:/audio/music/game7.ogg",
    "romfs:/audio/music/game8.ogg",
    "romfs:/audio/music/game9.ogg",
    "romfs:/audio/music/land.ogg",
    "romfs:/audio/music/launch.ogg",
    "romfs:/audio/music/menu.ogg",
};

static bool m3ds_vorbis_format_valid(M3dsAudioStreamFormat format) {
    return (format.channels == 1u ||
            format.channels == M3DS_AUDIO_STREAM_MAX_CHANNELS) &&
           format.sample_rate >= 4000u &&
           format.sample_rate <= 48000u;
}

static bool m3ds_vorbis_read_frames_cb(
    void *raw_ctx,
    int16_t *out_samples,
    uint32_t max_frames,
    uint32_t *out_frames) {
    if (out_frames) {
        *out_frames = 0u;
    }
    M3dsVorbisStreamSource *src = (M3dsVorbisStreamSource *)raw_ctx;
    if (!src || !src->open || !src->ops.read_pcm || !out_frames ||
        (max_frames > 0u && !out_samples) ||
        !m3ds_vorbis_format_valid(src->format)) {
        return false;
    }
    if (max_frames == 0u) {
        return true;
    }

    uint32_t frame_bytes = (uint32_t)src->format.channels * (uint32_t)sizeof(int16_t);
    if (max_frames > UINT32_MAX / frame_bytes) {
        src->decode_errors += 1u;
        return false;
    }

    uint32_t target_bytes = max_frames * frame_bytes;
    uint32_t filled = 0u;
    uint8_t *dst = (uint8_t *)out_samples;

    while (filled < target_bytes) {
        uint32_t remaining = target_bytes - filled;
        int bitstream = 0;
        src->read_calls += 1u;
        int32_t n = src->ops.read_pcm(src->ops.ctx, dst + filled, remaining, &bitstream);
        if (n < 0 || (uint32_t)n > remaining) {
            src->decode_errors += 1u;
            *out_frames = 0u;
            return false;
        }
        if (n == 0) {
            /* Reject mid-frame EOF rather than passing truncated PCM samples downstream. */
            if ((filled % frame_bytes) != 0u) {
                src->decode_errors += 1u;
                *out_frames = 0u;
                return false;
            }
            *out_frames = filled / frame_bytes;
            return true;
        }
        if (!src->has_bitstream) {
            src->has_bitstream = true;
            src->initial_bitstream = bitstream;
        } else if (bitstream != src->initial_bitstream) {
            src->bitstream_errors += 1u;
            *out_frames = 0u;
            return false;
        }
        filled += (uint32_t)n;
    }

    *out_frames = max_frames;
    return true;
}

static bool m3ds_vorbis_rewind_cb(void *raw_ctx) {
    M3dsVorbisStreamSource *src = (M3dsVorbisStreamSource *)raw_ctx;
    if (!src || !src->open || !src->ops.seek_zero) {
        return false;
    }
    if (!src->ops.seek_zero(src->ops.ctx)) {
        return false;
    }
    src->has_bitstream = false;
    src->initial_bitstream = 0;
    src->rewind_calls += 1u;
    return true;
}

static void m3ds_vorbis_close_cb(void *raw_ctx) {
    M3dsVorbisStreamSource *src = (M3dsVorbisStreamSource *)raw_ctx;
    if (!src || !src->open) {
        return;
    }
    src->open = false;
    src->has_bitstream = false;
    src->initial_bitstream = 0;
    if (src->ops.close) {
        src->ops.close(src->ops.ctx);
    }
}

const char *m3ds_audio_vorbis_music_romfs_path(M3dsUpstreamMusic track) {
    if ((unsigned)track >= (unsigned)M3DS_AUDIO_MUSIC_COUNT) {
        return NULL;
    }
    return m3ds_vorbis_music_romfs_paths[track];
}

bool m3ds_audio_vorbis_stream_init(
    M3dsVorbisStreamSource *vorbis_src,
    const M3dsVorbisDecoderOps *ops,
    M3dsAudioStreamFormat format,
    M3dsAudioStreamSource *out_source) {
    if (!vorbis_src || !ops || !ops->read_pcm || !out_source ||
        !m3ds_vorbis_format_valid(format)) {
        return false;
    }

    memset(vorbis_src, 0, sizeof(*vorbis_src));
    memset(out_source, 0, sizeof(*out_source));

    vorbis_src->ops = *ops;
    vorbis_src->format = format;
    vorbis_src->open = true;

    out_source->ctx = vorbis_src;
    out_source->read_frames = m3ds_vorbis_read_frames_cb;
    out_source->rewind = ops->seek_zero ? m3ds_vorbis_rewind_cb : NULL;
    out_source->close = m3ds_vorbis_close_cb;
    return true;
}
