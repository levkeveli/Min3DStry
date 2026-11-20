#include "render/factory_demo.h"

#include <string.h>

enum { DEMO_DRILL_X = 4, DEMO_OFFSET_X = 6, DEMO_OFFSET_Y = 6 };
enum { DEMO_TURRET_X = 9, DEMO_TURRET_Y = 6 };

void m3ds_factory_demo_init(M3dsBoundedScene *scene, M3dsParityState *factory) {
    m3ds_scene_init(scene, M3DS_TOP_SCREEN_WIDTH, M3DS_TOP_SCREEN_HEIGHT);
    memset(scene->tiles, M3DS_TILE_STONE, sizeof(scene->tiles));
    scene->show_sim_items = true;

    m3ds_parity_init(factory);
    factory->core_copper = 0u;
    uint8_t y = factory->core_y;
    M3dsParityCell *row = factory->grid[y];
    row[DEMO_DRILL_X].ore = M3DS_PORE_COPPER;
    row[DEMO_DRILL_X].block = M3DS_PBLOCK_DRILL;
    scene->tiles[y + DEMO_OFFSET_Y][DEMO_DRILL_X + DEMO_OFFSET_X] = M3DS_TILE_COPPER_ORE;
    for (uint8_t x = DEMO_DRILL_X + 1; x < factory->core_x; ++x) {
        row[x].block = M3DS_PBLOCK_CONVEYOR;
        scene->tiles[y + DEMO_OFFSET_Y][x + DEMO_OFFSET_X] = M3DS_TILE_CONVEYOR_RIGHT;
    }
    scene->tiles[y + DEMO_OFFSET_Y][factory->core_x + DEMO_OFFSET_X] = M3DS_TILE_CORE;
}

void m3ds_factory_demo_add_turret(
    M3dsBoundedScene *scene,
    M3dsParityState *factory) {
    M3dsParityCell *turret = &factory->grid[DEMO_TURRET_Y][DEMO_TURRET_X];
    turret->block = M3DS_PBLOCK_TURRET;
    turret->ammo = M3DS_PARITY_TURRET_MAX_AMMO;
    scene->tiles[DEMO_TURRET_Y + DEMO_OFFSET_Y][DEMO_TURRET_X + DEMO_OFFSET_X] =
        M3DS_TILE_TURRET;
}

void m3ds_factory_demo_step(M3dsBoundedScene *scene, M3dsParityState *factory) {
    if (scene->paused) {
        return;
    }
    m3ds_parity_step(factory, NULL, 0u);
    scene->tick = factory->tick;
    uint8_t y = factory->core_y;
    for (uint8_t x = DEMO_DRILL_X + 1; x < factory->core_x; ++x) {
        const M3dsParityCell *belt = &factory->grid[y][x];
        scene->belt_items[y + DEMO_OFFSET_Y][x + DEMO_OFFSET_X] =
            belt->item ? (uint8_t)(1u + belt->progress * 254u / M3DS_PARITY_BELT_MAX) : 0u;
    }
}
