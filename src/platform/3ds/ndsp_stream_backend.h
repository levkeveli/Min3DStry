#ifndef MIN3DSTRY_PLATFORM_3DS_NDSP_STREAM_BACKEND_H
#define MIN3DSTRY_PLATFORM_3DS_NDSP_STREAM_BACKEND_H

#include <3ds.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "audio/audio_stream.h"
#include "ndsp_backend.h"

typedef struct M3dsNdspStreamContext {
    const M3dsNdspContext *ndsp_ctx;
    bool ready;
    uint32_t channel;
    uint8_t channels;
    int16_t *linear_bufs[M3DS_AUDIO_STREAM_BUFFER_COUNT];
    ndspWaveBuf wavebufs[M3DS_AUDIO_STREAM_BUFFER_COUNT];
} M3dsNdspStreamContext;

/*
 * Configures a libctru ping-pong NDSP music stream backend borrowing an
 * initialized M3dsNdspContext. Never calls ndspInit() or ndspExit().
 */
void m3ds_ndsp_stream_backend_init(
    M3dsAudioStreamBackend *backend,
    M3dsNdspStreamContext *ctx,
    const M3dsNdspContext *ndsp_ctx);

#endif /* MIN3DSTRY_PLATFORM_3DS_NDSP_STREAM_BACKEND_H */
