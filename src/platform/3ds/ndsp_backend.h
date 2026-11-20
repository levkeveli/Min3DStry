#ifndef MIN3DSTRY_PLATFORM_3DS_NDSP_BACKEND_H
#define MIN3DSTRY_PLATFORM_3DS_NDSP_BACKEND_H

#include <3ds.h>
#include <stdbool.h>
#include <stddef.h>

#include "audio/audio_mixer.h"

typedef struct M3dsNdspClipBuffer {
    int16_t *linear_pcm;
    size_t byte_size;
} M3dsNdspClipBuffer;

typedef struct M3dsNdspContext {
    bool ndsp_ready;
    Result init_result;
    M3dsNdspClipBuffer clips[M3DS_AUDIO_MAX_CLIPS];
    ndspWaveBuf wavebufs[M3DS_AUDIO_MAX_VOICES];
} M3dsNdspContext;

void m3ds_ndsp_backend_init(M3dsAudioBackend *backend, M3dsNdspContext *ctx);

#endif /* MIN3DSTRY_PLATFORM_3DS_NDSP_BACKEND_H */
