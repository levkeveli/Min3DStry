#ifndef MIN3DSTRY_AUDIO_AUDIO_VORBIS_STREAM_H
#define MIN3DSTRY_AUDIO_AUDIO_VORBIS_STREAM_H

#include "audio/audio_stream.h"

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Pull-based Vorbis PCM16 decoder callbacks matching Tremor (libvorbisidec)
 * ov_read / ov_pcm_seek / ov_clear semantics:
 * - read_pcm writes decoded little-endian interleaved PCM16 bytes up to
 *   max_bytes, sets *out_bitstream, and returns >0 bytes read, 0 at EOF, or <0
 *   on decode/I/O error.
 * - seek_zero is optional; NULL indicates a non-seekable stream.
 * - close is optional and invoked at most once when the stream source closes.
 */
typedef struct M3dsVorbisDecoderOps {
    void *ctx;
    int32_t (*read_pcm)(
        void *ctx,
        uint8_t *dst_bytes,
        uint32_t max_bytes,
        int *out_bitstream);
    bool (*seek_zero)(void *ctx);
    void (*close)(void *ctx);
} M3dsVorbisDecoderOps;

/*
 * Host-testable adapter between a Vorbis decoder backend and M3dsAudioStream.
 * Keep this struct (and any owning decoder wrapper) alive in static/heap storage
 * for the duration of stream playback; avoid placing large stream contexts on
 * small ARM11 thread stacks.
 */
typedef struct M3dsVorbisStreamSource {
    M3dsVorbisDecoderOps ops;
    M3dsAudioStreamFormat format;
    bool open;
    bool has_bitstream;
    int initial_bitstream;
    uint32_t read_calls;
    uint32_t rewind_calls;
    uint32_t bitstream_errors;
    uint32_t decode_errors;
} M3dsVorbisStreamSource;

/*
 * Returns the staged RomFS OGG Vorbis path ("romfs:/audio/music/<name>.ogg")
 * for a pinned v160.8 Musics.load() track index (0..15), or NULL if out of
 * range.
 */
const char *m3ds_audio_vorbis_music_romfs_path(M3dsUpstreamMusic track);

/*
 * Initializes vorbis_src and binds out_source for M3dsAudioStream pull
 * streaming. Leaves caller structs untouched when arguments or format bounds
 * (1 or 2 channels, 4000..48000 Hz) are invalid.
 */
bool m3ds_audio_vorbis_stream_init(
    M3dsVorbisStreamSource *vorbis_src,
    const M3dsVorbisDecoderOps *ops,
    M3dsAudioStreamFormat format,
    M3dsAudioStreamSource *out_source);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_AUDIO_AUDIO_VORBIS_STREAM_H */
