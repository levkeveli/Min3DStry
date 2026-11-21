#include "m3ds_world_fragment.h"

#include <assert.h>
#include <math.h>
#include <stdbool.h>

static bool near(float a, float b) {
    return fabsf(a - b) < 0.0001f;
}

static void test_positive_fragments(void) {
    const float pivot = 100.0f;
    const float step = 2.0f;
    assert(near(m3ds_world_fragment_center(pivot, 0, 0, step), 100.0f));
    assert(near(m3ds_world_fragment_center(pivot, 128, 0, step), -156.0f));
    assert(near(m3ds_world_fragment_center(pivot, 256, 0, step), -412.0f));
    // top-left = pivot - center must follow the source offset exactly.
    assert(near(pivot - m3ds_world_fragment_center(pivot, 128, 0, step), 256.0f));
    assert(near(pivot - m3ds_world_fragment_center(pivot, 256, 0, step), 512.0f));
}

static void test_mirrored_fragments(void) {
    const float pivot = 100.0f;
    const float step = -2.0f;
    assert(near(m3ds_world_fragment_center(pivot, 0, 0, step), 100.0f));
    assert(near(m3ds_world_fragment_center(pivot, 128, 0, step), 356.0f));
    assert(near(m3ds_world_fragment_center(pivot, 256, 0, step), 612.0f));
    assert(near(pivot - m3ds_world_fragment_center(pivot, 128, 0, step), -256.0f));
    assert(near(pivot - m3ds_world_fragment_center(pivot, 256, 0, step), -512.0f));

    // Three mirrored fragments stay ordered and never collapse onto 0.
    float first = m3ds_world_fragment_center(pivot, 0, 0, step);
    float second = m3ds_world_fragment_center(pivot, 128, 0, step);
    float third = m3ds_world_fragment_center(pivot, 256, 0, step);
    assert(first < second && second < third);
    assert(near(pivot - first, 0.0f));
    assert(near(pivot - second, -256.0f));
    assert(near(pivot - third, -512.0f));
}

static void test_region_offset_and_axis(void) {
    // Fragment inside a region that does not start at atlas zero.
    assert(near(m3ds_world_fragment_center(50.0f, 96, 32, -1.5f), 146.0f));
    assert(near(50.0f - m3ds_world_fragment_center(50.0f, 96, 32, -1.5f), -96.0f));
    // Vertical fragment uses the same rule.
    assert(near(m3ds_world_fragment_center(10.0f, 128, 64, 0.5f), -22.0f));
    assert(near(10.0f - m3ds_world_fragment_center(10.0f, 128, 64, 0.5f), 32.0f));
    // Unit scale is a plain source offset.
    assert(near(m3ds_world_fragment_center(0.0f, 128, 0, 1.0f), -128.0f));
    assert(near(m3ds_world_fragment_center(0.0f, 128, 0, -1.0f), 128.0f));
}

int main(void) {
    test_positive_fragments();
    test_mirrored_fragments();
    test_region_offset_and_axis();
    return 0;
}
