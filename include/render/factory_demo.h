#ifndef MIN3DSTRY_RENDER_FACTORY_DEMO_H
#define MIN3DSTRY_RENDER_FACTORY_DEMO_H

#include "render/scene_2d.h"
#include "sim/parity_scenario.h"

#define M3DS_FACTORY_DEMO_DRILL_X  4u
#define M3DS_FACTORY_DEMO_OFFSET_X 6u
#define M3DS_FACTORY_DEMO_OFFSET_Y 6u

void m3ds_factory_demo_init(M3dsBoundedScene *scene, M3dsParityState *factory);
void m3ds_factory_demo_step(M3dsBoundedScene *scene, M3dsParityState *factory);
void m3ds_factory_demo_sync_tile(
    M3dsBoundedScene *scene,
    const M3dsParityState *factory,
    uint8_t sim_x,
    uint8_t sim_y);

#endif
