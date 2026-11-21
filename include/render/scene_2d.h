#ifndef MIN3DSTRY_RENDER_SCENE_2D_H
#define MIN3DSTRY_RENDER_SCENE_2D_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_TOP_SCREEN_WIDTH  400.0f
#define M3DS_TOP_SCREEN_HEIGHT 240.0f
#define M3DS_TILE_SIZE_PX      16.0f

#define M3DS_SCENE_GRID_W      36
#define M3DS_SCENE_GRID_H      24
#define M3DS_SCENE_MAX_PRIMITIVES 1024u
#define M3DS_SCENE_MAX_EFFECTS 64u
#define M3DS_SCENE_MIN_ZOOM    0.5f
#define M3DS_SCENE_MAX_ZOOM    2.0f

typedef struct M3dsColor {
    uint8_t r;
    uint8_t g;
    uint8_t b;
    uint8_t a;
} M3dsColor;

static inline M3dsColor m3ds_rgba(uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    M3dsColor c = {r, g, b, a};
    return c;
}

typedef struct M3dsViewport {
    float width;
    float height;
} M3dsViewport;

typedef enum M3dsTileKind {
    M3DS_TILE_STONE = 0,
    M3DS_TILE_COPPER_ORE = 1,
    M3DS_TILE_LEAD_ORE = 2,
    M3DS_TILE_CONVEYOR_RIGHT = 3,
    M3DS_TILE_CONVEYOR_DOWN = 4,
    M3DS_TILE_WALL = 5,
    M3DS_TILE_CORE = 6,
    M3DS_TILE_TURRET = 7
} M3dsTileKind;

typedef struct M3dsDrawStats {
    uint32_t drawn_rects;
    uint32_t drawn_lines;
    uint32_t drawn_triangles;
    uint32_t drawn_circles;
    uint32_t culled_primitives;
    uint32_t state_switches;
    uint32_t budget_dropped;
    uint32_t effects_dropped;
    uint32_t light_circles;
    bool circle_mode_active;
} M3dsDrawStats;

typedef enum M3dsSceneSprite {
    M3DS_SPRITE_FLOOR,
    M3DS_SPRITE_OVERLAY,
    M3DS_SPRITE_STRIP_0,
    M3DS_SPRITE_STRIP_1,
    M3DS_SPRITE_ITEM,
} M3dsSceneSprite;

typedef struct M3dsRenderBackend {
    void *user_data;
    bool (*clear)(void *user_data, M3dsColor color);
    bool (*draw_rect)(
        void *user_data, float x, float y, float z, float w, float h, M3dsColor color);
    bool (*draw_line)(
        void *user_data, float x0, float y0, float x1, float y1,
        float thickness, float z, M3dsColor color);
    bool (*draw_triangle)(
        void *user_data, float x0, float y0, float x1, float y1,
        float x2, float y2, float z, M3dsColor color);
    bool (*draw_circle)(
        void *user_data, float cx, float cy, float z, float radius, M3dsColor color);
    bool (*draw_sprite)(void *user_data, M3dsSceneSprite sprite, float x, float y, float z,
                        float width, float height);
} M3dsRenderBackend;

typedef struct M3dsBoundedScene {
    M3dsViewport viewport;
    float camera_x;
    float camera_y;
    float zoom;
    uint32_t tick;
    bool paused;
    bool show_sim_items;
    bool lighting;
    uint8_t tiles[M3DS_SCENE_GRID_H][M3DS_SCENE_GRID_W];
    /* Zero is empty; 1 through 255 encode an item's position along the belt. */
    uint8_t belt_items[M3DS_SCENE_GRID_H][M3DS_SCENE_GRID_W];
} M3dsBoundedScene;

void m3ds_scene_init(M3dsBoundedScene *scene, float viewport_w, float viewport_h);
void m3ds_scene_reset_camera(M3dsBoundedScene *scene);
void m3ds_scene_pan(M3dsBoundedScene *scene, float dx, float dy);
void m3ds_scene_set_zoom(M3dsBoundedScene *scene, float zoom);
void m3ds_scene_zoom_by(M3dsBoundedScene *scene, float delta);
static inline float m3ds_scene_zoom(const M3dsBoundedScene *scene) {
    return (scene && scene->zoom > 0.0f) ? scene->zoom : 1.0f;
}
void m3ds_scene_set_lighting(M3dsBoundedScene *scene, bool enabled);
bool m3ds_scene_lighting(const M3dsBoundedScene *scene);
void m3ds_scene_step(M3dsBoundedScene *scene, uint32_t ticks);
bool m3ds_scene_render(
    const M3dsBoundedScene *scene,
    const M3dsRenderBackend *backend,
    M3dsDrawStats *out_stats);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_RENDER_SCENE_2D_H */
