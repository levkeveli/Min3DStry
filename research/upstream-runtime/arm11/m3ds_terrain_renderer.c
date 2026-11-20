#include "m3ds_terrain_renderer.h"

#include <3ds.h>
#include <citro2d.h>
#include <math.h>

typedef struct {
    int page;
    int left, top, width, height;
    size_t index;
} ArcAtlasRegion;

#include "terrain_layout.h"

static bool romfs_ready;
static bool session_finished;
static bool gpu_ready;
static bool renderer_ready;
static bool frame_open;
static C2D_SpriteSheet sheet;
static C3D_RenderTarget *target;
static float world_left, world_bottom, scale, screen_left, screen_bottom;
static int32_t draws;

static int32_t prepare_renderer(void) {
    romfs_ready = R_SUCCEEDED(romfsInit());
    if (!romfs_ready) return -1;
    gpu_ready = C3D_Init(C3D_DEFAULT_CMDBUF_SIZE);
    if (!gpu_ready) return -2;
    renderer_ready = C2D_Init(2048);
    if (!renderer_ready) return -3;
    C2D_Prepare();
    target = C2D_CreateScreenTarget(GFX_TOP, GFX_LEFT);
    sheet = C2D_SpriteSheetLoad("romfs:/terrain.t3x");
    if (!target) return -4;
    if (!sheet) return -5;
    size_t count = sizeof(terrain_regions) / sizeof(terrain_regions[0]);
    if (C2D_SpriteSheetCount(sheet) != count) return -6;
    for (size_t i = 0; i < count; i++) {
        const ArcAtlasRegion *region = &terrain_regions[i];
        C2D_Image image = C2D_SpriteSheetGetImage(sheet, region->index);
        if (!image.tex || !image.subtex || image.subtex->width != region->width
                || image.subtex->height != region->height) {
            return -(int32_t)(100 + i);
        }
        C3D_TexSetFilter(image.tex, GPU_NEAREST, GPU_NEAREST);
    }
    return 1;
}

int32_t m3ds_terrain_begin(int32_t left, int32_t bottom, int32_t width, int32_t height,
                           int32_t tile_size) {
    if (!sheet) {
        int32_t status = prepare_renderer();
        if (status < 0) return status;
    }
    world_left = (left - 0.5f) * tile_size;
    world_bottom = (bottom - 0.5f) * tile_size;
    float world_width = width * tile_size;
    float world_height = height * tile_size;
    scale = fminf(400.0f / world_width, 240.0f / world_height);
    screen_left = 0.5f * (400.0f - world_width * scale);
    screen_bottom = 0.5f * (240.0f + world_height * scale);
    draws = 0;
    frame_open = C3D_FrameBegin(C3D_FRAME_SYNCDRAW);
    if (!frame_open) return -7;
    C2D_TargetClear(target, C2D_Color32(24, 27, 32, 255));
    C2D_SceneBegin(target);
    C3D_DepthTest(false, GPU_ALWAYS, GPU_WRITE_COLOR);
    C2D_SetTintMode(C2D_TintMult);
    return 1;
}

int32_t m3ds_terrain_draw(int32_t page, int32_t left, int32_t top, int32_t width, int32_t height,
                          float x, float y, float origin_x, float origin_y, float draw_width,
                          float draw_height, float rotation, int32_t color) {
    if (!frame_open || width <= 0 || height <= 0) return 0;
    size_t count = sizeof(terrain_regions) / sizeof(terrain_regions[0]);
    for (size_t i = 0; i < count; i++) {
        const ArcAtlasRegion *region = &terrain_regions[i];
        int offset_x = left - region->left;
        int offset_y = top - region->top;
        if (page != region->page || offset_x < 0 || offset_y < 0
                || width > region->width - offset_x || height > region->height - offset_y) {
            continue;
        }
        C2D_Image image = C2D_SpriteSheetGetImage(sheet, region->index);
        const Tex3DS_SubTexture *parent = image.subtex;
        Tex3DS_SubTexture subtexture = {.width = width, .height = height};
        if (Tex3DS_SubTextureRotated(parent)) {
            // tex3ds rotates the packed rectangle counter-clockwise. In this
            // representation source X advances V and source Y advances U.
            float dv = (parent->right - parent->left) / region->width;
            float du = (parent->bottom - parent->top) / region->height;
            subtexture.left = parent->left + offset_x * dv;
            subtexture.right = parent->left + (offset_x + width) * dv;
            subtexture.top = parent->top + offset_y * du;
            subtexture.bottom = parent->top + (offset_y + height) * du;
        } else {
            // Arc counts rows down from the top. GPU V increases from the bottom.
            float du = (parent->right - parent->left) / region->width;
            float dv = (parent->bottom - parent->top) / region->height;
            subtexture.left = parent->left + offset_x * du;
            subtexture.right = parent->left + (offset_x + width) * du;
            subtexture.top = parent->top + offset_y * dv;
            subtexture.bottom = parent->top + (offset_y + height) * dv;
        }
        image.subtex = &subtexture;
        C2D_DrawParams params = {
            .pos = { screen_left + (x + origin_x - world_left) * scale,
                     screen_bottom - (y + origin_y - world_bottom) * scale,
                     draw_width * scale, draw_height * scale },
            .center = { origin_x * scale, (draw_height - origin_y) * scale },
            .depth = 0.5f,
            .angle = rotation * (-3.14159265358979323846f / 180.0f),
        };
        C2D_ImageTint tint;
        C2D_PlainImageTint(&tint, (u32)color, 1.0f);
        if (!C2D_DrawImage(image, &params, &tint)) return 0;
        draws++;
        return 1;
    }
    return 0;
}

int32_t m3ds_terrain_end(void) {
    if (!frame_open) return 0;
    C3D_FrameEnd(0);
    frame_open = false;
    return draws;
}

int32_t m3ds_terrain_input(void) {
    if (!aptMainLoop()) {
        session_finished = true;
        return -1;
    }
    hidScanInput();
    u32 pressed = hidKeysDown();
    u32 held = hidKeysHeld() | pressed;
    if (pressed & KEY_START) {
        session_finished = true;
        return -1;
    }
    return ((pressed & KEY_A) ? 1 : 0) | ((pressed & KEY_X) ? 2 : 0) |
           ((pressed & KEY_B) ? 4 : 0) | ((held & KEY_LEFT) ? 8 : 0) |
           ((held & KEY_RIGHT) ? 16 : 0) | ((held & KEY_DOWN) ? 32 : 0) |
           ((held & KEY_UP) ? 64 : 0) | ((pressed & KEY_Y) ? 128 : 0);
}

int64_t m3ds_terrain_clock_ms(void) {
    return svcGetSystemTick() / (SYSCLOCK_ARM11 / 1000);
}

void m3ds_terrain_wait(void) {
    gspWaitForVBlank();
}

bool m3ds_terrain_session_finished(void) {
    return session_finished;
}

void m3ds_terrain_fini(void) {
    if (frame_open) m3ds_terrain_end();
    if (sheet) C2D_SpriteSheetFree(sheet);
    if (target) C3D_RenderTargetDelete(target);
    if (renderer_ready) C2D_Fini();
    if (gpu_ready) C3D_Fini();
    if (romfs_ready) romfsExit();
}
