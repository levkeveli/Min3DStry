#include "m3ds_terrain_renderer.h"

#include <3ds.h>
#include <citro2d.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

typedef struct {
    int page;
    int left, top, width, height;
    size_t index;
} ArcAtlasRegion;

#include "terrain_layout.h"
#ifdef M3DS_WORLD_ATLAS
#include "world_atlas_layout.h"

// Retain at most 1.5 MiB of RGBA8 atlas backing. A frame never evicts its own textures.
static struct {
    C2D_SpriteSheet sheet;
    int page, x, y;
    uint32_t frame;
} world_textures[24];
static uint32_t world_frame;
static bool reported_split;
#endif

static bool romfs_ready;
static bool gpu_ready;
static bool renderer_ready;
static bool frame_open;
static C2D_SpriteSheet sheet;
static C3D_RenderTarget *target;
static C3D_RenderTarget *palette;
static C2D_TextBuf palette_buffer;
static C2D_Text palette_labels[7];
static C2D_TextBuf catalog_buffer;
static struct {
    C2D_SpriteSheet sheet;
    int32_t id;
    bool categories, requested;
} menu_icons[6];
static float world_left, world_bottom, scale, screen_left, screen_bottom;
static int32_t draws;

int32_t m3ds_terrain_mount_assets(void) {
    if (!romfs_ready) romfs_ready = R_SUCCEEDED(romfsInit());
    return romfs_ready;
}

int32_t m3ds_terrain_system_language(void) {
    u8 language = CFG_LANGUAGE_EN;
    if (R_SUCCEEDED(cfguInit())) {
        if (R_FAILED(CFGU_GetSystemLanguage(&language))) language = CFG_LANGUAGE_EN;
        cfguExit();
    }
    return language;
}

int32_t m3ds_terrain_initialize(void) {
    if (sheet) return 1;
    m3ds_terrain_mount_assets();
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
#ifdef M3DS_WORLD_ATLAS
    world_frame++;
#endif
    C2D_TargetClear(target, C2D_Color32(24, 27, 32, 255));
    C2D_SceneBegin(target);
    C3D_DepthTest(false, GPU_ALWAYS, GPU_WRITE_COLOR);
    C2D_SetTintMode(C2D_TintMult);
    return 1;
}

#ifdef M3DS_WORLD_ATLAS
static C2D_SpriteSheet get_world_texture(int page, int x, int y) {
    int victim = -1;
    for (size_t i = 0; i < sizeof(world_textures) / sizeof(world_textures[0]); i++) {
        if (world_textures[i].sheet && world_textures[i].page == page
                && world_textures[i].x == x && world_textures[i].y == y) {
            world_textures[i].frame = world_frame;
            return world_textures[i].sheet;
        }
        if (!world_textures[i].sheet || world_textures[i].frame != world_frame) {
            if (victim < 0 || (world_textures[victim].sheet && (!world_textures[i].sheet
                    || world_textures[i].frame < world_textures[victim].frame))) {
                victim = (int)i;
            }
        }
    }
    if (victim < 0) return NULL;
    if (world_textures[victim].sheet) C2D_SpriteSheetFree(world_textures[victim].sheet);
    char path[48];
    snprintf(path, sizeof(path), "romfs:/world-atlas/%d-%d-%d.t3x", page, x, y);
    C2D_SpriteSheet sheet = C2D_SpriteSheetLoad(path);
    world_textures[victim].sheet = sheet;
    if (!sheet) return NULL;
    world_textures[victim].page = page;
    world_textures[victim].x = x;
    world_textures[victim].y = y;
    world_textures[victim].frame = world_frame;
    C3D_TexSetFilter(C2D_SpriteSheetGetImage(sheet, 0).tex, GPU_NEAREST, GPU_NEAREST);
    return sheet;
}

static int32_t draw_world_region(int page, int left, int top, int w, int h,
                                 const C2D_DrawParams *whole, const C2D_ImageTint *tint) {
    if (page < 0 || (size_t)page >= sizeof(world_atlas_sizes) / sizeof(world_atlas_sizes[0])
            || left < 0 || top < 0 || w > world_atlas_sizes[page][0] - left
            || h > world_atlas_sizes[page][1] - top) return 0;

    const int tile = WORLD_ATLAS_TILE;
    const int right = left + w, bottom = top + h;
    const float step_x = whole->pos.w / w, step_y = whole->pos.h / h;
    int fragments = 0;
    for (int y = top; y < bottom;) {
        int tile_y = y / tile, row_end = (tile_y + 1) * tile;
        if (row_end > bottom) row_end = bottom;
        for (int x = left; x < right;) {
            int tile_x = x / tile, col_end = (tile_x + 1) * tile;
            if (col_end > right) col_end = right;
            C2D_SpriteSheet sheet = get_world_texture(page, tile_x, tile_y);
            if (!sheet) return 0;
            C2D_Image image = C2D_SpriteSheetGetImage(sheet, 0);
            Tex3DS_SubTexture sub = {
                .width = col_end - x, .height = row_end - y,
                .left = (float)(x - tile_x * tile) / tile, .right = (float)(col_end - tile_x * tile) / tile,
                .top = 1.0f - (float)(y - tile_y * tile) / tile,
                .bottom = 1.0f - (float)(row_end - tile_y * tile) / tile,
            };
            image.subtex = &sub;
            C2D_DrawParams piece = *whole;
            piece.pos.w = sub.width * step_x;
            piece.pos.h = sub.height * step_y;
            // Retain the original pivot even when it lies outside this fragment.
            piece.center.x -= (x - left) * step_x;
            piece.center.y -= (y - top) * step_y;
            if (!C2D_DrawImage(image, &piece, tint)) return 0;
            fragments++;
            x = col_end;
        }
        y = row_end;
    }
    if (!reported_split && fragments > 1) {
        char record[112];
        int length = snprintf(record, sizeof(record), "UPSTREAM_WORLD_ATLAS page=%d rect=%d,%d+%dx%d fragments=%d\n",
            page, left, top, w, h, fragments);
        if (length > 0 && length < (int)sizeof(record)) svcOutputDebugString(record, length);
        reported_split = true;
    }
    draws += fragments;
    return 1;
}
#endif

int32_t m3ds_terrain_draw(int32_t page, int32_t left, int32_t top, int32_t width, int32_t height,
                          float x, float y, float origin_x, float origin_y, float draw_width,
                          float draw_height, float rotation, int32_t color) {
    if (!frame_open || width <= 0 || height <= 0) return 0;
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
        if (!C2D_DrawImage(image, &params, &tint)) return 0;
        draws++;
        return 1;
    }
#ifdef M3DS_WORLD_ATLAS
    return draw_world_region(page, left, top, width, height, &params, &tint);
#else
    return 0;
#endif
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

int32_t m3ds_terrain_build_labels(const char *labels) {
    palette_buffer = C2D_TextBufNew(256);
    catalog_buffer = C2D_TextBufNew(512);
    if (!palette_buffer || !catalog_buffer) return 0;
    for (int i = 0; i < 7; i++) {
        C2D_TextParse(&palette_labels[i], palette_buffer, labels);
        C2D_TextOptimize(&palette_labels[i]);
        labels += strlen(labels) + 1;
    }
    return 1;
}

int32_t m3ds_terrain_build_overlay(int32_t column, int32_t row, int32_t block, int32_t rotation,
                                  int32_t removing, int32_t tile_size, int32_t rotatable, int32_t catalog_open,
                                  int32_t configuring) {
    if (!frame_open) return 0;
    if (!palette) {
        if (!palette_buffer || !catalog_buffer) return 0;
        // consoleInit switched the bottom framebuffer to RGB565.
        gfxSetScreenFormat(GFX_BOTTOM, GSP_BGR8_OES);
        gfxSetDoubleBuffering(GFX_BOTTOM, true);
        palette = C2D_CreateScreenTarget(GFX_BOTTOM, GFX_LEFT);
        if (!palette) return 0;
    }
    u32 accent = removing ? C2D_Color32(241, 117, 97, 255) : C2D_Color32(250, 213, 107, 255);
    bool cursor = column >= 0 && column < 16 && row >= 0 && row < 12;
    if (cursor) {
        float size = tile_size * scale;
        float x = screen_left + column * size;
        float y = screen_bottom - (row + 1) * size;
        C2D_DrawRectSolid(x, y, 0.5f, size, 2, accent);
        C2D_DrawRectSolid(x, y + size - 2, 0.5f, size, 2, accent);
        C2D_DrawRectSolid(x, y, 0.5f, 2, size, accent);
        C2D_DrawRectSolid(x + size - 2, y, 0.5f, 2, size, accent);
        if (rotatable && !removing) {
            static const int dx[] = {1, 0, -1, 0}, dy[] = {0, -1, 0, 1};
            float cx = x + size / 2, cy = y + size / 2;
            C2D_DrawLine(cx, cy, accent, cx + dx[rotation] * 6, cy + dy[rotation] * 6, accent, 2, 0.5f);
        }
    }
    C2D_TargetClear(palette, C2D_Color32(24, 27, 32, 255));
    C2D_SceneBegin(palette);
    C3D_DepthTest(false, GPU_ALWAYS, GPU_WRITE_COLOR);
    if (catalog_open) return 1;
    C2D_DrawRectSolid(2, 48, 0.5f, 26, 31, C2D_Color32(45, 52, 64, 255));
    C2D_DrawText(&palette_labels[4], C2D_WithColor | C2D_AlignCenter, 15, 57, 0.5f,
        0.34f, 0.34f, block < 0 && !removing ? accent : C2D_Color32(239, 241, 247, 255));
    C2D_DrawRectSolid(292, 48, 0.5f, 26, 31, C2D_Color32(45, 52, 64, 255));
    C2D_DrawText(&palette_labels[5], C2D_WithColor | C2D_AlignCenter, 305, 57, 0.5f,
        0.30f, 0.30f, configuring ? accent : C2D_Color32(239, 241, 247, 255));
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
    if (cursor) {
        C2D_DrawRectSolid(33 + column * 16, 49 + (11 - row) * 16, 0.5f, 15, 15, accent);
    }
    return 1;
}

static void catalog_label(const char *text, float x, float y, float scale, u32 color) {
    C2D_Text label;
    C2D_TextParse(&label, catalog_buffer, text);
    C2D_TextOptimize(&label);
    C2D_DrawText(&label, C2D_WithColor | C2D_AlignCenter, x, y, 0.5f, scale, scale, color);
}

static bool draw_menu_icon(int row, int32_t id, bool categories, float y) {
    if (id < 0) return false;
    if (!menu_icons[row].requested || menu_icons[row].id != id || menu_icons[row].categories != categories) {
        // FrameBegin completed the previous frame before any row texture can be replaced.
        if (menu_icons[row].sheet) C2D_SpriteSheetFree(menu_icons[row].sheet);
        menu_icons[row].id = id;
        menu_icons[row].categories = categories;
        menu_icons[row].requested = true;
        char path[48];
        snprintf(path, sizeof(path), "romfs:/catalog/%s-%ld.t3x", categories ? "block" : "item", (long)id);
        menu_icons[row].sheet = C2D_SpriteSheetLoad(path);
    }
    if (!menu_icons[row].sheet) return false;
    C2D_Image image = C2D_SpriteSheetGetImage(menu_icons[row].sheet, 0);
    C3D_TexSetFilter(image.tex, GPU_NEAREST, GPU_NEAREST);
    float icon_scale = fminf(20.0f / image.subtex->width, 20.0f / image.subtex->height);
    return C2D_DrawImageAt(image, 27 - image.subtex->width * icon_scale / 2,
        y + (23 - image.subtex->height * icon_scale) / 2, 0.5f, NULL, icon_scale, icon_scale);
}

int32_t m3ds_terrain_build_catalog(const char *labels, const int32_t *icons, int32_t selected,
                                    int32_t previous, int32_t next, int32_t categories) {
    const u32 panel = C2D_Color32(45, 52, 64, 255), text = C2D_Color32(239, 241, 247, 255);
    const u32 selected_panel = C2D_Color32(85, 71, 52, 255), accent = C2D_Color32(250, 213, 107, 255);
    C2D_TextBufClear(catalog_buffer);
    if (categories) {
        C2D_DrawRectSolid(8, 4, 0.5f, 54, 34, panel);
        C2D_DrawRectSolid(258, 4, 0.5f, 54, 34, panel);
        catalog_label("<", 35, 15, 0.55f, text);
        catalog_label(">", 285, 15, 0.55f, text);
    }
    catalog_label(labels, 160, 15, 0.52f, text);
    int32_t visible_icons = 0;
    for (int row = 0; row < 6; row++) {
        labels += strlen(labels) + 1;
        if (!*labels) continue;
        float y = 44 + row * 25;
        C2D_DrawRectSolid(8, y, 0.5f, 304, 23, row == selected ? selected_panel : panel);
        if (draw_menu_icon(row, icons[row], categories != 0, y)) visible_icons++;
        catalog_label(labels, 177, y + 4, 0.50f, row == selected ? accent : text);
    }
    if (previous) {
        C2D_DrawRectSolid(8, 201, 0.5f, 88, 34, panel);
        catalog_label("Prev page", 52, 212, 0.45f, text);
    }
    C2D_DrawRectSolid(104, 201, 0.5f, 112, 34, panel);
    C2D_DrawText(&palette_labels[6], C2D_WithColor | C2D_AlignCenter, 160, 212, 0.5f, 0.45f, 0.45f, text);
    if (next) {
        C2D_DrawRectSolid(224, 201, 0.5f, 88, 34, panel);
        catalog_label("Next page", 268, 212, 0.45f, text);
    }
    return visible_icons;
}

void m3ds_terrain_fini(void) {
    if (frame_open) m3ds_terrain_end();
    if (gpu_ready && C3D_FrameBegin(0)) C3D_FrameEnd(0);
#ifdef M3DS_WORLD_ATLAS
    for (size_t i = 0; i < sizeof(world_textures) / sizeof(world_textures[0]); i++) {
        if (world_textures[i].sheet) C2D_SpriteSheetFree(world_textures[i].sheet);
    }
#endif
    for (size_t row = 0; row < sizeof(menu_icons) / sizeof(menu_icons[0]); row++) {
        if (menu_icons[row].sheet) C2D_SpriteSheetFree(menu_icons[row].sheet);
    }
    if (palette_buffer) C2D_TextBufDelete(palette_buffer);
    if (catalog_buffer) C2D_TextBufDelete(catalog_buffer);
    if (palette) C3D_RenderTargetDelete(palette);
    if (sheet) C2D_SpriteSheetFree(sheet);
    if (target) C3D_RenderTargetDelete(target);
    if (renderer_ready) C2D_Fini();
    if (gpu_ready) C3D_Fini();
    if (romfs_ready) romfsExit();
}
