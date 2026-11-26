#ifndef M3DS_CAMPAIGN_SKYBOX_H
#define M3DS_CAMPAIGN_SKYBOX_H

#include <stdint.h>

enum M3dsSkyboxFace {
    M3DS_SKYBOX_RIGHT, M3DS_SKYBOX_LEFT, M3DS_SKYBOX_TOP,
    M3DS_SKYBOX_BOTTOM, M3DS_SKYBOX_FRONT, M3DS_SKYBOX_BACK,
    M3DS_SKYBOX_FACE_COUNT
};

enum { M3DS_SKYBOX_FACE_SIZE = 1024 };

typedef struct {
    float x, y, z;
} M3dsSkyboxRay;

/* Normalization is unnecessary. The camera must supply a finite, nonzero direction. */
uint32_t m3ds_skybox_texel(M3dsSkyboxRay ray);

#endif
