#include "m3ds_planet_mesh.h"
#include "m3ds_planet_shbin.h"

#include <3ds.h>
#include <citro3d.h>
#include <math.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum { serpulo, erekir, planet_count, max_overlay_vertices = UINT16_MAX };

typedef struct {
    float position[3];
    float color[4];
} PlanetVertex;

static struct {
    int planet;
    uint32_t stride, vertices, indices;
    float *stock;
    PlanetVertex *draw;
    PlanetVertex *overlay;
    size_t overlay_capacity;
    uint16_t *index;
    DVLB_s *shader;
    shaderProgram_s program;
    C3D_AttrInfo attributes;
    int projection;
} mesh = {.planet = -1};

static void release_faces(void) {
    free(mesh.stock);
    if (mesh.draw) linearFree(mesh.draw);
    if (mesh.index) linearFree(mesh.index);
    mesh.stock = NULL;
    mesh.draw = NULL;
    mesh.index = NULL;
    mesh.planet = -1;
}

void m3ds_planet_release(void) {
    release_faces();
    if (mesh.overlay) linearFree(mesh.overlay);
    mesh.overlay = NULL;
    mesh.overlay_capacity = 0;
    if (mesh.shader) {
        shaderProgramFree(&mesh.program);
        DVLB_Free(mesh.shader);
        mesh.shader = NULL;
    }
}

static bool load_faces(int32_t planet) {
    static const char *const assets[planet_count] = {
        "romfs:/planet-meshes/serpulo.m3pm", "romfs:/planet-meshes/erekir.m3pm",
    };
    if (planet == mesh.planet) return true;
    release_faces();
    FILE *file = fopen(assets[planet], "rb");
    if (!file) return false;
    uint32_t header[5];
    bool valid = fread(header, sizeof(*header), 5, file) == 5
        && header[0] == 0x4d50334d && header[1] == 1
        && header[2] == (planet == serpulo ? 8 : 7)
        && header[3] > 0 && header[3] <= UINT16_MAX
        && header[4] > 0 && header[4] <= 3 * UINT16_MAX && header[4] % 3 == 0;
    if (valid) {
        mesh.stride = header[2];
        mesh.vertices = header[3];
        mesh.indices = header[4];
        size_t words = (size_t)mesh.stride * mesh.vertices;
        mesh.stock = malloc(words * sizeof(float));
        mesh.draw = linearAlloc(mesh.vertices * sizeof(PlanetVertex));
        mesh.index = linearAlloc(mesh.indices * sizeof(uint16_t));
        valid = mesh.stock && mesh.draw && mesh.index
            && fread(mesh.stock, sizeof(float), words, file) == words
            && fread(mesh.index, sizeof(uint16_t), mesh.indices, file) == mesh.indices
            && fgetc(file) == EOF && !ferror(file);
    }
    fclose(file);
    for (uint32_t i = 0; valid && i < mesh.indices; i++) {
        valid = mesh.index[i] < mesh.vertices;
    }
    if (!valid) {
        release_faces();
        return false;
    }
    GSPGPU_FlushDataCache(mesh.index, mesh.indices * sizeof(uint16_t));
    mesh.planet = planet;
    char record[100];
    int size = snprintf(record, sizeof(record), "UPSTREAM_PLANET_MESH_DRAW planet=%d vertices=%lu indices=%lu",
        (int)planet, (unsigned long)mesh.vertices, (unsigned long)mesh.indices);
    if (size > 0 && size < (int)sizeof(record)) svcOutputDebugString(record, size);
    return true;
}

static bool load_shader(void) {
    if (mesh.shader) return true;
    mesh.shader = DVLB_ParseFile((u32 *)m3ds_planet_shbin, m3ds_planet_shbin_size);
    if (!mesh.shader) return false;
    shaderProgramInit(&mesh.program);
    shaderProgramSetVsh(&mesh.program, &mesh.shader->DVLE[0]);
    mesh.projection = shaderInstanceGetUniformLocation(mesh.program.vertexShader, "projection");
    AttrInfo_Init(&mesh.attributes);
    AttrInfo_AddLoader(&mesh.attributes, 0, GPU_FLOAT, 3);
    AttrInfo_AddLoader(&mesh.attributes, 1, GPU_FLOAT, 4);
    return true;
}

static float dot(const float *a, const float *b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

static float color_channel(uint32_t color, unsigned channel) {
    return ((color >> (8 * channel)) & 255) / 255.0f;
}

static void shade_vertices(const float *light, const float *ambient, const float *eye) {
    // Translate the color calculation from Mindustry v160.8 planet.vert.
    for (uint32_t i = 0; i < mesh.vertices; i++) {
        const float *position = mesh.stock + i * mesh.stride;
        const float *normal = position + 3;
        float eye_vector[3] = {eye[0] - position[0], eye[1] - position[1], eye[2] - position[2]};
        float nl = dot(normal, light);
        // Both inputs to reflect in the original shader are unit vectors.
        float reflection[3] = {normal[0] - 2 * nl * light[0],
            normal[1] - 2 * nl * light[1], normal[2] - 2 * nl * light[2]};

        uint32_t surface, emission = 0;
        memcpy(&surface, position + 6, sizeof(surface));
        if (mesh.stride == 8) memcpy(&emission, position + 7, sizeof(emission));

        float highlight = fmaxf(dot(eye_vector, reflection) / sqrtf(dot(eye_vector, eye_vector)), 0);
        float specular = powf(highlight, 40) * (1 - color_channel(surface, 3));
        float diffuse = 0.01f + fminf(fmaxf((nl + 1) * 0.5f, 0), 1);
        float darkness = fmaxf((1 - (ambient[0] + specular) * diffuse) * 1.2f, 0);
        float emissive = color_channel(emission, 3) * fminf(darkness * darkness * darkness, 1.1f);

        PlanetVertex *vertex = &mesh.draw[i];
        memcpy(vertex->position, position, sizeof(vertex->position));
        for (unsigned channel = 0; channel < 3; channel++) {
            float base = color_channel(surface, channel);
            float color = base + (color_channel(emission, channel) - base) * emissive;
            float shaded = (ambient[channel] + specular) * diffuse;
            vertex->color[channel] = color * (shaded + (1 - shaded) * emissive);
        }
        vertex->color[3] = 1;
    }
    GSPGPU_FlushDataCache(mesh.draw, mesh.vertices * sizeof(PlanetVertex));
}

int32_t m3ds_planet_draw(int32_t planet, const float *matrix, const float *lighting,
    const float *overlay, int32_t vertices, int32_t sector_vertices) {
    if (planet < 0 || planet >= planet_count || vertices < 0 || vertices > max_overlay_vertices
        || sector_vertices < 0 || sector_vertices > vertices || sector_vertices % 3 != 0
        || vertices % 3 != 0 || !load_faces(planet) || !load_shader()) return 0;
    if ((size_t)vertices > mesh.overlay_capacity) {
        size_t capacity = mesh.overlay_capacity ? mesh.overlay_capacity * 2 : 4096;
        while (capacity < (size_t)vertices) capacity *= 2;
        if (capacity > max_overlay_vertices) capacity = max_overlay_vertices;
        PlanetVertex *buffer = linearAlloc(capacity * sizeof(PlanetVertex));
        if (!buffer) return 0;
        if (mesh.overlay) linearFree(mesh.overlay);
        mesh.overlay = buffer;
        mesh.overlay_capacity = capacity;
    }
    shade_vertices(lighting, lighting + 3, lighting + 6);

    // Convert Arc's column-major clip matrix to PICA's tilted framebuffer and reversed depth.
    C3D_Mtx projection;
    projection.r[0] = FVec4_New(matrix[1], matrix[5], matrix[9], matrix[13]);
    projection.r[1] = FVec4_New(-matrix[0], -matrix[4], -matrix[8], -matrix[12]);
    projection.r[2] = FVec4_New(-0.5f * (matrix[2] + matrix[3]), -0.5f * (matrix[6] + matrix[7]),
        -0.5f * (matrix[10] + matrix[11]), -0.5f * (matrix[14] + matrix[15]));
    projection.r[3] = FVec4_New(matrix[3], matrix[7], matrix[11], matrix[15]);

    C3D_BufInfo buffers;
    BufInfo_Init(&buffers);
    BufInfo_Add(&buffers, mesh.draw, sizeof(PlanetVertex), 2, 0x10);
    C3D_BindProgram(&mesh.program);
    C3D_SetAttrInfo(&mesh.attributes);
    C3D_SetBufInfo(&buffers);
    C3D_FVUnifMtx4x4(GPU_VERTEX_SHADER, mesh.projection, &projection);
    C3D_DepthTest(true, GPU_GEQUAL, GPU_WRITE_ALL);
    for (int i = 0; i < 6; i++) C3D_TexEnvInit(C3D_GetTexEnv(i));
    C3D_TexEnvSrc(C3D_GetTexEnv(0), C3D_Both, GPU_PRIMARY_COLOR, GPU_PRIMARY_COLOR, GPU_PRIMARY_COLOR);
    C3D_TexEnvFunc(C3D_GetTexEnv(0), C3D_Both, GPU_REPLACE);
    C3D_DrawElements(GPU_TRIANGLES, (int)mesh.indices, C3D_UNSIGNED_SHORT, mesh.index);
    if (vertices) {
        size_t bytes = (size_t)vertices * sizeof(PlanetVertex);
        memcpy(mesh.overlay, overlay, bytes);
        GSPGPU_FlushDataCache(mesh.overlay, bytes);
        BufInfo_Init(&buffers);
        BufInfo_Add(&buffers, mesh.overlay, sizeof(PlanetVertex), 2, 0x10);
        C3D_SetBufInfo(&buffers);
        C3D_CullFace(GPU_CULL_BACK_CCW);
        C3D_DepthTest(true, GPU_GEQUAL, GPU_WRITE_ALL);
        C3D_AlphaBlend(GPU_BLEND_ADD, GPU_BLEND_ADD, GPU_SRC_ALPHA, GPU_ONE_MINUS_SRC_ALPHA,
            GPU_ONE, GPU_ONE_MINUS_SRC_ALPHA);
        if (sector_vertices) C3D_DrawArrays(GPU_TRIANGLES, 0, sector_vertices);
        C3D_CullFace(GPU_CULL_NONE);
        if (sector_vertices < vertices) {
            C3D_DepthTest(true, GPU_GREATER, GPU_WRITE_ALL);
            C3D_DrawArrays(GPU_TRIANGLES, sector_vertices, vertices - sector_vertices);
        }
    }
    return 1;
}
