#include "m3ds_terrain_renderer.h"

#include <3ds.h>
#include <citro2d.h>
#include <errno.h>
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
#include "m3ds_quad_clip.h"
#include "m3ds_quad_shbin.h"
#include "m3ds_world_fragment.h"
#include "world_atlas_layout.h"

// Retain at most 1.5 MiB of RGBA8 atlas backing in the default build.
#ifndef M3DS_WORLD_ATLAS_SLOTS
#define M3DS_WORLD_ATLAS_SLOTS 24
#endif
_Static_assert(M3DS_WORLD_ATLAS_SLOTS > 0, "The world atlas cache needs at least one slot");
static struct {
    C2D_SpriteSheet sheet;
    int page, x, y;
    uint32_t frame;
} world_textures[M3DS_WORLD_ATLAS_SLOTS];
static uint32_t world_pass;
enum { canvas_texture_size = 32, canvas_texture_slots = 16 };
static struct {
    C3D_Tex tex;
    int32_t id, revision;
    uint32_t pass;
} canvas_textures[canvas_texture_slots];
static bool reported_split;
static bool reported_recycle;
static DVLB_s *quad_shader;
static shaderProgram_s quad_program;
static C3D_AttrInfo quad_attributes;
static C3D_Mtx quad_projection;
static int quad_projection_uniform;
#endif

static bool romfs_ready;
static bool gpu_ready;
static bool renderer_ready;
static bool frame_open;
static C2D_SpriteSheet sheet;
static C3D_RenderTarget *target;
static C3D_RenderTarget *palette;
static C2D_TextBuf palette_buffer;
static C2D_Text palette_labels[8];
static C2D_TextBuf catalog_buffer;
static struct {
    C2D_SpriteSheet sheet;
    int32_t id;
    bool categories, requested;
} menu_icons[6];
static float world_left, world_bottom, scale, screen_left, screen_bottom;
static int32_t draws;
static int32_t world_blend = -1;
static u32 world_fade;

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

static void begin_world_scene(void) {
    C2D_SceneBegin(target);
    // The top framebuffer stores screen X along its rotated Y axis.
    u32 x_margin = (u32)lroundf(fmaxf(0.0f, screen_left));
    u32 y_margin = (u32)lroundf(fmaxf(0.0f, 240.0f - screen_bottom));
    C3D_SetScissor(GPU_SCISSOR_NORMAL, y_margin, x_margin, 240 - y_margin, 400 - x_margin);
    C3D_DepthTest(false, GPU_ALWAYS, GPU_WRITE_COLOR);
    C2D_SetTintMode(C2D_TintMult);
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
    world_pass++;
#endif
    C2D_TargetClear(target, C2D_Color32(24, 27, 32, 255));
    begin_world_scene();
    return 1;
}

#ifdef M3DS_WORLD_ATLAS
static bool recycle_world_pass(void) {
    // Submit finished draws to the same render target without displaying a partial frame.
    const u32 transfer_flags = target->transferFlags;
    C3D_RenderTargetDetachOutput(target);
    C3D_FrameEnd(0);
    frame_open = C3D_FrameBegin(C3D_FRAME_SYNCDRAW);
    C3D_RenderTargetSetOutput(target, GFX_TOP, GFX_LEFT, transfer_flags);
    if (!frame_open) return false;

    // FrameBegin waited for the previous command queue. Clear Citro2D's saved texture
    // pointer before freeing any sheet, then continue without clearing the framebuffer.
    C2D_Prepare();
    begin_world_scene();
    world_pass++;
    if (!reported_recycle) {
        static const char record[] = "UPSTREAM_WORLD_ATLAS_RECYCLE\n";
        svcOutputDebugString(record, sizeof(record) - 1);
        reported_recycle = true;
    }
    return true;
}

static void report_atlas_failure(const char *stage, int page, int x, int y, int error) {
    char record[120];
    int length = snprintf(record, sizeof(record),
        "UPSTREAM_WORLD_ATLAS_ERROR stage=%s page=%d tile=%d,%d error=%d linear=%lu\n",
        stage, page, x, y, error, (unsigned long)linearSpaceFree());
    if (length > 0 && length < (int)sizeof(record)) svcOutputDebugString(record, length);
}

static C2D_SpriteSheet get_world_texture(int page, int x, int y) {
    int vacant = -1;
    int victim = -1;
    for (size_t i = 0; i < sizeof(world_textures) / sizeof(world_textures[0]); i++) {
        if (!world_textures[i].sheet) {
            if (vacant < 0) vacant = (int)i;
            continue;
        }
        if (world_textures[i].page == page && world_textures[i].x == x && world_textures[i].y == y) {
            world_textures[i].frame = world_pass;
            return world_textures[i].sheet;
        }
        if (world_textures[i].frame != world_pass
                && (victim < 0 || world_textures[i].frame < world_textures[victim].frame)) {
            victim = (int)i;
        }
    }
    // Each catalog row can load one 128×128 RGBA8 icon while the world cache stays resident.
    const size_t texture_bytes = WORLD_ATLAS_TILE * WORLD_ATLAS_TILE * 4;
    const size_t menu_rows = sizeof(menu_icons) / sizeof(menu_icons[0]);
    if (vacant >= 0 && linearSpaceFree() >= texture_bytes * (menu_rows + 1)) {
        victim = vacant;
    } else if (victim < 0) {
        // Slots fill in order. A populated slot zero can be overwritten after the fence.
        if (!world_textures[0].sheet) {
            report_atlas_failure("budget", page, x, y, 0);
            return NULL;
        }
        if (!recycle_world_pass()) {
            report_atlas_failure("recycle", page, x, y, 0);
            return NULL;
        }
        victim = 0;
    }
    if (world_textures[victim].sheet) C2D_SpriteSheetFree(world_textures[victim].sheet);
    char path[48];
    snprintf(path, sizeof(path), "romfs:/world-atlas/%d-%d-%d.t3x", page, x, y);
    world_textures[victim].sheet = NULL;
    FILE *file = fopen(path, "rb");
    if (!file) {
        report_atlas_failure("open", page, x, y, errno);
        return NULL;
    }
    setvbuf(file, NULL, _IOFBF, 64 * 1024);
    C2D_SpriteSheet sheet = C2D_SpriteSheetLoadFromHandle(file);
    fclose(file);
    if (!sheet) {
        report_atlas_failure("import", page, x, y, 0);
        return NULL;
    }
    world_textures[victim].sheet = sheet;
    world_textures[victim].page = page;
    world_textures[victim].x = x;
    world_textures[victim].y = y;
    world_textures[victim].frame = world_pass;
    C3D_Tex *texture = C2D_SpriteSheetGetImage(sheet, 0).tex;
    C3D_TexSetFilter(texture, GPU_NEAREST, GPU_NEAREST);
    // A pixel center exactly on an internal atlas edge can sample coordinate 1.
    // Clamp to the adjacent texel instead of introducing a transparent border.
    C3D_TexSetWrap(texture, GPU_CLAMP_TO_EDGE, GPU_CLAMP_TO_EDGE);
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
            // Retain the original pivot for positive and mirrored fragments.
            piece.center.x = m3ds_world_fragment_center(whole->center.x, x, col_end, left, right, step_x);
            piece.center.y = m3ds_world_fragment_center(whole->center.y, y, row_end, top, bottom, step_y);
            if (!C2D_DrawImage(image, &piece, tint)) {
                report_atlas_failure("draw", page, tile_x, tile_y, 0);
                return 0;
            }
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

static void set_world_blend(int32_t mode) {
    if (world_blend == mode) return;
    C2D_Flush();
    GPU_BLENDFACTOR source = mode == 2 ? GPU_ONE : GPU_SRC_ALPHA;
    GPU_BLENDFACTOR dest = mode == 2 ? GPU_ZERO : mode == 1 ? GPU_ONE : GPU_ONE_MINUS_SRC_ALPHA;
    C3D_AlphaBlend(GPU_BLEND_ADD, GPU_BLEND_ADD, source, dest, GPU_ONE,
        mode == 2 ? GPU_ZERO : GPU_ONE_MINUS_SRC_ALPHA);
    world_blend = mode;
}

static void set_world_mix(u32 tint, u32 mix_color) {
    // Arc computes tint * mix(texture.rgb, mix.rgb, mix.a), with texture alpha unchanged by mixing.
    // Citro2D fades after tinting, so tint the fade endpoint as well as the texture.
    u32 fade = mix_color & 0xff000000u;
    if (fade) {
        for (int shift = 0; shift < 24; shift += 8) {
            u32 a = (tint >> shift) & 255;
            u32 b = (mix_color >> shift) & 255;
            fade |= ((a * b + 127) / 255) << shift;
        }
    }
    if (fade == world_fade) return;
    C2D_Flush();
    C2D_Fade(fade);
    world_fade = fade;
}

static C2D_DrawParams sprite_params(float x, float y, float origin_x, float origin_y,
                                    float draw_width, float draw_height, float rotation) {
    return (C2D_DrawParams){
        .pos = { screen_left + (x + origin_x - world_left) * scale,
                 screen_bottom - (y + origin_y - world_bottom) * scale,
                 draw_width * scale, draw_height * scale },
        .center = { (origin_x - fminf(0.0f, draw_width)) * scale,
                    (fmaxf(0.0f, draw_height) - origin_y) * scale },
        .depth = 0.5f,
        .angle = rotation * (-3.14159265358979323846f / 180.0f),
    };
}

int32_t m3ds_terrain_draw(int32_t page, int32_t left, int32_t top, int32_t width, int32_t height,
                          float x, float y, float origin_x, float origin_y, float draw_width,
                          float draw_height, float rotation, int32_t color, int32_t mix_color, int32_t blend_mode) {
    if (!frame_open || width <= 0 || height <= 0) return 0;
    set_world_blend(blend_mode);
    set_world_mix((u32)color, (u32)mix_color);
    C2D_DrawParams params = sprite_params(x, y, origin_x, origin_y, draw_width, draw_height, rotation);
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

#ifdef M3DS_WORLD_ATLAS
static unsigned canvas_texel(unsigned x, unsigned y) {
    unsigned morton = 0;
    for (unsigned bit = 0; bit < 3; bit++) {
        morton |= ((x >> bit) & 1) << (2 * bit);
        morton |= ((y >> bit) & 1) << (2 * bit + 1);
    }
    return ((y / 8) * (canvas_texture_size / 8) + x / 8) * 64 + morton;
}

static C3D_Tex *upload_canvas(int32_t id, int32_t revision, int width, int height, const uint32_t *pixels) {
    int victim = -1;
    for (int i = 0; i < canvas_texture_slots; i++) {
        if (canvas_textures[i].id == id && canvas_textures[i].revision == revision) {
            canvas_textures[i].pass = world_pass;
            return &canvas_textures[i].tex;
        }
        if (canvas_textures[i].pass != world_pass
                && (victim < 0 || canvas_textures[i].pass < canvas_textures[victim].pass)) victim = i;
    }
    if (victim < 0) {
        // Wait before writing pixels that a queued draw can still sample.
        if (!recycle_world_pass()) return NULL;
        victim = 0;
    }
    C3D_Tex *tex = &canvas_textures[victim].tex;
    if (!tex->data) {
        if (!C3D_TexInitVRAM(tex, canvas_texture_size, canvas_texture_size, GPU_RGBA8)
                && !C3D_TexInit(tex, canvas_texture_size, canvas_texture_size, GPU_RGBA8)) return NULL;
        C3D_TexSetFilter(tex, GPU_NEAREST, GPU_NEAREST);
        C3D_TexSetWrap(tex, GPU_CLAMP_TO_EDGE, GPU_CLAMP_TO_EDGE);
    }
    uint32_t *tiled = tex->data;
    for (int y = 0; y < canvas_texture_size; y++) {
        const uint32_t *row = pixels + (y < height ? y : height - 1) * width;
        for (int x = 0; x < canvas_texture_size; x++) {
            tiled[canvas_texel(x, y)] = row[x < width ? x : width - 1];
        }
    }
    C3D_TexFlush(tex);
    canvas_textures[victim].id = id;
    canvas_textures[victim].revision = revision;
    canvas_textures[victim].pass = world_pass;
    return tex;
}
#endif

int32_t m3ds_terrain_draw_canvas(int32_t id, int32_t revision, int32_t width, int32_t height,
                                const uint32_t *pixels, float x, float y, float origin_x, float origin_y,
                                float draw_width, float draw_height, float rotation,
                                int32_t color, int32_t mix_color, int32_t blend_mode) {
#ifdef M3DS_WORLD_ATLAS
    if (!frame_open || id == 0 || width < 1 || width > canvas_texture_size
            || height < 1 || height > canvas_texture_size) return 0;
    C3D_Tex *tex = upload_canvas(id, revision, width, height, pixels);
    if (!tex) return 0;
    set_world_blend(blend_mode);
    set_world_mix((u32)color, (u32)mix_color);
    Tex3DS_SubTexture region = {
        .width = width, .height = height, .left = 0, .top = 1,
        .right = (float)width / canvas_texture_size, .bottom = 1 - (float)height / canvas_texture_size,
    };
    C2D_DrawParams params = sprite_params(x, y, origin_x, origin_y, draw_width, draw_height, rotation);
    C2D_ImageTint tint;
    C2D_PlainImageTint(&tint, (u32)color, 1.0f);
    if (!C2D_DrawImage((C2D_Image){tex, &region}, &params, &tint)) return 0;
    draws++;
    return 1;
#else
    (void)id; (void)revision; (void)width; (void)height; (void)pixels; (void)x; (void)y;
    (void)origin_x; (void)origin_y; (void)draw_width; (void)draw_height; (void)rotation;
    (void)color; (void)mix_color; (void)blend_mode;
    return 0;
#endif
}

int32_t m3ds_terrain_draw_solid_quad(const float *vertices, int32_t offset, int32_t blend_mode) {
    if (!frame_open) return 0;
    set_world_blend(blend_mode);
    set_world_mix(0, 0);
    float x[4], y[4];
    u32 color[4];
    for (int i = 0; i < 4; i++) {
        // Arc's SpriteBatch vertex is x, y, packed color, u, v, packed mix color.
        const float *vertex = vertices + offset + i * 6;
        x[i] = screen_left + (vertex[0] - world_left) * scale;
        y[i] = screen_bottom - (vertex[1] - world_bottom) * scale;
        memcpy(&color[i], &vertex[2], sizeof(color[i]));
        color[i] |= (u32)((color[i] >> 24) * (255.0f / 254.0f)) << 24;
    }
    // Match the original SpriteBatch's (0,1,2) and (2,3,0) triangles.
    if (!C2D_DrawTriangle(x[0], y[0], color[0], x[1], y[1], color[1], x[2], y[2], color[2], 0.5f)
            || !C2D_DrawTriangle(x[2], y[2], color[2], x[3], y[3], color[3], x[0], y[0], color[0], 0.5f)) {
        return 0;
    }
    draws++;
    return 1;
}

#ifdef M3DS_WORLD_ATLAS
static bool initialize_quad_shader(void) {
    if (quad_shader) return true;
    quad_shader = DVLB_ParseFile((u32 *)m3ds_quad_shbin, m3ds_quad_shbin_size);
    if (!quad_shader) return false;
    shaderProgramInit(&quad_program);
    shaderProgramSetVsh(&quad_program, &quad_shader->DVLE[0]);
    quad_projection_uniform = shaderInstanceGetUniformLocation(quad_program.vertexShader, "projection");
    AttrInfo_Init(&quad_attributes);
    AttrInfo_AddLoader(&quad_attributes, 0, GPU_FLOAT, 4);
    AttrInfo_AddLoader(&quad_attributes, 1, GPU_FLOAT, 2);
    AttrInfo_AddLoader(&quad_attributes, 2, GPU_FLOAT, 4);
    Mtx_OrthoTilt(&quad_projection, 0, 400, 240, 0, 1, -1, true);
    return true;
}

static void draw_quad_piece(C3D_Tex *texture, const M3DS_QuadVertex *polygon, int count,
                            int x, int y, uint32_t mix) {
    C2D_Flush();
    C3D_BindProgram(&quad_program);
    C3D_SetAttrInfo(&quad_attributes);
    C3D_FVUnifMtx4x4(GPU_VERTEX_SHADER, quad_projection_uniform, &quad_projection);
    C3D_TexBind(0, texture);

    // Arc mixes RGB before multiplying the interpolated vertex tint.
    for (int i = 0; i < 6; i++) C3D_TexEnvInit(C3D_GetTexEnv(i));
    C3D_TexEnv *env = C3D_GetTexEnv(0);
    C3D_TexEnvSrc(env, C3D_Both, GPU_TEXTURE0, GPU_CONSTANT, GPU_CONSTANT);
    C3D_TexEnvOpRgb(env, GPU_TEVOP_RGB_SRC_COLOR, GPU_TEVOP_RGB_SRC_COLOR, GPU_TEVOP_RGB_ONE_MINUS_SRC_ALPHA);
    C3D_TexEnvFunc(env, C3D_RGB, GPU_INTERPOLATE);
    C3D_TexEnvColor(env, mix);
    env = C3D_GetTexEnv(1);
    C3D_TexEnvSrc(env, C3D_Both, GPU_PREVIOUS, GPU_PRIMARY_COLOR, GPU_PRIMARY_COLOR);
    C3D_TexEnvFunc(env, C3D_Both, GPU_MODULATE);

    C3D_ImmDrawBegin(GPU_TRIANGLES);
    for (int i = 1; i + 1 < count; i++) {
        const int indices[] = {0, i, i + 1};
        for (int corner = 0; corner < 3; corner++) {
            M3DS_QuadVertex v = polygon[indices[corner]];
            C3D_ImmSendAttrib(v.x, v.y, 0.5f, 1.0f);
            C3D_ImmSendAttrib((v.u - x) / WORLD_ATLAS_TILE, 1.0f - (v.v - y) / WORLD_ATLAS_TILE, 0, 0);
            C3D_ImmSendAttrib(v.color[0], v.color[1], v.color[2], v.color[3]);
        }
    }
    C3D_ImmDrawEnd();
    C2D_Prepare();
    begin_world_scene();
}
#endif

int32_t m3ds_terrain_draw_textured_quad(int32_t page, int32_t width, int32_t height,
                                      const float *vertices, int32_t offset, int32_t blend_mode) {
#ifdef M3DS_WORLD_ATLAS
    if (!frame_open || page < 0 || (size_t)page >= sizeof(world_atlas_sizes) / sizeof(world_atlas_sizes[0])
            || width != world_atlas_sizes[page][0] || height != world_atlas_sizes[page][1]
            || !initialize_quad_shader()) return 0;
    M3DS_QuadVertex corners[4];
    uint32_t mix;
    memcpy(&mix, vertices + offset + 5, sizeof(mix));
    for (int i = 0; i < 4; i++) {
        const float *v = vertices + offset + i * 6;
        uint32_t color, corner_mix;
        memcpy(&color, v + 2, sizeof(color));
        memcpy(&corner_mix, v + 5, sizeof(corner_mix));
        if (mix != corner_mix) return 0;
        if (!isfinite(v[0]) || !isfinite(v[1]) || !isfinite(v[3]) || !isfinite(v[4])) return 1;
        corners[i] = (M3DS_QuadVertex){
            .x = screen_left + (v[0] - world_left) * scale,
            .y = screen_bottom - (v[1] - world_bottom) * scale,
            .u = fminf(fmaxf(v[3] * width, 0), nextafterf(width, 0)),
            .v = fminf(fmaxf(v[4] * height, 0), nextafterf(height, 0)),
            .color = {(color & 255) / 255.0f, ((color >> 8) & 255) / 255.0f,
                      ((color >> 16) & 255) / 255.0f, (color >> 24) / 254.0f},
        };
    }
    mix = (mix & 0x00ffffffu) | ((uint32_t)((mix >> 24) * (255.0f / 254.0f)) << 24);
    set_world_blend(blend_mode);
    set_world_mix(0, 0);
    static const int triangles[2][3] = {{0, 1, 2}, {2, 3, 0}};
    int pieces = 0;
    for (int i = 0; i < 2; i++) {
        M3DS_QuadVertex tri[3] = {corners[triangles[i][0]], corners[triangles[i][1]], corners[triangles[i][2]]};
        int left = (int)fminf(tri[0].u, fminf(tri[1].u, tri[2].u)) / WORLD_ATLAS_TILE;
        int top = (int)fminf(tri[0].v, fminf(tri[1].v, tri[2].v)) / WORLD_ATLAS_TILE;
        int right = (int)fmaxf(tri[0].u, fmaxf(tri[1].u, tri[2].u)) / WORLD_ATLAS_TILE;
        int bottom = (int)fmaxf(tri[0].v, fmaxf(tri[1].v, tri[2].v)) / WORLD_ATLAS_TILE;
        for (int tile_y = top; tile_y <= bottom; tile_y++) {
            for (int tile_x = left; tile_x <= right; tile_x++) {
                int x = tile_x * WORLD_ATLAS_TILE, y = tile_y * WORLD_ATLAS_TILE;
                M3DS_QuadVertex polygon[M3DS_QUAD_MAX_VERTICES];
                int count = m3ds_quad_clip_triangle(tri, polygon, x, y, x + WORLD_ATLAS_TILE, y + WORLD_ATLAS_TILE);
                if (count < 3) continue;
                C2D_SpriteSheet atlas = get_world_texture(page, tile_x, tile_y);
                if (!atlas) return 0;
                draw_quad_piece(C2D_SpriteSheetGetImage(atlas, 0).tex, polygon, count, x, y, mix);
                pieces++;
            }
        }
    }
    static bool reported;
    if (pieces && !reported) {
        static const char message[] = "UPSTREAM_TEXTURED_QUAD source=arc gpu=citro3d\n";
        svcOutputDebugString(message, sizeof(message) - 1);
        reported = true;
    }
    draws += pieces;
    return 1;
#else
    (void)page; (void)width; (void)height; (void)vertices; (void)offset; (void)blend_mode;
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
    if (!palette_buffer) palette_buffer = C2D_TextBufNew(256);
    if (!catalog_buffer) catalog_buffer = C2D_TextBufNew(512);
    if (!palette_buffer || !catalog_buffer) return 0;
    if (!palette) {
        // Allocate both screen outputs before the world cache uses the linear heap.
        gfxSetScreenFormat(GFX_BOTTOM, GSP_BGR8_OES);
        gfxSetDoubleBuffering(GFX_BOTTOM, true);
        palette = C2D_CreateScreenTarget(GFX_BOTTOM, GFX_LEFT);
        if (!palette) return 0;
    }
    C2D_TextBufClear(palette_buffer);
    for (size_t i = 0; i < sizeof(palette_labels) / sizeof(palette_labels[0]); i++) {
        C2D_TextParse(&palette_labels[i], palette_buffer, labels);
        C2D_TextOptimize(&palette_labels[i]);
        labels += strlen(labels) + 1;
    }
    return 1;
}

int32_t m3ds_terrain_build_overlay(int32_t column, int32_t row, int32_t block, int32_t rotation,
                                  int32_t removing, int32_t tile_size, int32_t rotatable, int32_t catalog_open,
                                  int32_t configuring, int32_t selecting_mine) {
    if (!frame_open || !palette) return 0;
    set_world_blend(0);
    set_world_mix(0, 0);
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
        if (rotatable && !removing && !selecting_mine) {
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
    C2D_DrawRectSolid(292, 80, 0.5f, 26, 31, C2D_Color32(45, 52, 64, 255));
    C2D_DrawText(&palette_labels[7], C2D_WithColor | C2D_AlignCenter, 305, 89, 0.5f,
        0.30f, 0.30f, selecting_mine ? accent : C2D_Color32(239, 241, 247, 255));
    for (int i = 0; i < 4; i++) {
        bool active = !selecting_mine && (removing ? i == 3 : i == block);
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

float m3ds_terrain_draw_objective(const char *label, int32_t page, int32_t left, int32_t top,
                                 int32_t width, int32_t height, float y) {
    if (!frame_open || !catalog_buffer) return 240.0f;
    C2D_Text text;
    C2D_TextBufClear(catalog_buffer);
    if (!C2D_TextParse(&text, catalog_buffer, label)) return 240.0f;
    C2D_TextOptimize(&text);
    bool has_icon = page >= 0 && width > 0 && height > 0;
    float inset = has_icon ? 32.0f : 6.0f;
    float font_scale = fminf(0.44f, (230.0f - inset) / fmaxf(1.0f, text.width));
    float text_width, text_height;
    C2D_TextGetDimensions(&text, font_scale, font_scale, &text_width, &text_height);
    float panel_height = fmaxf(has_icon ? 32.0f : 0.0f, text_height + 10.0f);
    if (y + panel_height > screen_bottom - 4.0f) return 240.0f;

    set_world_blend(0);
    set_world_mix(0, 0);
    float x = screen_left + 4.0f;
    C2D_DrawRectSolid(x, y, 0.5f, inset + text_width + 6.0f, panel_height, C2D_Color32(24, 27, 32, 224));
    if (has_icon) {
        float icon_scale = 20.0f / fmaxf((float)width, (float)height);
        float icon_width = width * icon_scale, icon_height = height * icon_scale;
        float icon_x = x + 6.0f + (20.0f - icon_width) * 0.5f;
        float icon_y = y + (panel_height + icon_height) * 0.5f;
        if (!m3ds_terrain_draw(page, left, top, width, height,
                world_left + (icon_x - screen_left) / scale, world_bottom + (screen_bottom - icon_y) / scale,
                0.0f, 0.0f, icon_width / scale, icon_height / scale, 0.0f, -1, 0, 0)) {
            return 240.0f;
        }
    }
    C2D_DrawText(&text, C2D_WithColor, x + inset, y + 5.0f, 0.5f, font_scale, font_scale,
        C2D_Color32(239, 241, 247, 255));
    return y + panel_height + 3.0f;
}

int32_t m3ds_terrain_draw_world_label(const char *label, float x, float y, int32_t flags, float size,
                                     int32_t horizontal, int32_t vertical, int32_t lines) {
    if (!frame_open || !catalog_buffer || !(size > 0.0f) || !isfinite(size)) return 0;
    C2D_Text text;
    C2D_TextBufClear(catalog_buffer);
    const char *end = C2D_TextParse(&text, catalog_buffer, label);
    if (!end || *end != '\0') return 0;
    const float screen_right = 400.0f - screen_left;
    const float screen_top = 240.0f - screen_bottom;
    // Pinned WorldLabel uses the 18 px game font at quarter scale in world units.
    // Citro2D's system font is 30 px. Fit long translations inside the 3DS viewport.
    float scale_y = 0.25f * (18.0f / 30.0f) * size * scale;
    float scale_x = fminf(scale_y, (screen_right - screen_left - 8.0f) / fmaxf(1.0f, text.width));
    float width, height;
    C2D_TextGetDimensions(&text, scale_x, scale_y, &width, &height);
    float sx = screen_left + (x - world_left) * scale;
    float sy = screen_bottom - (y - world_bottom) * scale;
    bool anchor_visible = sx >= screen_left && sx <= screen_right;
    bool background = (flags & 1) != 0;
    float border = background ? scale : 0.0f;
    sx -= width * 0.5f * (1 + horizontal) + border * horizontal;
    sy -= height * 0.5f * (1 + vertical) + border * 1.5f * vertical;
    if (anchor_visible) {
        // A world marker near an edge can still show its whole prompt while the anchor is in view.
        sx = fmaxf(screen_left + 4.0f, fminf(sx, screen_right - 4.0f - width));
    }
    if (sx + width < screen_left || sx > screen_right || sy + height < screen_top || sy > screen_bottom) return 0;

    C2D_TextOptimize(&text);
    set_world_blend(0);
    set_world_mix(0, 0);
    if (background) {
        C2D_DrawRectSolid(sx - border, sy - 1.5f * border, 0.5f, width + 2.0f * border,
            height + 3.0f * border, C2D_Color32(0, 0, 0, 77));
    }
    u32 align = lines < 0 ? C2D_AlignLeft : lines > 0 ? C2D_AlignRight : C2D_AlignCenter;
    sx += width * 0.5f * (1 + lines);
    if ((flags & 2) != 0) {
        C2D_DrawText(&text, C2D_WithColor | align, sx + 0.75f, sy + 1.0f, 0.5f, scale_x, scale_y,
            C2D_Color32(45, 45, 45, 255));
    }
    C2D_DrawText(&text, C2D_WithColor | align, sx, sy, 0.5f, scale_x, scale_y, C2D_Color32(255, 255, 255, 255));
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
    if (quad_shader) {
        shaderProgramFree(&quad_program);
        DVLB_Free(quad_shader);
        quad_shader = NULL;
    }
    for (int i = 0; i < canvas_texture_slots; i++) {
        if (canvas_textures[i].tex.data) C3D_TexDelete(&canvas_textures[i].tex);
    }
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
