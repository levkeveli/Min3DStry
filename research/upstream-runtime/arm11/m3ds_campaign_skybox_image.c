#include "m3ds_campaign_skybox.h"

#include <math.h>
#include <png.h>
#include <stdio.h>
#include <stdlib.h>

enum {
    viewport_pixels = M3DS_SKYBOX_WIDTH * M3DS_SKYBOX_HEIGHT,
    face_pixels = M3DS_SKYBOX_FACE_SIZE * M3DS_SKYBOX_FACE_SIZE
};

static bool read_face(const char *directory, unsigned face, uint8_t *rgb) {
    static const char *const names[] = {"right", "left", "top", "bottom", "front", "back"};
    char path[256];
    int length = snprintf(path, sizeof(path), "%s/%s.png", directory, names[face]);
    if (length < 0 || (size_t)length >= sizeof(path)) return false;
    png_image image = {0};
    image.version = PNG_IMAGE_VERSION;
    bool ok = png_image_begin_read_from_file(&image, path)
        && image.width == M3DS_SKYBOX_FACE_SIZE && image.height == M3DS_SKYBOX_FACE_SIZE;
    if (ok) {
        image.format = PNG_FORMAT_RGB;
        ok = png_image_finish_read(&image, NULL, rgb, 0, NULL) != 0;
    }
    png_image_free(&image);
    return ok;
}

static M3dsSkyboxRay cross(M3dsSkyboxRay a, M3dsSkyboxRay b) {
    return (M3dsSkyboxRay){a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}

bool m3ds_skybox_render(const char *directory, const float camera[7], uint8_t *rgb) {
    uint32_t *addresses = malloc(viewport_pixels * sizeof(*addresses));
    uint8_t *face_rgb = malloc(face_pixels * 3);
    bool ok = addresses && face_rgb;
    if (ok) {
        const M3dsSkyboxRay forward = {camera[0], camera[1], camera[2]};
        M3dsSkyboxRay right = cross(forward, (M3dsSkyboxRay){camera[3], camera[4], camera[5]});
        float right_length = sqrtf(right.x * right.x + right.y * right.y + right.z * right.z);
        right = (M3dsSkyboxRay){right.x / right_length, right.y / right_length, right.z / right_length};
        const M3dsSkyboxRay up = cross(right, forward);
        unsigned used_faces = 0;
        // Camera view rays originate at the center of the skybox as in PlanetRenderer.
        const float step = 2 * camera[6] / M3DS_SKYBOX_HEIGHT;
        for (int y = 0; y < M3DS_SKYBOX_HEIGHT; y++) {
            float v = (0.5f * (M3DS_SKYBOX_HEIGHT - 1) - y) * step;
            for (int x = 0; x < M3DS_SKYBOX_WIDTH; x++) {
                float u = (x - 0.5f * (M3DS_SKYBOX_WIDTH - 1)) * step;
                M3dsSkyboxRay direction = {forward.x + right.x * u + up.x * v,
                    forward.y + right.y * u + up.y * v, forward.z + right.z * u + up.z * v};
                uint32_t address = m3ds_skybox_texel(direction);
                addresses[y * M3DS_SKYBOX_WIDTH + x] = address;
                used_faces |= 1u << (address / face_pixels);
            }
        }
        for (unsigned face = 0; ok && face < M3DS_SKYBOX_FACE_COUNT; face++) {
            if (!(used_faces & (1u << face))) continue;
            ok = read_face(directory, face, face_rgb);
            for (unsigned pixel = 0; ok && pixel < viewport_pixels; pixel++) {
                uint32_t offset = addresses[pixel] - face * face_pixels;
                if (offset >= face_pixels) continue;
                for (unsigned channel = 0; channel < 3; channel++) {
                    rgb[pixel * 3 + channel] = face_rgb[offset * 3 + channel];
                }
            }
        }
    }
    free(face_rgb);
    free(addresses);
    return ok;
}
