#include <assert.h>
#include <stdio.h>
#include <string.h>

#include "sim/parity_scenario.h"

static void dump_result_json(const M3dsParityRunResult *res) {
    printf("{\n");
    printf("  \"scenario_id\": \"%s\",\n", res->scenario_id);
    printf("  \"total_ticks\": %lu,\n", (unsigned long)res->total_ticks);
    printf("  \"trace_hash\": \"0x%08lx\",\n", (unsigned long)res->trace_hash);
    printf("  \"checkpoints\": [\n");
    for (uint32_t i = 0u; i < res->checkpoint_count; i++) {
        const M3dsParitySnapshot *s = &res->checkpoints[i];
        printf(
            "    {\"tick\": %lu, \"core_copper\": %lu, \"core_hp\": %lu, "
            "\"items_mined\": %lu, \"items_delivered\": %lu, \"shots_fired\": %lu, "
            "\"enemies_spawned\": %lu, \"enemies_killed\": %lu, \"enemies_leaked\": %lu, "
            "\"active_enemies\": %lu, \"blocks_placed\": %lu, \"inputs_applied\": %lu, "
            "\"inputs_rejected\": %lu, \"state_hash\": \"0x%08lx\"}%s\n",
            (unsigned long)s->tick,
            (unsigned long)s->core_copper,
            (unsigned long)s->core_hp,
            (unsigned long)s->items_mined,
            (unsigned long)s->items_delivered,
            (unsigned long)s->shots_fired,
            (unsigned long)s->enemies_spawned,
            (unsigned long)s->enemies_killed,
            (unsigned long)s->enemies_leaked,
            (unsigned long)s->active_enemies,
            (unsigned long)s->blocks_placed,
            (unsigned long)s->inputs_applied,
            (unsigned long)s->inputs_rejected,
            (unsigned long)s->state_hash,
            (i + 1u < res->checkpoint_count) ? "," : "");
    }
    printf("  ]\n");
    printf("}\n");
}

static void test_input_guards_and_pause(void) {
    M3dsParityState st;
    m3ds_parity_init(&st);

    /* Out-of-bounds rejected */
    M3dsParityInput bad_oob = {0u, M3DS_PINPUT_PLACE_BLOCK, 99u, 0u, M3DS_PBLOCK_WALL, 0u};
    assert(!m3ds_parity_apply_input(&st, &bad_oob));
    assert(st.inputs_rejected == 1u);

    /* Drill off ore rejected */
    M3dsParityInput bad_drill = {0u, M3DS_PINPUT_PLACE_BLOCK, 0u, 0u, M3DS_PBLOCK_DRILL, 0u};
    assert(!m3ds_parity_apply_input(&st, &bad_drill));
    assert(st.inputs_rejected == 2u);

    /* Core cannot be removed */
    M3dsParityInput bad_core = {0u, M3DS_PINPUT_REMOVE_BLOCK, st.core_x, st.core_y, 0u, 0u};
    assert(!m3ds_parity_apply_input(&st, &bad_core));
    assert(st.inputs_rejected == 3u);

    /* Wall placement + removal refunds 50% (cost 2 -> refund 1) */
    uint32_t before = st.core_copper;
    M3dsParityInput place_wall = {0u, M3DS_PINPUT_PLACE_BLOCK, 1u, 1u, M3DS_PBLOCK_WALL, 0u};
    assert(m3ds_parity_apply_input(&st, &place_wall));
    assert(st.core_copper == before - 2u);
    M3dsParityInput rm_wall = {0u, M3DS_PINPUT_REMOVE_BLOCK, 1u, 1u, 0u, 0u};
    assert(m3ds_parity_apply_input(&st, &rm_wall));
    assert(st.core_copper == before - 1u);

    /* Pause freezes world state except tick & pause toggle */
    M3dsParityInput pause_on = {0u, M3DS_PINPUT_SET_PAUSED, 0u, 0u, 0u, 1u};
    m3ds_parity_step(&st, &pause_on, 1u);
    assert(st.paused == 1u);
    uint32_t copper_before = st.core_copper;
    for (int i = 0; i < 10; i++) {
        m3ds_parity_step(&st, NULL, 0u);
    }
    assert(st.core_copper == copper_before);
    assert(st.items_mined == 0u);
}

static void test_canonical_scenario_determinism(void) {
    M3dsParityRunResult r1;
    M3dsParityRunResult r2;
    m3ds_parity_run_canonical(&r1);
    m3ds_parity_run_canonical(&r2);

    assert(strcmp(r1.scenario_id, "starter_copper_defense_v1") == 0);
    assert(r1.total_ticks == 240u);
    assert(r1.checkpoint_count == 5u);
    assert(r1.trace_hash == r2.trace_hash);
    assert(memcmp(&r1, &r2, sizeof(r1)) == 0);

    const M3dsParitySnapshot *final_snap = &r1.checkpoints[r1.checkpoint_count - 1u];
    assert(final_snap->tick == 240u);
    assert(final_snap->items_mined > 0u);
    assert(final_snap->items_delivered > 0u);
    assert(final_snap->shots_fired > 0u);
    assert(final_snap->enemies_spawned == 4u);
    assert(final_snap->enemies_killed > 0u);
    assert(final_snap->inputs_rejected == 0u);
    /* Pause window [140..160] leaves snapshots across repeated steps strictly deterministic */
    assert(r1.checkpoints[2].tick == 120u);
    assert(r1.checkpoints[3].tick == 180u);
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--dump-json") == 0) {
        M3dsParityRunResult res;
        m3ds_parity_run_canonical(&res);
        dump_result_json(&res);
        return 0;
    }
    if (argc != 1) {
        fprintf(stderr, "usage: %s [--dump-json]\n", argv[0]);
        return 2;
    }

    test_input_guards_and_pause();
    test_canonical_scenario_determinism();
    printf("test_parity_harness: all assertions passed.\n");
    return 0;
}
