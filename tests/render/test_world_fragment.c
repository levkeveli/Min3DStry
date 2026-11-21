#include "m3ds_world_fragment.h"

#include <assert.h>
#include <math.h>

static void check_fragments(float step, const float expected[3]) {
    const int cuts[] = {32, 128, 256, 280};
    const float center = 100.0f;
    for (int i = 0; i < 3; i++) {
        const float fragment_center = m3ds_world_fragment_center(
            center, cuts[i], cuts[i + 1], cuts[0], cuts[3], step);
        // Citro2D's top-left calculation subtracts the pivot from the position.
        assert(fabsf(center - fragment_center - expected[i]) < 0.0001f);
    }
}

int main(void) {
    const float forward[] = {0.0f, 48.0f, 112.0f};
    const float mirrored[] = {76.0f, 12.0f, 0.0f};
    check_fragments(0.5f, forward);
    check_fragments(-0.5f, mirrored);
    return 0;
}
