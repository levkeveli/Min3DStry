#include "m3ds_campaign_skybox.h"

#include <math.h>

/* OpenGL nearest filtering on clamp-to-edge cube coordinates. Arc uploads
 * each PNG in row order, so t=0 addresses the top row of the original file. */
static unsigned texel_coordinate(float coordinate, float major) {
    unsigned result = (unsigned)((coordinate / major + 1.0f) * (M3DS_SKYBOX_FACE_SIZE / 2));
    return result < M3DS_SKYBOX_FACE_SIZE ? result : M3DS_SKYBOX_FACE_SIZE - 1;
}

uint32_t m3ds_skybox_texel(M3dsSkyboxRay ray) {
    float ax = fabsf(ray.x), ay = fabsf(ray.y), az = fabsf(ray.z);
    enum M3dsSkyboxFace face;
    float s, t, major;
    if (ax >= ay && ax >= az) {
        face = ray.x >= 0 ? M3DS_SKYBOX_RIGHT : M3DS_SKYBOX_LEFT;
        s = ray.x >= 0 ? -ray.z : ray.z;
        t = -ray.y;
        major = ax;
    } else if (ay >= az) {
        face = ray.y >= 0 ? M3DS_SKYBOX_TOP : M3DS_SKYBOX_BOTTOM;
        s = ray.x;
        t = ray.y >= 0 ? ray.z : -ray.z;
        major = ay;
    } else {
        face = ray.z >= 0 ? M3DS_SKYBOX_FRONT : M3DS_SKYBOX_BACK;
        s = ray.z >= 0 ? ray.x : -ray.x;
        t = -ray.y;
        major = az;
    }
    return ((unsigned)face * M3DS_SKYBOX_FACE_SIZE + texel_coordinate(t, major)) * M3DS_SKYBOX_FACE_SIZE
        + texel_coordinate(s, major);
}
