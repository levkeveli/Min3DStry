#ifndef MIN3DSTRY_TEST_NDSP_STREAM_BACKEND_H
#define MIN3DSTRY_TEST_NDSP_STREAM_BACKEND_H

#include "audio/audio_stream.h"
#include "ndsp_backend.h"

typedef struct M3dsNdspStreamContext {
    bool pending[M3DS_AUDIO_STREAM_BUFFER_COUNT];
    unsigned next_slot;
    bool advance;
    bool paused;
    float left;
    float right;
    float rate;
    unsigned channels;
} M3dsNdspStreamContext;

void m3ds_ndsp_stream_backend_init_on_channel(
    M3dsAudioStreamBackend *backend, M3dsNdspStreamContext *ctx,
    const M3dsNdspContext *ndsp_ctx, uint32_t channel);

#endif
