#include "render/factory_demo.h"

#include <string.h>

void m3ds_factory_demo_init(M3dsBoundedScene *scene, M3dsParityState *factory) {
    m3ds_scene_init(scene, M3DS_TOP_SCREEN_WIDTH, M3DS_TOP_SCREEN_HEIGHT);
    memset(scene->tiles, M3DS_TILE_STONE, sizeof(scene->tiles));
    scene->show_sim_items = true;

    m3ds_parity_init(factory);
    factory->core_copper = 0u;
    uint8_t y = factory->core_y;
    M3dsParityCell *row = factory->grid[y];
    row[M3DS_FACTORY_DEMO_DRILL_X].ore = M3DS_PORE_COPPER;
    row[M3DS_FACTORY_DEMO_DRILL_X].block = M3DS_PBLOCK_DRILL;
    scene->tiles[y + M3DS_FACTORY_DEMO_OFFSET_Y][M3DS_FACTORY_DEMO_DRILL_X + M3DS_FACTORY_DEMO_OFFSET_X] =
        M3DS_TILE_COPPER_ORE;
    for (uint8_t x = M3DS_FACTORY_DEMO_DRILL_X + 1u; x < factory->core_x; ++x) {
        row[x].block = M3DS_PBLOCK_CONVEYOR;
        scene->tiles[y + M3DS_FACTORY_DEMO_OFFSET_Y][x + M3DS_FACTORY_DEMO_OFFSET_X] =
            M3DS_TILE_CONVEYOR_RIGHT;
    }
    scene->tiles[y + M3DS_FACTORY_DEMO_OFFSET_Y][factory->core_x + M3DS_FACTORY_DEMO_OFFSET_X] =
        M3DS_TILE_CORE;
}

void m3ds_factory_demo_step(M3dsBoundedScene *scene, M3dsParityState *factory) {
    if (scene->paused) {
        return;
    }
    m3ds_parity_step(factory, NULL, 0u);
    scene->tick = factory->tick;
    uint8_t y = factory->core_y;
    for (uint8_t x = M3DS_FACTORY_DEMO_DRILL_X + 1u; x < factory->core_x; ++x) {
        const M3dsParityCell *belt = &factory->grid[y][x];
        scene->belt_items[y + M3DS_FACTORY_DEMO_OFFSET_Y][x + M3DS_FACTORY_DEMO_OFFSET_X] =
            belt->item ? (uint8_t)(1u + belt->progress * 254u / M3DS_PARITY_BELT_MAX) : 0u;
    }
}

void m3ds_factory_demo_sync_tile(
    M3dsBoundedScene *scene,
    const M3dsParityState *factory,
    uint8_t sim_x,
    uint8_t sim_y) {
    if (!scene || !factory || sim_x >= M3DS_PARITY_GRID_W || sim_y >= M3DS_PARITY_GRID_H) {
        return;
    }
    uint32_t gx = (uint32_t)sim_x + M3DS_FACTORY_DEMO_OFFSET_X;
    uint32_t gy = (uint32_t)sim_y + M3DS_FACTORY_DEMO_OFFSET_Y;
    if (gx >= M3DS_SCENE_GRID_W || gy >= M3DS_SCENE_GRID_H) {
        return;
    }

    const M3dsParityCell *cell = &factory->grid[sim_y][sim_x];
    uint8_t tile = M3DS_TILE_STONE;
    switch (cell->block) {
        case M3DS_PBLOCK_CONVEYOR:
            switch (cell->rot & 3u) {
                case 0u:
                    tile = M3DS_TILE_CONVEYOR_RIGHT;
                    break;
                case 1u:
                    tile = M3DS_TILE_CONVEYOR_DOWN;
                    break;
                case 2u:
                    tile = M3DS_TILE_CONVEYOR_LEFT;
                    break;
                default:
                    tile = M3DS_TILE_CONVEYOR_UP;
                    break;
            }
            break;
        case M3DS_PBLOCK_DRILL:
            tile = M3DS_TILE_COPPER_ORE;
            break;
        case M3DS_PBLOCK_WALL:
            tile = M3DS_TILE_WALL;
            break;
        case M3DS_PBLOCK_TURRET:
            tile = M3DS_TILE_TURRET;
            break;
        case M3DS_PBLOCK_CORE:
            tile = M3DS_TILE_CORE;
            break;
        case M3DS_PBLOCK_EMPTY:
        default:
            tile = cell->ore ? M3DS_TILE_COPPER_ORE : M3DS_TILE_STONE;
            break;
    }

    scene->tiles[gy][gx] = tile;
    if (cell->block != M3DS_PBLOCK_CONVEYOR) {
        scene->belt_items[gy][gx] = 0u;
    }
}
