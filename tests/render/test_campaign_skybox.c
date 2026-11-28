#include "m3ds_campaign_skybox.h"

#include <assert.h>
#include <stdio.h>

static void expect(M3dsSkyboxRay ray, enum M3dsSkyboxFace face, unsigned x, unsigned y) {
    uint32_t address = m3ds_skybox_texel(ray);
    assert(address / (M3DS_SKYBOX_FACE_SIZE * M3DS_SKYBOX_FACE_SIZE) == (unsigned)face);
    assert(address / M3DS_SKYBOX_FACE_SIZE % M3DS_SKYBOX_FACE_SIZE == y);
    assert(address % M3DS_SKYBOX_FACE_SIZE == x);
}

int main(void) {
    expect((M3dsSkyboxRay){1, 0, 0}, M3DS_SKYBOX_RIGHT, 512, 512);
    expect((M3dsSkyboxRay){-1, 0, 0}, M3DS_SKYBOX_LEFT, 512, 512);
    expect((M3dsSkyboxRay){0, 1, 0}, M3DS_SKYBOX_TOP, 512, 512);
    expect((M3dsSkyboxRay){0, -1, 0}, M3DS_SKYBOX_BOTTOM, 512, 512);
    expect((M3dsSkyboxRay){0, 0, 1}, M3DS_SKYBOX_FRONT, 512, 512);
    expect((M3dsSkyboxRay){0, 0, -1}, M3DS_SKYBOX_BACK, 512, 512);

    expect((M3dsSkyboxRay){1, 0.5f, 0.25f}, M3DS_SKYBOX_RIGHT, 384, 256);
    expect((M3dsSkyboxRay){-1, 0.5f, 0.25f}, M3DS_SKYBOX_LEFT, 640, 256);
    expect((M3dsSkyboxRay){0.5f, 1, 0.25f}, M3DS_SKYBOX_TOP, 768, 640);
    expect((M3dsSkyboxRay){0.5f, -1, 0.25f}, M3DS_SKYBOX_BOTTOM, 768, 384);
    expect((M3dsSkyboxRay){0.5f, 0.25f, 1}, M3DS_SKYBOX_FRONT, 768, 384);
    expect((M3dsSkyboxRay){0.5f, 0.25f, -1}, M3DS_SKYBOX_BACK, 256, 384);
    expect((M3dsSkyboxRay){0.125f, 0.0625f, 0.25f}, M3DS_SKYBOX_FRONT, 768, 384);
    expect((M3dsSkyboxRay){1, -1, -1}, M3DS_SKYBOX_RIGHT, 1023, 1023);
    puts("Stock campaign skybox face lookup passed.");
    return 0;
}
