#ifndef MIN3DSTRY_PLATFORM_3DS_TREMOR_STREAM_SOURCE_H
#define MIN3DSTRY_PLATFORM_3DS_TREMOR_STREAM_SOURCE_H

#include "audio/audio_vorbis_stream.h"

#include <tremor/ivorbisfile.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * 3DS libvorbisidec (Tremor) decoder owner paired with M3dsVorbisStreamSource.
 * Callers must pass an uninitialized/closed struct, keep it alive at a fixed
 * address in static or heap storage (do not move/copy after ov_open and avoid
 * small ARM11 thread stacks) until M3dsAudioStream closes the source.
 */
typedef struct M3dsTremorStreamSource {
    OggVorbis_File vf;
    bool vf_open;
    M3dsVorbisStreamSource vorbis;
} M3dsTremorStreamSource;

/*
 * Opens a host or libctru romfs:/ OGG Vorbis file via Tremor and binds an
 * M3dsAudioStreamSource + M3dsAudioStreamFormat. Leaves caller structs
 * untouched if arguments are invalid or opening the file fails.
 */
bool m3ds_tremor_stream_open_file(
    M3dsTremorStreamSource *tremor_src,
    const char *path,
    M3dsAudioStreamFormat *out_format,
    M3dsAudioStreamSource *out_source);

/*
 * Opens one of the 16 pinned v160.8 Musics tracks from staged RomFS
 * ("romfs:/audio/music/<name>.ogg").
 */
bool m3ds_tremor_stream_open_music(
    M3dsTremorStreamSource *tremor_src,
    M3dsUpstreamMusic track,
    M3dsAudioStreamFormat *out_format,
    M3dsAudioStreamSource *out_source);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_3DS_TREMOR_STREAM_SOURCE_H */
