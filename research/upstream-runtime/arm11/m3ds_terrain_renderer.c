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
static bool gpu_ready;
static bool renderer_ready;
static bool frame_open;
static C2D_SpriteSheet sheet;
static C3D_RenderTarget *target;
static C3D_RenderTarget *palette;
static C2D_TextBuf palette_buffer;
static C2D_Text palette_labels[4];
static float world_left, world_bottom, scale, screen_left, screen_bottom;
static int32_t draws;

int32_t m3ds_terrain_initialize(void) {
    if (sheet) return 1;
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
        int32_t status = m3ds_terrain_initialize();
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

int32_t m3ds_terrain_palette_active(void) {
    return palette != NULL;
}

int32_t m3ds_terrain_build_overlay(int32_t column, int32_t row, int32_t block, int32_t rotation,
                                  int32_t removing, int32_t tile_size) {
    if (!frame_open) return 0;
    if (!palette) {
        static const char *labels[] = {"Conveyor", "Copper Wall", "Rotate", "Remove"};
        palette_buffer = C2D_TextBufNew(64);
        if (!palette_buffer) return 0;
        // consoleInit switched the bottom framebuffer to RGB565.
        gfxSetScreenFormat(GFX_BOTTOM, GSP_BGR8_OES);
        gfxSetDoubleBuffering(GFX_BOTTOM, true);
        palette = C2D_CreateScreenTarget(GFX_BOTTOM, GFX_LEFT);
        if (!palette) return 0;
        for (int i = 0; i < 4; i++) {
            C2D_TextParse(&palette_labels[i], palette_buffer, labels[i]);
            C2D_TextOptimize(&palette_labels[i]);
        }
    }
    u32 accent = removing ? C2D_Color32(241, 117, 97, 255) : C2D_Color32(250, 213, 107, 255);
    if (column >= 0 && row >= 0) {
        float size = tile_size * scale;
        float x = screen_left + column * size;
        float y = screen_bottom - (row + 1) * size;
        C2D_DrawRectSolid(x, y, 0.5f, size, 2, accent);
        C2D_DrawRectSolid(x, y + size - 2, 0.5f, size, 2, accent);
        C2D_DrawRectSolid(x, y, 0.5f, 2, size, accent);
        C2D_DrawRectSolid(x + size - 2, y, 0.5f, 2, size, accent);
        if (block == 0 && !removing) {
            static const int dx[] = {1, 0, -1, 0}, dy[] = {0, -1, 0, 1};
            float cx = x + size / 2, cy = y + size / 2;
            C2D_DrawLine(cx, cy, accent, cx + dx[rotation] * 6, cy + dy[rotation] * 6, accent, 2, 0.5f);
        }
    }
    C2D_TargetClear(palette, C2D_Color32(24, 27, 32, 255));
    C2D_SceneBegin(palette);
    C3D_DepthTest(false, GPU_ALWAYS, GPU_WRITE_COLOR);
    for (int i = 0; i < 4; i++) {
        bool active = removing ? i == 3 : i == block;
        C2D_DrawRectSolid(i * 80 + 2, 4, 0.5f, 76, 39,
            active ? C2D_Color32(85, 71, 52, 255) : C2D_Color32(45, 52, 64, 255));
        C2D_DrawText(&palette_labels[i], C2D_WithColor | C2D_AlignCenter, i * 80 + 40, 16, 0.5f,
            0.42f, 0.42f, active ? accent : C2D_Color32(239, 241, 247, 255));
    }
    for (int x = 0; x <= 16; x++) {
        C2D_DrawRectSolid(32 + x * 16, 48, 0.5f, 1, 192, C2D_Color32(65, 74, 88, 255));
    }
    for (int y = 0; y < 12; y++) {
        C2D_DrawRectSolid(32, 48 + y * 16, 0.5f, 256, 1, C2D_Color32(65, 74, 88, 255));
    }
    if (column >= 0 && row >= 0) {
        C2D_DrawRectSolid(33 + column * 16, 49 + (11 - row) * 16, 0.5f, 15, 15, accent);
    }
    return 1;
}

void m3ds_terrain_fini(void) {
    if (frame_open) m3ds_terrain_end();
    if (palette_buffer) C2D_TextBufDelete(palette_buffer);
    if (palette) C3D_RenderTargetDelete(palette);
    if (sheet) C2D_SpriteSheetFree(sheet);
    if (target) C3D_RenderTargetDelete(target);
    if (renderer_ready) C2D_Fini();
    if (gpu_ready) C3D_Fini();
    if (romfs_ready) romfsExit();
}
