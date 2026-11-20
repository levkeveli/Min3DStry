#ifndef MIN3DSTRY_PLATFORM_3DS_CITRO2D_BACKEND_H
#define MIN3DSTRY_PLATFORM_3DS_CITRO2D_BACKEND_H

#include <citro2d.h>

#include "render/scene_2d.h"

typedef struct M3dsCitro2dContext {
    C3D_RenderTarget *target;
    C2D_SpriteSheet sprites;
} M3dsCitro2dContext;

void m3ds_citro2d_backend_init(
    M3dsRenderBackend *backend,
    M3dsCitro2dContext *ctx,
    C3D_RenderTarget *target,
    C2D_SpriteSheet sprites);

#endif /* MIN3DSTRY_PLATFORM_3DS_CITRO2D_BACKEND_H */
