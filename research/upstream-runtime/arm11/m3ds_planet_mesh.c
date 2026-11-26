#include "m3ds_planet_mesh.h"
#include "m3ds_planet_shbin.h"
#ifdef M3DS_CAMPAIGN_PLANET_MARKERS
#include "m3ds_planet_markers.h"
#endif

#include <3ds.h>
#include <citro3d.h>
#include <math.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

enum {
    serpulo, erekir, sun, gier, notva, verilus,
    serpulo_cloud_0, serpulo_cloud_1, erekir_cloud_0, erekir_cloud_1,
    planet_count, max_overlay_vertices = UINT16_MAX
};

typedef struct {
    float position[3];
    float color[4];
} PlanetVertex;

typedef struct {
    bool surface_applied;
    bool reported_markers;
    uint32_t stride, vertices, indices;
    float *stock;
    PlanetVertex *draw;
    uint16_t *index;
} PlanetFaces;

static PlanetFaces bodies[planet_count];

static struct {
    PlanetVertex *overlay;
    size_t overlay_capacity;
    DVLB_s *shader;
    shaderProgram_s program;
    C3D_AttrInfo attributes;
    int projection;
} mesh;

static void release_faces(PlanetFaces *faces) {
    free(faces->stock);
    if (faces->draw) linearFree(faces->draw);
    if (faces->index) linearFree(faces->index);
    *faces = (PlanetFaces){0};
}

void m3ds_planet_release(void) {
    for (int planet = 0; planet < planet_count; planet++) release_faces(&bodies[planet]);
#ifdef M3DS_CAMPAIGN_PLANET_MARKERS
    m3ds_planet_release_markers();
#endif
    if (mesh.overlay) linearFree(mesh.overlay);
    mesh.overlay = NULL;
    mesh.overlay_capacity = 0;
    if (mesh.shader) {
        shaderProgramFree(&mesh.program);
        DVLB_Free(mesh.shader);
        mesh.shader = NULL;
    }
}

static bool load_faces(PlanetFaces *faces, int32_t planet) {
    static const char *const assets[planet_count] = {
        [serpulo] = "romfs:/planet-meshes/serpulo.m3pm",
        [erekir] = "romfs:/planet-meshes/erekir.m3pm",
        [sun] = "romfs:/planet-meshes/sun.m3pm",
        [gier] = "romfs:/planet-meshes/gier.m3pm",
        [notva] = "romfs:/planet-meshes/notva.m3pm",
        [verilus] = "romfs:/planet-meshes/verilus.m3pm",
        [serpulo_cloud_0] = "romfs:/planet-meshes/serpulo-cloud-0.m3pm",
        [serpulo_cloud_1] = "romfs:/planet-meshes/serpulo-cloud-1.m3pm",
        [erekir_cloud_0] = "romfs:/planet-meshes/erekir-cloud-0.m3pm",
        [erekir_cloud_1] = "romfs:/planet-meshes/erekir-cloud-1.m3pm",
    };
    if (faces->stock) return true;
    FILE *file = fopen(assets[planet], "rb");
    if (!file) return false;
    uint32_t header[5];
    bool valid = fread(header, sizeof(*header), 5, file) == 5
        && header[0] == 0x4d50334d && header[1] == 1
        && header[2] == (planet == serpulo ? 8 : 7)
        && header[3] > 0 && header[3] <= UINT16_MAX
        && header[4] > 0 && header[4] <= 3 * UINT16_MAX && header[4] % 3 == 0;
    if (valid) {
        faces->stride = header[2];
        faces->vertices = header[3];
        faces->indices = header[4];
        size_t words = (size_t)faces->stride * faces->vertices;
        faces->stock = malloc(words * sizeof(float));
        faces->draw = linearAlloc(faces->vertices * sizeof(PlanetVertex));
        faces->index = linearAlloc(faces->indices * sizeof(uint16_t));
        valid = faces->stock && faces->draw && faces->index
            && fread(faces->stock, sizeof(float), words, file) == words
            && fread(faces->index, sizeof(uint16_t), faces->indices, file) == faces->indices
            && fgetc(file) == EOF && !ferror(file);
    }
    fclose(file);
    for (uint32_t i = 0; valid && i < faces->indices; i++) {
        valid = faces->index[i] < faces->vertices;
    }
    if (!valid) {
        release_faces(faces);
        return false;
    }
    GSPGPU_FlushDataCache(faces->index, faces->indices * sizeof(uint16_t));
    char record[100];
    int size = snprintf(record, sizeof(record), "UPSTREAM_PLANET_MESH_DRAW planet=%d vertices=%lu indices=%lu",
        (int)planet, (unsigned long)faces->vertices, (unsigned long)faces->indices);
    if (size > 0 && size < (int)sizeof(record)) svcOutputDebugString(record, size);
    return true;
}

static bool update_surface(PlanetFaces *body, const int32_t *surface, int32_t faces) {
    if (faces == 0 || body->surface_applied) return true;
    if (body->stride != 8 || !surface || faces < 0 || (uint32_t)faces > body->vertices / 5) {
        return false;
    }
    uint32_t count = 0;
    for (int32_t i = 0; i < faces; i++) {
        int32_t size = surface[i * 3];
        if (size != 5 && size != 6) return false;
        count += size;
    }
    if (count != body->vertices) return false;

    float *vertex = body->stock;
    for (int32_t i = 0; i < faces; i++) {
        const int32_t *face = surface + i * 3;
        for (int32_t j = 0; j < face[0]; j++, vertex += body->stride) {
            memcpy(vertex + 6, face + 1, 2 * sizeof(*face));
        }
    }
    body->surface_applied = true;
    char record[96];
    int size = snprintf(record, sizeof(record), "UPSTREAM_CAMPAIGN_SURFACE_DRAW faces=%ld vertices=%lu",
        (long)faces, (unsigned long)body->vertices);
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

static void shade_vertices(PlanetFaces *body, const float *light, const float *ambient, const float *eye, bool unlit) {
    // Translate the color calculation from Mindustry v160.8 planet.vert.
    for (uint32_t i = 0; i < body->vertices; i++) {
        const float *position = body->stock + i * body->stride;
        PlanetVertex *vertex = &body->draw[i];
        memcpy(vertex->position, position, sizeof(vertex->position));
        uint32_t surface, emission = 0;
        memcpy(&surface, position + 6, sizeof(surface));
        if (unlit) {
            for (unsigned channel = 0; channel < 3; channel++) vertex->color[channel] = color_channel(surface, channel);
            vertex->color[3] = 1;
            continue;
        }
        if (body->stride == 8) memcpy(&emission, position + 7, sizeof(emission));
        const float *normal = position + 3;
        float eye_vector[3] = {eye[0] - position[0], eye[1] - position[1], eye[2] - position[2]};
        float nl = dot(normal, light);
        // Both inputs to reflect in the original shader are unit vectors.
        float reflection[3] = {normal[0] - 2 * nl * light[0],
            normal[1] - 2 * nl * light[1], normal[2] - 2 * nl * light[2]};

        float highlight = fmaxf(dot(eye_vector, reflection) / sqrtf(dot(eye_vector, eye_vector)), 0);
        float specular = powf(highlight, 40) * (1 - color_channel(surface, 3));
        float diffuse = 0.01f + fminf(fmaxf((nl + 1) * 0.5f, 0), 1);
        float darkness = fmaxf((1 - (ambient[0] + specular) * diffuse) * 1.2f, 0);
        float emissive = color_channel(emission, 3) * fminf(darkness * darkness * darkness, 1.1f);

        for (unsigned channel = 0; channel < 3; channel++) {
            float base = color_channel(surface, channel);
            float color = base + (color_channel(emission, channel) - base) * emissive;
            float shaded = (ambient[channel] + specular) * diffuse;
            vertex->color[channel] = color * (shaded + (1 - shaded) * emissive);
        }
        vertex->color[3] = 1;
    }
    GSPGPU_FlushDataCache(body->draw, body->vertices * sizeof(PlanetVertex));
}

static void shade_cloud_vertices(PlanetFaces *body, const float *light, const float *ambient) {
    // The overview has no focused planet, so stock HexSkyMesh supplies u_alpha = 1.
    for (uint32_t i = 0; i < body->vertices; i++) {
        const float *stock = body->stock + i * body->stride;
        PlanetVertex *vertex = &body->draw[i];
        memcpy(vertex->position, stock, sizeof(vertex->position));
        uint32_t color;
        memcpy(&color, stock + 6, sizeof(color));
        float diffuse = 0.01f + fminf(fmaxf((dot(stock + 3, light) + 1) * 0.5f, 0), 1);
        for (unsigned channel = 0; channel < 3; channel++) {
            vertex->color[channel] = color_channel(color, channel) * ambient[channel] * diffuse;
        }
        vertex->color[3] = color_channel(color, 3);
    }
    GSPGPU_FlushDataCache(body->draw, body->vertices * sizeof(PlanetVertex));
}

static C3D_Mtx screen_projection(const float *matrix) {
    // Convert Arc's column-major clip matrix to PICA's tilted framebuffer and reversed depth.
    C3D_Mtx projection;
    projection.r[0] = FVec4_New(matrix[1], matrix[5], matrix[9], matrix[13]);
    projection.r[1] = FVec4_New(-matrix[0], -matrix[4], -matrix[8], -matrix[12]);
    projection.r[2] = FVec4_New(-0.5f * (matrix[2] + matrix[3]), -0.5f * (matrix[6] + matrix[7]),
        -0.5f * (matrix[10] + matrix[11]), -0.5f * (matrix[14] + matrix[15]));
    projection.r[3] = FVec4_New(matrix[3], matrix[7], matrix[11], matrix[15]);
    return projection;
}

static bool reserve_overlay(int32_t vertices) {
    if ((size_t)vertices > mesh.overlay_capacity) {
        size_t capacity = mesh.overlay_capacity ? mesh.overlay_capacity * 2 : 4096;
        while (capacity < (size_t)vertices) capacity *= 2;
        if (capacity > max_overlay_vertices) capacity = max_overlay_vertices;
        PlanetVertex *buffer = linearAlloc(capacity * sizeof(PlanetVertex));
        if (!buffer) {
            char record[104];
            int size = snprintf(record, sizeof(record), "UPSTREAM_CAMPAIGN_OVERLAY_MEMORY vertices=%ld bytes=%lu free=%lu",
                (long)vertices, (unsigned long)(capacity * sizeof(PlanetVertex)), (unsigned long)linearSpaceFree());
            if (size > 0 && size < (int)sizeof(record)) svcOutputDebugString(record, size);
            return false;
        }
        if (mesh.overlay) linearFree(mesh.overlay);
        mesh.overlay = buffer;
        mesh.overlay_capacity = capacity;
    }
    return true;
}

static void bind_scene(const float *matrix) {
    C3D_Mtx projection = screen_projection(matrix);
    C3D_BindProgram(&mesh.program);
    C3D_SetAttrInfo(&mesh.attributes);
    C3D_FVUnifMtx4x4(GPU_VERTEX_SHADER, mesh.projection, &projection);
    C3D_DepthTest(true, GPU_GEQUAL, GPU_WRITE_ALL);
    for (int i = 0; i < 6; i++) C3D_TexEnvInit(C3D_GetTexEnv(i));
    C3D_TexEnvSrc(C3D_GetTexEnv(0), C3D_Both, GPU_PRIMARY_COLOR, GPU_PRIMARY_COLOR, GPU_PRIMARY_COLOR);
    C3D_TexEnvFunc(C3D_GetTexEnv(0), C3D_Both, GPU_REPLACE);
}

static void bind_vertices(PlanetVertex *vertices) {
    C3D_BufInfo buffers;
    BufInfo_Init(&buffers);
    BufInfo_Add(&buffers, vertices, sizeof(PlanetVertex), 2, 0x10);
    C3D_SetBufInfo(&buffers);
}

static void draw_overlay(const float *overlay, int32_t vertices, int32_t sector_vertices) {
    size_t bytes = (size_t)vertices * sizeof(PlanetVertex);
    memcpy(mesh.overlay, overlay, bytes);
    GSPGPU_FlushDataCache(mesh.overlay, bytes);
    bind_vertices(mesh.overlay);
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

int32_t m3ds_planet_draw_orbits(const float *matrix, const float *overlay, int32_t vertices) {
    if (vertices == 0) return 1;
    if (vertices < 0 || vertices > max_overlay_vertices || vertices % 3 != 0
        || !load_shader() || !reserve_overlay(vertices)) return 0;
    bind_scene(matrix);
    draw_overlay(overlay, vertices, 0);
    return 1;
}

int32_t m3ds_planet_draw(int32_t planet, const float *matrix, const float *lighting,
    const float *overlay, int32_t vertices, int32_t sector_vertices, const int32_t *surface, int32_t faces,
    const int32_t *ids, const float *planes, const float *marker_matrix, int32_t markers) {
    if (planet < 0 || planet >= planet_count || vertices < 0 || vertices > max_overlay_vertices
        || sector_vertices < 0 || sector_vertices > vertices || sector_vertices % 3 != 0
        || vertices % 3 != 0) return 0;
    PlanetFaces *body = &bodies[planet];
    if (!load_faces(body, planet) || !load_shader() || !update_surface(body, surface, faces)
        || !reserve_overlay(vertices)) return 0;
    bool cloud = planet >= serpulo_cloud_0;
    if (cloud) shade_cloud_vertices(body, lighting, lighting + 3);
    else shade_vertices(body, lighting, lighting + 3, lighting + 6, planet == sun);
    bind_scene(matrix);
    bind_vertices(body->draw);
    if (cloud) {
        C3D_CullFace(GPU_CULL_BACK_CCW);
        C3D_AlphaBlend(GPU_BLEND_ADD, GPU_BLEND_ADD, GPU_SRC_ALPHA, GPU_ONE_MINUS_SRC_ALPHA,
            GPU_ONE, GPU_ONE_MINUS_SRC_ALPHA);
    }
    C3D_DrawElements(GPU_TRIANGLES, (int)body->indices, C3D_UNSIGNED_SHORT, body->index);
    if (cloud) C3D_CullFace(GPU_CULL_NONE);
    if (vertices) draw_overlay(overlay, vertices, sector_vertices);
#ifdef M3DS_CAMPAIGN_PLANET_MARKERS
    C3D_Mtx marker_projection = screen_projection(marker_matrix);
    int32_t submitted = m3ds_planet_draw_markers(&marker_projection, ids, planes, markers);
    if (submitted > 0 && !body->reported_markers) {
        body->reported_markers = true;
        char record[96];
        int size = snprintf(record, sizeof(record), "UPSTREAM_CAMPAIGN_MARKERS_DRAW planet=%d markers=%ld",
            (int)planet, (long)submitted);
        if (size > 0 && size < (int)sizeof(record)) svcOutputDebugString(record, size);
    }
#else
    (void)ids;
    (void)planes;
    (void)marker_matrix;
    (void)markers;
#endif
    return 1;
}
