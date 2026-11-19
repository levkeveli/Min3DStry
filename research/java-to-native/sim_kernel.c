/*
 * Min3DStry research: deterministic fixed-point simulation kernel.
 *
 * This is a feasibility proof of concept for issue #1, not game code. It models
 * the kind of hot loop a native Mindustry port would run on the 3DS ARM11:
 * conveyor transport, router distribution, turret targeting and enemy movement.
 *
 * Design constraints that matter for the port:
 *   - integer-only state and math, so results are identical on x86_64, ARM11
 *     and under different optimisation levels;
 *   - flat structure-of-arrays-friendly records, no pointers inside the state,
 *     so a snapshot can be hashed or serialised deterministically;
 *   - no libc in the simulation core (freestanding ARM builds include only the
 *     core; they are compiled but not executed as part of this POC).
 *
 * Evidence level: host-tested. Nothing here is 3DS emulator or hardware tested.
 *
 * Build:
 *   cc -std=c99 -Wall -Wextra -Werror -O2 sim_kernel.c -o sim_kernel
 *   ./sim_kernel --map 96x96 --ticks 2000 --seed 7
 */

#include <stdint.h>

#define MAP_MAX 512
#define MAX_ENEMIES 48
#define TICKS_PER_ITEM_STEP 4
#define BELT_PROGRESS_MAX 1024
#define BELT_SPEED (BELT_PROGRESS_MAX / TICKS_PER_ITEM_STEP)
#define SOURCE_PERIOD 24
#define TURRET_COOLDOWN 8
#define TURRET_DAMAGE 25
#define TURRET_RANGE_TILES 48
#define ENEMY_SPEED 2
#define ENEMY_HP 100
#define CORE_RADIUS 24

enum {
    CELL_NONE = 0,
    CELL_BELT = 1,
    CELL_ROUTER = 2,
    CELL_TURRET = 3,
    CELL_SOURCE = 4,
    CELL_CORE = 5
};

/* 12 bytes on every target: the memory model in budget_model.py uses this. */
typedef struct {
    uint16_t progress;
    uint16_t cooldown;
    uint32_t state;
    uint8_t type;
    uint8_t rot;
    uint8_t item;
    uint8_t count;
} Cell;

typedef struct {
    int32_t x;
    int32_t y;
    int32_t hp;
    uint32_t rng;
    uint8_t alive;
} Enemy;

typedef struct {
    uint32_t hash;
    uint32_t map_w;
    uint32_t map_h;
    uint32_t ticks;
    uint32_t cell_bytes;
    uint32_t delivered;
    uint32_t spawned;
    uint32_t killed;
    uint32_t leaked;
} SimResult;

static Cell g_cells[MAP_MAX * MAP_MAX];
static Enemy g_enemies[MAX_ENEMIES];

static const int32_t DIR_X[4] = {1, 0, -1, 0};
static const int32_t DIR_Y[4] = {0, 1, 0, -1};

static uint32_t rng_next(uint32_t *state) {
    uint32_t x = *state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *state = x;
    return x;
}

static void mix(uint32_t *hash, uint32_t value) {
    *hash = (*hash ^ value) * 16777619u;
}

static int32_t clamp_i32(int32_t value, int32_t lo, int32_t hi) {
    if (value < lo) return lo;
    if (value > hi) return hi;
    return value;
}

static void clear_state(uint32_t cells, uint32_t enemies) {
    uint32_t i;
    for (i = 0; i < cells; i++) {
        g_cells[i].progress = 0;
        g_cells[i].cooldown = 0;
        g_cells[i].state = 0;
        g_cells[i].type = CELL_NONE;
        g_cells[i].rot = 0;
        g_cells[i].item = 0;
        g_cells[i].count = 0;
    }
    for (i = 0; i < enemies; i++) {
        g_enemies[i].x = 0;
        g_enemies[i].y = 0;
        g_enemies[i].hp = 0;
        g_enemies[i].rng = 0;
        g_enemies[i].alive = 0;
    }
}

static Cell *cell_at(uint32_t w, uint32_t h, int32_t x, int32_t y) {
    if (x < 0 || y < 0 || x >= (int32_t)w || y >= (int32_t)h) return 0;
    return &g_cells[(uint32_t)y * w + (uint32_t)x];
}

static void build_map(uint32_t w, uint32_t h) {
    uint32_t x;
    uint32_t y;
    uint32_t rng = 0x9e3779b9u;

    /* Sources feed eastbound belt rows; every row ends in the core. */
    for (y = 0; y < h; y++) {
        if ((y % 8u) != 2u) continue;
        for (x = 0; x < w; x++) {
            Cell *c = cell_at(w, h, (int32_t)x, (int32_t)y);
            if (x == 0) {
                c->type = CELL_SOURCE;
                c->rot = 0;
            } else if (x + 1 < w) {
                c->type = ((x % 7u) == 0u) ? CELL_ROUTER : CELL_BELT;
                c->rot = 0;
            }
        }
        cell_at(w, h, (int32_t)(w - 1), (int32_t)y)->type = CELL_CORE;
    }

    /* Two turret bands the enemy stream has to cross. */
    for (y = 0; y < h; y++) {
        if (y != 5u && y + 6u != h) continue;
        for (x = 2; x + 2 < w; x += 5u) {
            Cell *c = cell_at(w, h, (int32_t)x, (int32_t)y);
            if (c->type == CELL_NONE) {
                c->type = CELL_TURRET;
                c->rot = (uint8_t)((x / 5u) & 3u);
                c->state = rng_next(&rng);
            }
        }
    }
}

static uint32_t hash_state(uint32_t w, uint32_t h, uint32_t delivered,
                           uint32_t spawned, uint32_t killed, uint32_t leaked) {
    uint32_t hash = 2166136261u;
    uint32_t i;
    uint32_t total = w * h;

    for (i = 0; i < total; i++) {
        mix(&hash, g_cells[i].progress);
        mix(&hash, g_cells[i].cooldown);
        mix(&hash, g_cells[i].state);
        mix(&hash, (uint32_t)g_cells[i].type | ((uint32_t)g_cells[i].rot << 8) |
                   ((uint32_t)g_cells[i].item << 16) | ((uint32_t)g_cells[i].count << 24));
    }
    for (i = 0; i < MAX_ENEMIES; i++) {
        mix(&hash, (uint32_t)g_enemies[i].x);
        mix(&hash, (uint32_t)g_enemies[i].y);
        mix(&hash, (uint32_t)g_enemies[i].hp);
        mix(&hash, (uint32_t)g_enemies[i].alive);
        mix(&hash, g_enemies[i].rng);
    }
    mix(&hash, delivered);
    mix(&hash, spawned);
    mix(&hash, killed);
    mix(&hash, leaked);
    return hash;
}

/*
 * Runs the kernel for `ticks` fixed steps. With hash_each_tick set, every tick
 * is folded into the trace hash, which is what cross-build comparisons use.
 * The simulation is integer-only, so this must be bit-identical everywhere.
 */
SimResult sim_run(uint32_t w, uint32_t h, uint32_t ticks, uint32_t seed,
                  int hash_each_tick) {
    SimResult result;
    uint32_t rng = seed ? seed : 1u;
    uint32_t total = w * h;
    uint32_t tick;
    uint32_t core_x = (w - 2u) * 16u;
    uint32_t core_y = (h / 2u) * 16u;
    uint32_t trace = 2166136261u;

    if (w < 16u) w = 16u;
    if (h < 16u) h = 16u;
    if (w > MAP_MAX) w = MAP_MAX;
    if (h > MAP_MAX) h = MAP_MAX;
    total = w * h;
    core_x = (w - 2u) * 16u;
    core_y = (h / 2u) * 16u;

    clear_state(total, MAX_ENEMIES);
    build_map(w, h);

    result.map_w = w;
    result.map_h = h;
    result.ticks = ticks;
    result.cell_bytes = (uint32_t)sizeof(Cell);
    result.delivered = 0;
    result.spawned = 0;
    result.killed = 0;
    result.leaked = 0;

    for (tick = 0; tick < ticks; tick++) {
        uint32_t i;
        int32_t x;
        int32_t y;

        /* Sources emit one item into a free downstream belt. */
        if ((tick % SOURCE_PERIOD) == 0u) {
            for (y = 0; y < (int32_t)h; y++) {
                for (x = 0; x < (int32_t)w; x++) {
                    Cell *c = cell_at(w, h, x, y);
                    if (c->type != CELL_SOURCE || c->item != 0) continue;
                    {
                        Cell *next = cell_at(w, h, x + 1, y);
                        if (next && (next->type == CELL_BELT || next->type == CELL_ROUTER) &&
                            next->item == 0) {
                            next->item = (uint8_t)(1u + (rng_next(&rng) % 3u));
                            next->progress = 0;
                            next->count = 1;
                        }
                    }
                }
            }
        }

        /*
         * Transport runs east-to-west so that downstream cells move first. The
         * iteration order is fixed and part of the observable trace.
         */
        for (y = (int32_t)h - 1; y >= 0; y--) {
            for (x = (int32_t)w - 1; x >= 0; x--) {
                Cell *c = cell_at(w, h, x, y);
                if (c->type == CELL_BELT && c->item != 0) {
                    c->progress = (uint16_t)(c->progress + BELT_SPEED);
                    if (c->progress >= BELT_PROGRESS_MAX) {
                        Cell *next = cell_at(w, h, x + DIR_X[c->rot], y + DIR_Y[c->rot]);
                        if (next && next->item == 0 &&
                            (next->type == CELL_BELT || next->type == CELL_ROUTER)) {
                            next->item = c->item;
                            next->progress = (uint16_t)(c->progress - BELT_PROGRESS_MAX);
                            next->count = 1;
                            c->item = 0;
                            c->progress = 0;
                            c->count = 0;
                        } else if (next && next->type == CELL_CORE) {
                            c->item = 0;
                            c->progress = 0;
                            c->count = 0;
                            result.delivered++;
                        } else {
                            c->progress = BELT_PROGRESS_MAX - 1u;
                        }
                    }
                } else if (c->type == CELL_ROUTER && c->item != 0) {
                    uint32_t attempt;
                    uint32_t start = c->state % 3u;
                    for (attempt = 0; attempt < 3u; attempt++) {
                        uint32_t dir = (start + attempt) % 3u;
                        Cell *next;
                        if (dir == 0u) next = cell_at(w, h, x + 1, y);
                        else if (dir == 1u) next = cell_at(w, h, x, y + 1);
                        else next = cell_at(w, h, x, y - 1);
                        if (next && next->item == 0 &&
                            (next->type == CELL_BELT || next->type == CELL_ROUTER)) {
                            next->item = c->item;
                            next->progress = 0;
                            next->count = 1;
                            c->item = 0;
                            c->count = 0;
                            c->state++;
                            break;
                        } else if (next && next->type == CELL_CORE) {
                            c->item = 0;
                            c->count = 0;
                            c->state++;
                            result.delivered++;
                            break;
                        }
                    }
                }
            }
        }

        /* Turrets pick the nearest living enemy; ties go to the lower index. */
        for (y = 0; y < (int32_t)h; y++) {
            for (x = 0; x < (int32_t)w; x++) {
                Cell *c = cell_at(w, h, x, y);
                if (c->type != CELL_TURRET) continue;
                if (c->cooldown > 0u) {
                    c->cooldown--;
                    continue;
                }
                {
                    int32_t best = -1;
                    int32_t best_d2 = (TURRET_RANGE_TILES * 16) * (TURRET_RANGE_TILES * 16);
                    uint32_t e;
                    for (e = 0; e < MAX_ENEMIES; e++) {
                        int32_t dx;
                        int32_t dy;
                        int32_t d2;
                        if (!g_enemies[e].alive) continue;
                        dx = g_enemies[e].x - x * 16;
                        dy = g_enemies[e].y - y * 16;
                        d2 = dx * dx + dy * dy;
                        if (d2 < best_d2 || (d2 == best_d2 && best < 0)) {
                            best_d2 = d2;
                            best = (int32_t)e;
                        }
                    }
                    if (best >= 0) {
                        g_enemies[best].hp -= TURRET_DAMAGE;
                        c->cooldown = TURRET_COOLDOWN;
                        c->state++;
                        if (g_enemies[best].hp <= 0) {
                            g_enemies[best].alive = 0;
                            g_enemies[best].hp = 0;
                            result.killed++;
                        }
                    }
                }
            }
        }

        /* Spawns and enemy movement toward the core. */
        if ((tick % 32u) == 0u && result.spawned < 1000000u) {
            uint32_t slot;
            for (slot = 0; slot < MAX_ENEMIES; slot++) {
                if (g_enemies[slot].alive) continue;
                g_enemies[slot].x = (int32_t)(rng_next(&rng) % (w * 16u));
                g_enemies[slot].y = 0;
                g_enemies[slot].hp = ENEMY_HP;
                g_enemies[slot].rng = rng_next(&rng);
                g_enemies[slot].alive = 1;
                result.spawned++;
                break;
            }
        }

        for (i = 0; i < MAX_ENEMIES; i++) {
            int32_t dx;
            int32_t dy;
            if (!g_enemies[i].alive) continue;
            dx = (int32_t)core_x - g_enemies[i].x;
            dy = (int32_t)core_y - g_enemies[i].y;
            if (dx > ENEMY_SPEED) g_enemies[i].x += ENEMY_SPEED;
            else if (dx < -ENEMY_SPEED) g_enemies[i].x -= ENEMY_SPEED;
            if (dy > ENEMY_SPEED) g_enemies[i].y += ENEMY_SPEED;
            else if (dy < -ENEMY_SPEED) g_enemies[i].y -= ENEMY_SPEED;
            if (g_enemies[i].x > (int32_t)(core_x) - CORE_RADIUS &&
                g_enemies[i].x < (int32_t)(core_x) + CORE_RADIUS &&
                g_enemies[i].y > (int32_t)(core_y) - CORE_RADIUS &&
                g_enemies[i].y < (int32_t)(core_y) + CORE_RADIUS) {
                g_enemies[i].alive = 0;
                result.leaked++;
            }
            /* Keep coordinates bounded even if the core is reached diagonally. */
            g_enemies[i].x = clamp_i32(g_enemies[i].x, 0, (int32_t)(w * 16u) - 1);
            g_enemies[i].y = clamp_i32(g_enemies[i].y, 0, (int32_t)(h * 16u) - 1);
        }

        if (hash_each_tick) {
            mix(&trace, hash_state(w, h, result.delivered, result.spawned,
                                   result.killed, result.leaked));
        }
    }

    result.hash = hash_each_tick
        ? trace
        : hash_state(w, h, result.delivered, result.spawned, result.killed, result.leaked);
    return result;
}

#ifndef SIM_FREESTANDING

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static double now_ms(void) {
    return (double)clock() * 1000.0 / (double)CLOCKS_PER_SEC;
}

static uint32_t parse_u32(const char *text) {
    char *end = 0;
    unsigned long value = strtoul(text, &end, 10);
    if (end == text) return 0;
    return (uint32_t)value;
}

int main(int argc, char **argv) {
    uint32_t w = 96;
    uint32_t h = 96;
    uint32_t ticks = 2000;
    uint32_t seed = 1;
    int hash_each_tick = 1;
    SimResult result;
    double start;
    double elapsed;
    int i;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--ticks") == 0 && i + 1 < argc) {
            ticks = parse_u32(argv[++i]);
        } else if (strcmp(argv[i], "--seed") == 0 && i + 1 < argc) {
            seed = parse_u32(argv[++i]);
        } else if (strcmp(argv[i], "--map") == 0 && i + 1 < argc) {
            const char *value = argv[++i];
            const char *x = strchr(value, 'x');
            if (x) {
                w = parse_u32(value);
                h = parse_u32(x + 1);
            }
        } else if (strcmp(argv[i], "--nohash") == 0) {
            hash_each_tick = 0;
        } else {
            fprintf(stderr,
                    "usage: %s [--map WxH] [--ticks N] [--seed S] [--nohash]\n",
                    argv[0]);
            return 2;
        }
    }

    start = now_ms();
    result = sim_run(w, h, ticks, seed, hash_each_tick);
    elapsed = now_ms() - start;

    printf("map=%ux%u ticks=%u seed=%u cell_bytes=%u\n", result.map_w,
           result.map_h, result.ticks, seed, result.cell_bytes);
    printf("trace_hash=0x%08x\n", result.hash);
    printf("items_delivered=%u enemies_spawned=%u enemies_killed=%u leaks=%u\n",
           result.delivered, result.spawned, result.killed, result.leaked);
    printf("sim_ms=%.1f ticks_per_sec=%.0f\n", elapsed,
           elapsed > 0.0 ? (double)result.ticks * 1000.0 / elapsed : 0.0);
    printf("static_kernel_bytes=%u (cells) + %u (enemies)\n",
           (unsigned)(sizeof(g_cells)), (unsigned)(sizeof(g_enemies)));
    return 0;
}

#endif /* SIM_FREESTANDING */
