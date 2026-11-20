#include "sim/parity_scenario.h"

static const int32_t PARITY_DIR_X[4] = {1, 0, -1, 0};
static const int32_t PARITY_DIR_Y[4] = {0, 1, 0, -1};

static void parity_zero_bytes(void *ptr, size_t len) {
    uint8_t *p = (uint8_t *)ptr;
    for (size_t i = 0; i < len; i++) {
        p[i] = 0u;
    }
}

static void parity_copy_str(char *dst, size_t dst_size, const char *src) {
    if (!dst || dst_size == 0u) {
        return;
    }
    size_t i = 0u;
    while (src && src[i] != '\0' && i + 1u < dst_size) {
        dst[i] = src[i];
        i++;
    }
    dst[i] = '\0';
}

static uint32_t fnv1a_u8(uint32_t h, uint8_t v) {
    return (h ^ (uint32_t)v) * 16777619u;
}

static uint32_t fnv1a_u32(uint32_t h, uint32_t v) {
    h = fnv1a_u8(h, (uint8_t)(v & 0xFFu));
    h = fnv1a_u8(h, (uint8_t)((v >> 8) & 0xFFu));
    h = fnv1a_u8(h, (uint8_t)((v >> 16) & 0xFFu));
    h = fnv1a_u8(h, (uint8_t)((v >> 24) & 0xFFu));
    return h;
}

static uint32_t block_copper_cost(uint8_t block) {
    switch (block) {
        case M3DS_PBLOCK_CONVEYOR:
            return 1u;
        case M3DS_PBLOCK_WALL:
            return 2u;
        case M3DS_PBLOCK_DRILL:
            return 4u;
        case M3DS_PBLOCK_TURRET:
            return 6u;
        default:
            return 0u;
    }
}

static bool in_bounds(int32_t x, int32_t y) {
    return x >= 0 && y >= 0 && x < (int32_t)M3DS_PARITY_GRID_W && y < (int32_t)M3DS_PARITY_GRID_H;
}

static uint32_t count_active_enemies(const M3dsParityState *state) {
    uint32_t count = 0u;
    for (uint32_t i = 0u; i < M3DS_PARITY_MAX_ENEMIES; i++) {
        if (state->enemies[i].alive) {
            count++;
        }
    }
    return count;
}

void m3ds_parity_init(M3dsParityState *state) {
    if (!state) {
        return;
    }
    parity_zero_bytes(state, sizeof(*state));
    state->core_x = 12u;
    state->core_y = 5u;
    state->core_copper = 40u;
    state->core_hp = 200u;

    state->grid[5][2].ore = M3DS_PORE_COPPER;
    state->grid[6][2].ore = M3DS_PORE_COPPER;
    state->grid[state->core_y][state->core_x].block = M3DS_PBLOCK_CORE;
}

bool m3ds_parity_apply_input(M3dsParityState *state, const M3dsParityInput *input) {
    if (!state || !input) {
        return false;
    }

    switch (input->kind) {
        case M3DS_PINPUT_PLACE_BLOCK: {
            if (!in_bounds((int32_t)input->x, (int32_t)input->y)) {
                state->inputs_rejected++;
                return false;
            }
            if (input->block == M3DS_PBLOCK_EMPTY || input->block == M3DS_PBLOCK_CORE ||
                input->block > M3DS_PBLOCK_WALL) {
                state->inputs_rejected++;
                return false;
            }
            M3dsParityCell *cell = &state->grid[input->y][input->x];
            if (cell->block != M3DS_PBLOCK_EMPTY) {
                state->inputs_rejected++;
                return false;
            }
            if (input->block == M3DS_PBLOCK_DRILL && cell->ore == M3DS_PORE_NONE) {
                state->inputs_rejected++;
                return false;
            }
            uint32_t cost = block_copper_cost(input->block);
            if (state->core_copper < cost) {
                state->inputs_rejected++;
                return false;
            }
            state->core_copper -= cost;
            cell->block = input->block;
            cell->rot = (uint8_t)(input->rot & 3u);
            cell->item = 0u;
            cell->progress = 0u;
            cell->ammo = 0u;
            cell->timer = 0u;
            state->blocks_placed++;
            state->inputs_applied++;
            return true;
        }
        case M3DS_PINPUT_REMOVE_BLOCK: {
            if (!in_bounds((int32_t)input->x, (int32_t)input->y)) {
                state->inputs_rejected++;
                return false;
            }
            M3dsParityCell *cell = &state->grid[input->y][input->x];
            if (cell->block == M3DS_PBLOCK_EMPTY || cell->block == M3DS_PBLOCK_CORE) {
                state->inputs_rejected++;
                return false;
            }
            uint32_t refund = block_copper_cost(cell->block) / 2u;
            state->core_copper += refund;
            cell->block = M3DS_PBLOCK_EMPTY;
            cell->rot = 0u;
            cell->item = 0u;
            cell->progress = 0u;
            cell->ammo = 0u;
            cell->timer = 0u;
            if (state->blocks_placed > 0u) {
                state->blocks_placed--;
            }
            state->inputs_applied++;
            return true;
        }
        case M3DS_PINPUT_SPAWN_WAVE: {
            if (!in_bounds((int32_t)input->x, (int32_t)input->y)) {
                state->inputs_rejected++;
                return false;
            }
            uint32_t spawned_here = 0u;
            uint32_t target = (input->block > 0u) ? (uint32_t)input->block : 2u;
            for (uint32_t slot = 0u; slot < M3DS_PARITY_MAX_ENEMIES && spawned_here < target; slot++) {
                if (state->enemies[slot].alive) {
                    continue;
                }
                state->enemies[slot].x =
                    (int32_t)input->x * M3DS_PARITY_SUBPIXELS_PER_TILE + 8 + (int32_t)(spawned_here * 8u);
                state->enemies[slot].y =
                    (int32_t)input->y * M3DS_PARITY_SUBPIXELS_PER_TILE + 8;
                state->enemies[slot].hp = M3DS_PARITY_ENEMY_HP;
                state->enemies[slot].alive = 1u;
                state->enemies_spawned++;
                spawned_here++;
            }
            if (spawned_here == 0u) {
                state->inputs_rejected++;
                return false;
            }
            state->inputs_applied++;
            return true;
        }
        case M3DS_PINPUT_SET_PAUSED: {
            state->paused = (input->rot != 0u) ? 1u : 0u;
            state->inputs_applied++;
            return true;
        }
        default:
            state->inputs_rejected++;
            return false;
    }
}

static void step_drills(M3dsParityState *state) {
    for (uint32_t y = 0u; y < M3DS_PARITY_GRID_H; y++) {
        for (uint32_t x = 0u; x < M3DS_PARITY_GRID_W; x++) {
            M3dsParityCell *cell = &state->grid[y][x];
            if (cell->block != M3DS_PBLOCK_DRILL || cell->ore == M3DS_PORE_NONE) {
                continue;
            }
            cell->timer++;
            if (cell->timer < M3DS_PARITY_DRILL_PERIOD) {
                continue;
            }
            cell->timer = 0u;
            int32_t nx = (int32_t)x + PARITY_DIR_X[cell->rot & 3u];
            int32_t ny = (int32_t)y + PARITY_DIR_Y[cell->rot & 3u];
            if (!in_bounds(nx, ny)) {
                continue;
            }
            M3dsParityCell *next = &state->grid[ny][nx];
            if (next->block == M3DS_PBLOCK_CONVEYOR && next->item == 0u) {
                next->item = 1u;
                next->progress = 0u;
                state->items_mined++;
            } else if (next->block == M3DS_PBLOCK_CORE) {
                state->core_copper++;
                state->items_mined++;
                state->items_delivered++;
            }
        }
    }
}

static void step_conveyors(M3dsParityState *state) {
    for (int32_t y = (int32_t)M3DS_PARITY_GRID_H - 1; y >= 0; y--) {
        for (int32_t x = (int32_t)M3DS_PARITY_GRID_W - 1; x >= 0; x--) {
            M3dsParityCell *cell = &state->grid[y][x];
            if (cell->block != M3DS_PBLOCK_CONVEYOR || cell->item == 0u) {
                continue;
            }
            uint32_t next_prog = (uint32_t)cell->progress + M3DS_PARITY_BELT_STEP;
            if (next_prog < M3DS_PARITY_BELT_MAX) {
                cell->progress = (uint8_t)next_prog;
                continue;
            }
            int32_t nx = x + PARITY_DIR_X[cell->rot & 3u];
            int32_t ny = y + PARITY_DIR_Y[cell->rot & 3u];
            if (!in_bounds(nx, ny)) {
                cell->progress = (uint8_t)(M3DS_PARITY_BELT_MAX - 1u);
                continue;
            }
            M3dsParityCell *next = &state->grid[ny][nx];
            if (next->block == M3DS_PBLOCK_CONVEYOR && next->item == 0u) {
                next->item = cell->item;
                next->progress = (uint8_t)(next_prog - M3DS_PARITY_BELT_MAX);
                cell->item = 0u;
                cell->progress = 0u;
            } else if (next->block == M3DS_PBLOCK_CORE) {
                cell->item = 0u;
                cell->progress = 0u;
                state->core_copper++;
                state->items_delivered++;
            } else if (next->block == M3DS_PBLOCK_TURRET &&
                       (uint32_t)next->ammo + M3DS_PARITY_AMMO_PER_COPPER <= M3DS_PARITY_TURRET_MAX_AMMO) {
                cell->item = 0u;
                cell->progress = 0u;
                next->ammo = (uint8_t)(next->ammo + M3DS_PARITY_AMMO_PER_COPPER);
            } else {
                cell->progress = (uint8_t)(M3DS_PARITY_BELT_MAX - 1u);
            }
        }
    }
}

static void step_turrets(M3dsParityState *state) {
    for (uint32_t y = 0u; y < M3DS_PARITY_GRID_H; y++) {
        for (uint32_t x = 0u; x < M3DS_PARITY_GRID_W; x++) {
            M3dsParityCell *cell = &state->grid[y][x];
            if (cell->block != M3DS_PBLOCK_TURRET) {
                continue;
            }
            if (cell->timer > 0u) {
                cell->timer--;
                continue;
            }
            if (cell->ammo == 0u) {
                continue;
            }
            int32_t tx = (int32_t)x * M3DS_PARITY_SUBPIXELS_PER_TILE + 8;
            int32_t ty = (int32_t)y * M3DS_PARITY_SUBPIXELS_PER_TILE + 8;
            int32_t best_idx = -1;
            int32_t best_d2 = M3DS_PARITY_TURRET_RANGE_SQ + 1;
            for (uint32_t e = 0u; e < M3DS_PARITY_MAX_ENEMIES; e++) {
                if (!state->enemies[e].alive) {
                    continue;
                }
                int32_t dx = state->enemies[e].x - tx;
                int32_t dy = state->enemies[e].y - ty;
                int32_t d2 = dx * dx + dy * dy;
                if (d2 <= M3DS_PARITY_TURRET_RANGE_SQ && d2 < best_d2) {
                    best_d2 = d2;
                    best_idx = (int32_t)e;
                }
            }
            if (best_idx >= 0) {
                cell->ammo--;
                cell->timer = (uint8_t)M3DS_PARITY_TURRET_COOLDOWN;
                state->shots_fired++;
                state->enemies[best_idx].hp -= M3DS_PARITY_TURRET_DAMAGE;
                if (state->enemies[best_idx].hp <= 0) {
                    state->enemies[best_idx].hp = 0;
                    state->enemies[best_idx].alive = 0u;
                    state->enemies_killed++;
                }
            }
        }
    }
}

static void step_enemies(M3dsParityState *state) {
    int32_t target_x = (int32_t)state->core_x * M3DS_PARITY_SUBPIXELS_PER_TILE + 8;
    int32_t target_y = (int32_t)state->core_y * M3DS_PARITY_SUBPIXELS_PER_TILE + 8;
    for (uint32_t i = 0u; i < M3DS_PARITY_MAX_ENEMIES; i++) {
        M3dsParityEnemy *en = &state->enemies[i];
        if (!en->alive) {
            continue;
        }
        int32_t dx = target_x - en->x;
        int32_t dy = target_y - en->y;
        if (dx > M3DS_PARITY_ENEMY_SPEED) {
            en->x += M3DS_PARITY_ENEMY_SPEED;
        } else if (dx < -M3DS_PARITY_ENEMY_SPEED) {
            en->x -= M3DS_PARITY_ENEMY_SPEED;
        } else {
            en->x = target_x;
        }
        if (dy > M3DS_PARITY_ENEMY_SPEED) {
            en->y += M3DS_PARITY_ENEMY_SPEED;
        } else if (dy < -M3DS_PARITY_ENEMY_SPEED) {
            en->y -= M3DS_PARITY_ENEMY_SPEED;
        } else {
            en->y = target_y;
        }
        if (en->x == target_x && en->y == target_y) {
            en->alive = 0u;
            en->hp = 0;
            state->enemies_leaked++;
            if (state->core_hp > M3DS_PARITY_ENEMY_CORE_DAMAGE) {
                state->core_hp -= M3DS_PARITY_ENEMY_CORE_DAMAGE;
            } else {
                state->core_hp = 0u;
            }
        }
    }
}

void m3ds_parity_step(
    M3dsParityState *state,
    const M3dsParityInput *inputs,
    size_t input_count) {
    if (!state) {
        return;
    }
    if (inputs) {
        for (size_t i = 0u; i < input_count; i++) {
            if (inputs[i].tick == state->tick) {
                (void)m3ds_parity_apply_input(state, &inputs[i]);
            }
        }
    }
    if (!state->paused) {
        step_drills(state);
        step_conveyors(state);
        step_turrets(state);
        step_enemies(state);
    }
    state->tick++;
}

uint32_t m3ds_parity_hash_state(const M3dsParityState *state) {
    uint32_t h = 2166136261u;
    if (!state) {
        return h;
    }
    for (uint32_t y = 0u; y < M3DS_PARITY_GRID_H; y++) {
        for (uint32_t x = 0u; x < M3DS_PARITY_GRID_W; x++) {
            const M3dsParityCell *c = &state->grid[y][x];
            h = fnv1a_u8(h, c->ore);
            h = fnv1a_u8(h, c->block);
            h = fnv1a_u8(h, c->rot);
            h = fnv1a_u8(h, c->item);
            h = fnv1a_u8(h, c->progress);
            h = fnv1a_u8(h, c->ammo);
            h = fnv1a_u8(h, c->timer);
        }
    }
    for (uint32_t i = 0u; i < M3DS_PARITY_MAX_ENEMIES; i++) {
        h = fnv1a_u32(h, (uint32_t)state->enemies[i].x);
        h = fnv1a_u32(h, (uint32_t)state->enemies[i].y);
        h = fnv1a_u32(h, (uint32_t)state->enemies[i].hp);
        h = fnv1a_u8(h, state->enemies[i].alive);
    }
    h = fnv1a_u32(h, state->tick);
    h = fnv1a_u32(h, state->paused);
    h = fnv1a_u32(h, state->core_copper);
    h = fnv1a_u32(h, state->core_hp);
    h = fnv1a_u32(h, state->items_mined);
    h = fnv1a_u32(h, state->items_delivered);
    h = fnv1a_u32(h, state->shots_fired);
    h = fnv1a_u32(h, state->enemies_spawned);
    h = fnv1a_u32(h, state->enemies_killed);
    h = fnv1a_u32(h, state->enemies_leaked);
    h = fnv1a_u32(h, state->blocks_placed);
    h = fnv1a_u32(h, state->inputs_applied);
    h = fnv1a_u32(h, state->inputs_rejected);
    return h;
}

M3dsParitySnapshot m3ds_parity_snapshot(const M3dsParityState *state) {
    M3dsParitySnapshot snap;
    parity_zero_bytes(&snap, sizeof(snap));
    if (!state) {
        return snap;
    }
    snap.tick = state->tick;
    snap.core_copper = state->core_copper;
    snap.core_hp = state->core_hp;
    snap.items_mined = state->items_mined;
    snap.items_delivered = state->items_delivered;
    snap.shots_fired = state->shots_fired;
    snap.enemies_spawned = state->enemies_spawned;
    snap.enemies_killed = state->enemies_killed;
    snap.enemies_leaked = state->enemies_leaked;
    snap.active_enemies = count_active_enemies(state);
    snap.blocks_placed = state->blocks_placed;
    snap.inputs_applied = state->inputs_applied;
    snap.inputs_rejected = state->inputs_rejected;
    snap.state_hash = m3ds_parity_hash_state(state);
    return snap;
}

void m3ds_parity_run_canonical(M3dsParityRunResult *out) {
    static const M3dsParityInput canonical_inputs[] = {
        {0u, M3DS_PINPUT_PLACE_BLOCK, 2u, 5u, M3DS_PBLOCK_DRILL, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 3u, 5u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 4u, 5u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 5u, 5u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 6u, 5u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 7u, 5u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 8u, 5u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 9u, 5u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 10u, 5u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 11u, 5u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 2u, 6u, M3DS_PBLOCK_DRILL, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 3u, 6u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 4u, 6u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 5u, 6u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 6u, 6u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 7u, 6u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 8u, 6u, M3DS_PBLOCK_CONVEYOR, 0u},
        {0u, M3DS_PINPUT_PLACE_BLOCK, 9u, 6u, M3DS_PBLOCK_TURRET, 0u},
        {12u, M3DS_PINPUT_PLACE_BLOCK, 10u, 3u, M3DS_PBLOCK_WALL, 0u},
        {15u, M3DS_PINPUT_REMOVE_BLOCK, 10u, 3u, 0u, 0u},
        {80u, M3DS_PINPUT_SPAWN_WAVE, 9u, 1u, 2u, 0u},
        {140u, M3DS_PINPUT_SET_PAUSED, 0u, 0u, 0u, 1u},
        {160u, M3DS_PINPUT_SET_PAUSED, 0u, 0u, 0u, 0u},
        {180u, M3DS_PINPUT_SPAWN_WAVE, 11u, 1u, 2u, 0u},
    };
    static const uint32_t total_ticks = 240u;
    const size_t input_count = sizeof(canonical_inputs) / sizeof(canonical_inputs[0]);

    if (!out) {
        return;
    }
    parity_zero_bytes(out, sizeof(*out));
    parity_copy_str(out->scenario_id, sizeof(out->scenario_id), "starter_copper_defense_v1");
    out->total_ticks = total_ticks;

    M3dsParityState state;
    m3ds_parity_init(&state);
    uint32_t trace = 2166136261u;

    for (uint32_t t = 0u; t < total_ticks; t++) {
        if ((t % 60u) == 0u && out->checkpoint_count < M3DS_PARITY_MAX_CHECKPOINTS) {
            out->checkpoints[out->checkpoint_count++] = m3ds_parity_snapshot(&state);
        }
        m3ds_parity_step(&state, canonical_inputs, input_count);
        trace = fnv1a_u32(trace, m3ds_parity_hash_state(&state));
    }

    if (out->checkpoint_count < M3DS_PARITY_MAX_CHECKPOINTS) {
        out->checkpoints[out->checkpoint_count++] = m3ds_parity_snapshot(&state);
    }
    out->trace_hash = trace;
}
