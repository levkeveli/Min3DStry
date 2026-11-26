#include "m3ds_planet_markers.h"
#include "m3ds_quad_shbin.h"

#include <3ds.h>
#include <citro3d.h>
#include <tex3ds.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>

enum { marker_limit = 272, marker_fields = 13, glyph_kind = -1, preset_kind = -2 };

typedef struct {
    float position[3], uv[2], color[4];
} MarkerVertex;

typedef struct {
    bool assigned, loaded;
    int32_t kind, id;
    C3D_Tex tex;
    Tex3DS_SubTexture area;
} MarkerTexture;

static struct {
    MarkerTexture assets[marker_limit];
    MarkerVertex *vertices;
    DVLB_s *shader;
    shaderProgram_s program;
    C3D_AttrInfo attributes;
    int projection;
} markers;

static bool read_icon(MarkerTexture *entry, const char *path) {
    FILE *file = fopen(path, "rb");
    if (!file) return false;
    Tex3DS_Texture imported = Tex3DS_TextureImportStdio(file, &entry->tex, NULL, true);
    if (!imported) {
        rewind(file);
        imported = Tex3DS_TextureImportStdio(file, &entry->tex, NULL, false);
    }
    fclose(file);
    if (!imported) return false;
    const Tex3DS_SubTexture *region = Tex3DS_GetSubTexture(imported, 0);
    bool valid = region && region->width && region->height && !Tex3DS_SubTextureRotated(region);
    if (valid) entry->area = *region;
    Tex3DS_TextureFree(imported);
    if (!valid) C3D_TexDelete(&entry->tex);
    else C3D_TexSetFilter(&entry->tex, GPU_LINEAR, GPU_LINEAR);
    return valid;
}

static void retain_visible_icons(const int32_t *ids, int32_t count) {
    for (size_t i = 0; i < marker_limit; i++) {
        MarkerTexture *entry = markers.assets + i;
        if (!entry->assigned) continue;
        int32_t j = 0;
        for (; j < count; j++) {
            if (entry->kind == ids[j * 2] && entry->id == ids[j * 2 + 1]) break;
        }
        if (j < count) continue;
        if (entry->loaded) C3D_TexDelete(&entry->tex);
        *entry = (MarkerTexture){0};
    }
}

static MarkerTexture *icon(int32_t kind, int32_t id) {
    MarkerTexture *free_entry = NULL;
    for (size_t i = 0; i < marker_limit; i++) {
        MarkerTexture *entry = markers.assets + i;
        if (entry->assigned && entry->kind == kind && entry->id == id) {
            return entry->loaded ? entry : NULL;
        }
        if (!entry->assigned && !free_entry) free_entry = entry;
    }
    if (!free_entry) return NULL;
    *free_entry = (MarkerTexture){.assigned = true, .kind = kind, .id = id};

    char path[80];
    if (kind == glyph_kind) snprintf(path, sizeof(path), "romfs:/planet-icons/glyph-%ld.t3x", (long)id);
    else if (kind == preset_kind) snprintf(path, sizeof(path), "romfs:/planet-icons/preset-%ld.t3x", (long)id);
    else snprintf(path, sizeof(path), "romfs:/planet-icons/content-%ld-%ld.t3x", (long)kind, (long)id);
    free_entry->loaded = read_icon(free_entry, path);
    if (free_entry->loaded) {
        char record[96];
        int size = snprintf(record, sizeof(record), "UPSTREAM_CAMPAIGN_MARKER_ASSET kind=%ld id=%ld",
            (long)kind, (long)id);
        if (size > 0 && size < (int)sizeof(record)) svcOutputDebugString(record, size);
    } else if (kind == preset_kind) {
        free_entry->loaded = read_icon(free_entry, "romfs:/planet-icons/preset-terrain.t3x");
    }
    if (!free_entry->loaded) free_entry->loaded = read_icon(free_entry, "romfs:/planet-icons/error.t3x");
    return free_entry->loaded ? free_entry : NULL;
}

static bool setup_markers(void) {
    if (!markers.vertices) markers.vertices = linearAlloc(marker_limit * 6 * sizeof(MarkerVertex));
    if (!markers.vertices) return false;
    if (markers.shader) return true;
    markers.shader = DVLB_ParseFile((u32 *)m3ds_quad_shbin, m3ds_quad_shbin_size);
    if (!markers.shader) return false;
    shaderProgramInit(&markers.program);
    shaderProgramSetVsh(&markers.program, &markers.shader->DVLE[0]);
    markers.projection = shaderInstanceGetUniformLocation(markers.program.vertexShader, "projection");
    AttrInfo_Init(&markers.attributes);
    AttrInfo_AddLoader(&markers.attributes, 0, GPU_FLOAT, 3);
    AttrInfo_AddLoader(&markers.attributes, 1, GPU_FLOAT, 2);
    AttrInfo_AddLoader(&markers.attributes, 2, GPU_FLOAT, 4);
    return true;
}

static void set_vertex(MarkerVertex *vertex, const float *plane, float x, float y, float u, float v) {
    for (int axis = 0; axis < 3; axis++) {
        vertex->position[axis] = plane[axis] + x * plane[3 + axis] + y * plane[6 + axis];
    }
    vertex->uv[0] = u;
    vertex->uv[1] = v;
    memcpy(vertex->color, plane + 9, sizeof(vertex->color));
}

int32_t m3ds_planet_draw_markers(const C3D_Mtx *projection, const int32_t *ids, const float *planes,
    int32_t count) {
    if (!ids || !planes || count < 0 || count > marker_limit) return 0;
    // FrameBegin synchronized the previous frame, so textures absent from this batch can retire.
    retain_visible_icons(ids, count);
    if (!count || !setup_markers()) return 0;
    MarkerTexture *visible[marker_limit];
    int32_t drawn = 0;
    for (int32_t i = 0; i < count; i++) {
        MarkerTexture *asset = icon(ids[i * 2], ids[i * 2 + 1]);
        if (!asset) continue;
        visible[drawn] = asset;
        const float *plane = planes + i * marker_fields;
        const float x = 8.0f / 150.0f, y = x * asset->area.height / asset->area.width;
        MarkerVertex *quad = markers.vertices + drawn * 6;
        set_vertex(quad, plane, -x, -y, asset->area.left, asset->area.bottom);
        set_vertex(quad + 1, plane, x, -y, asset->area.right, asset->area.bottom);
        set_vertex(quad + 2, plane, x, y, asset->area.right, asset->area.top);
        quad[3] = quad[0];
        quad[4] = quad[2];
        set_vertex(quad + 5, plane, -x, y, asset->area.left, asset->area.top);
        drawn++;
    }
    if (!drawn) return 0;
    GSPGPU_FlushDataCache(markers.vertices, drawn * 6 * sizeof(MarkerVertex));

    C3D_BufInfo buffers;
    BufInfo_Init(&buffers);
    BufInfo_Add(&buffers, markers.vertices, sizeof(MarkerVertex), 3, 0x210);
    C3D_BindProgram(&markers.program);
    C3D_SetAttrInfo(&markers.attributes);
    C3D_SetBufInfo(&buffers);
    C3D_FVUnifMtx4x4(GPU_VERTEX_SHADER, markers.projection, projection);
    C3D_DepthTest(true, GPU_GEQUAL, GPU_WRITE_COLOR);
    C3D_CullFace(GPU_CULL_NONE);
    C3D_AlphaBlend(GPU_BLEND_ADD, GPU_BLEND_ADD, GPU_SRC_ALPHA, GPU_ONE_MINUS_SRC_ALPHA,
        GPU_ONE, GPU_ONE_MINUS_SRC_ALPHA);
    for (int i = 0; i < 6; i++) C3D_TexEnvInit(C3D_GetTexEnv(i));
    C3D_TexEnv *env = C3D_GetTexEnv(0);
    C3D_TexEnvSrc(env, C3D_Both, GPU_TEXTURE0, GPU_PRIMARY_COLOR, GPU_PRIMARY_COLOR);
    C3D_TexEnvFunc(env, C3D_Both, GPU_MODULATE);
    for (int32_t i = 0; i < drawn; i++) {
        C3D_TexBind(0, &visible[i]->tex);
        C3D_DrawArrays(GPU_TRIANGLES, i * 6, 6);
    }
    return drawn;
}

void m3ds_planet_release_markers(void) {
    for (size_t i = 0; i < marker_limit; i++) {
        if (markers.assets[i].loaded) C3D_TexDelete(&markers.assets[i].tex);
        markers.assets[i] = (MarkerTexture){0};
    }
    if (markers.vertices) linearFree(markers.vertices);
    markers.vertices = NULL;
    if (markers.shader) {
        shaderProgramFree(&markers.program);
        DVLB_Free(markers.shader);
        markers.shader = NULL;
    }
}
