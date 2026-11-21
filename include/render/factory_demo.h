#ifndef MIN3DSTRY_RENDER_FACTORY_DEMO_H
#define MIN3DSTRY_RENDER_FACTORY_DEMO_H

#include "render/scene_2d.h"
#include "sim/parity_scenario.h"

void m3ds_factory_demo_init(M3dsBoundedScene *scene, M3dsParityState *factory);
void m3ds_factory_demo_add_turret(
    M3dsBoundedScene *scene,
    M3dsParityState *factory);
void m3ds_factory_demo_step(M3dsBoundedScene *scene, M3dsParityState *factory);

#endif
