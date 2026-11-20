#ifndef MIN3DSTRY_SIM_PARITY_SCENARIO_H
#define MIN3DSTRY_SIM_PARITY_SCENARIO_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define M3DS_PARITY_GRID_W 16u
#define M3DS_PARITY_GRID_H 12u
#define M3DS_PARITY_MAX_ENEMIES 16u
#define M3DS_PARITY_MAX_CHECKPOINTS 8u
#define M3DS_PARITY_TICKS_PER_SEC 60u

#define M3DS_PARITY_DRILL_PERIOD 15u
#define M3DS_PARITY_BELT_MAX 60u
#define M3DS_PARITY_BELT_STEP 15u
#define M3DS_PARITY_TURRET_COOLDOWN 10u
#define M3DS_PARITY_TURRET_DAMAGE 25
#define M3DS_PARITY_SUBPIXELS_PER_TILE 16
#define M3DS_PARITY_TURRET_RANGE_SUBPX (7 * M3DS_PARITY_SUBPIXELS_PER_TILE)
#define M3DS_PARITY_TURRET_RANGE_SQ (M3DS_PARITY_TURRET_RANGE_SUBPX * M3DS_PARITY_TURRET_RANGE_SUBPX)
#define M3DS_PARITY_TURRET_MAX_AMMO 8u
#define M3DS_PARITY_AMMO_PER_COPPER 4u

#define M3DS_PARITY_ENEMY_HP 75
#define M3DS_PARITY_ENEMY_SPEED 2
#define M3DS_PARITY_ENEMY_CORE_DAMAGE 20u

typedef enum M3dsParityBlock {
    M3DS_PBLOCK_EMPTY = 0,
    M3DS_PBLOCK_DRILL = 1,
    M3DS_PBLOCK_CONVEYOR = 2,
    M3DS_PBLOCK_TURRET = 3,
    M3DS_PBLOCK_CORE = 4,
    M3DS_PBLOCK_WALL = 5
} M3dsParityBlock;

typedef enum M3dsParityOre {
    M3DS_PORE_NONE = 0,
    M3DS_PORE_COPPER = 1
} M3dsParityOre;

typedef enum M3dsParityInputKind {
    M3DS_PINPUT_PLACE_BLOCK = 1,
    M3DS_PINPUT_REMOVE_BLOCK = 2,
    M3DS_PINPUT_SPAWN_WAVE = 3,
    M3DS_PINPUT_SET_PAUSED = 4
} M3dsParityInputKind;

typedef struct M3dsParityInput {
    uint32_t tick;
    uint8_t kind;
    uint8_t x;
    uint8_t y;
    uint8_t block;
    uint8_t rot;
} M3dsParityInput;

typedef struct M3dsParityCell {
    uint8_t ore;
    uint8_t block;
    uint8_t rot;
    uint8_t item;
    uint8_t progress;
    uint8_t ammo;
    uint8_t timer;
    uint8_t reserved;
} M3dsParityCell;

typedef struct M3dsParityEnemy {
    int32_t x;
    int32_t y;
    int32_t hp;
    uint8_t alive;
} M3dsParityEnemy;

typedef struct M3dsParityState {
    M3dsParityCell grid[M3DS_PARITY_GRID_H][M3DS_PARITY_GRID_W];
    M3dsParityEnemy enemies[M3DS_PARITY_MAX_ENEMIES];
    uint32_t tick;
    uint32_t paused;
    uint8_t core_x;
    uint8_t core_y;
    uint32_t core_copper;
    uint32_t core_hp;
    uint32_t items_mined;
    uint32_t items_delivered;
    uint32_t shots_fired;
    uint32_t enemies_spawned;
    uint32_t enemies_killed;
    uint32_t enemies_leaked;
    uint32_t blocks_placed;
    uint32_t inputs_applied;
    uint32_t inputs_rejected;
} M3dsParityState;

typedef struct M3dsParitySnapshot {
    uint32_t tick;
    uint32_t core_copper;
    uint32_t core_hp;
    uint32_t items_mined;
    uint32_t items_delivered;
    uint32_t shots_fired;
    uint32_t enemies_spawned;
    uint32_t enemies_killed;
    uint32_t enemies_leaked;
    uint32_t active_enemies;
    uint32_t blocks_placed;
    uint32_t inputs_applied;
    uint32_t inputs_rejected;
    uint32_t state_hash;
} M3dsParitySnapshot;

typedef struct M3dsParityRunResult {
    char scenario_id[32];
    uint32_t total_ticks;
    uint32_t checkpoint_count;
    M3dsParitySnapshot checkpoints[M3DS_PARITY_MAX_CHECKPOINTS];
    uint32_t trace_hash;
} M3dsParityRunResult;

void m3ds_parity_init(M3dsParityState *state);
bool m3ds_parity_apply_input(M3dsParityState *state, const M3dsParityInput *input);
void m3ds_parity_step(
    M3dsParityState *state,
    const M3dsParityInput *inputs,
    size_t input_count);
uint32_t m3ds_parity_hash_state(const M3dsParityState *state);
M3dsParitySnapshot m3ds_parity_snapshot(const M3dsParityState *state);
void m3ds_parity_run_canonical(M3dsParityRunResult *out);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_SIM_PARITY_SCENARIO_H */
