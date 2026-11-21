#include "tremor_stream_source.h"

#include <limits.h>
#include <stdio.h>
#include <string.h>

static int32_t m3ds_tremor_read_pcm_cb(
    void *raw_ctx,
    uint8_t *dst_bytes,
    uint32_t max_bytes,
    int *out_bitstream) {
    M3dsTremorStreamSource *src = (M3dsTremorStreamSource *)raw_ctx;
    if (!src || !src->vf_open || !out_bitstream || (max_bytes > 0u && !dst_bytes)) {
        return -1;
    }
    if (max_bytes == 0u) {
        return 0;
    }

    uint32_t chunk = max_bytes > (uint32_t)INT_MAX ? (uint32_t)INT_MAX : max_bytes;
    long rc = ov_read(&src->vf, (char *)dst_bytes, (int)chunk, out_bitstream);
    if (rc < (long)INT32_MIN) {
        return -1;
    }
    return (int32_t)rc;
}

static bool m3ds_tremor_seek_zero_cb(void *raw_ctx) {
    M3dsTremorStreamSource *src = (M3dsTremorStreamSource *)raw_ctx;
    if (!src || !src->vf_open) {
        return false;
    }
    return ov_pcm_seek(&src->vf, 0) == 0;
}

static void m3ds_tremor_close_cb(void *raw_ctx) {
    M3dsTremorStreamSource *src = (M3dsTremorStreamSource *)raw_ctx;
    if (src && src->vf_open) {
        ov_clear(&src->vf);
        src->vf_open = false;
    }
}

bool m3ds_tremor_stream_open_file(
    M3dsTremorStreamSource *tremor_src,
    const char *path,
    M3dsAudioStreamFormat *out_format,
    M3dsAudioStreamSource *out_source) {
    if (!tremor_src || !path || path[0] == '\0' || !out_format || !out_source) {
        return false;
    }

    FILE *fp = fopen(path, "rb");
    if (!fp) {
        return false;
    }

    memset(tremor_src, 0, sizeof(*tremor_src));
    if (ov_open(fp, &tremor_src->vf, NULL, 0) != 0) {
        fclose(fp);
        memset(tremor_src, 0, sizeof(*tremor_src));
        return false;
    }
    tremor_src->vf_open = true;

    vorbis_info *vi = ov_info(&tremor_src->vf, -1);
    if (!vi ||
        (vi->channels != 1 && vi->channels != (int)M3DS_AUDIO_STREAM_MAX_CHANNELS) ||
        vi->rate < 4000L || vi->rate > 48000L) {
        ov_clear(&tremor_src->vf);
        memset(tremor_src, 0, sizeof(*tremor_src));
        return false;
    }

    M3dsAudioStreamFormat fmt = {
        (uint32_t)vi->rate,
        (uint8_t)vi->channels,
    };
    M3dsVorbisDecoderOps ops = {
        tremor_src,
        m3ds_tremor_read_pcm_cb,
        m3ds_tremor_seek_zero_cb,
        m3ds_tremor_close_cb,
    };
    if (!m3ds_audio_vorbis_stream_init(&tremor_src->vorbis, &ops, fmt, out_source)) {
        ov_clear(&tremor_src->vf);
        memset(tremor_src, 0, sizeof(*tremor_src));
        return false;
    }

    *out_format = fmt;
    return true;
}

bool m3ds_tremor_stream_open_music(
    M3dsTremorStreamSource *tremor_src,
    M3dsUpstreamMusic track,
    M3dsAudioStreamFormat *out_format,
    M3dsAudioStreamSource *out_source) {
    const char *path = m3ds_audio_vorbis_music_romfs_path(track);
    if (!path) {
        return false;
    }
    return m3ds_tremor_stream_open_file(tremor_src, path, out_format, out_source);
}
