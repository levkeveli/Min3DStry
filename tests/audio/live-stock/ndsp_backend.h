#ifndef MIN3DSTRY_TEST_NDSP_BACKEND_H
#define MIN3DSTRY_TEST_NDSP_BACKEND_H

#include "audio/audio_mixer.h"

typedef struct M3dsNdspContext {
    size_t uploaded[M3DS_AUDIO_MAX_CLIPS];
    int playing[M3DS_AUDIO_MAX_VOICES];
} M3dsNdspContext;

void m3ds_ndsp_backend_init(M3dsAudioBackend *backend, M3dsNdspContext *ctx);
void svcOutputDebugString(const char *text, int length);

#endif
