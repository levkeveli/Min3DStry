#include "ui/touch_controls.h"

#include <assert.h>
#include <string.h>

static void test_centers(void) {
    assert(m3ds_touch_action_at(280.0f, 28.0f) == M3DS_TOUCH_ACTION_MENU);
    assert(m3ds_touch_action_at(280.0f, 84.0f) == M3DS_TOUCH_ACTION_ZOOM_IN);
    assert(m3ds_touch_action_at(280.0f, 140.0f) == M3DS_TOUCH_ACTION_ZOOM_OUT);
    assert(m3ds_touch_action_at(64.0f, 136.0f) == M3DS_TOUCH_ACTION_CAMERA_UP);
    assert(m3ds_touch_action_at(64.0f, 184.0f) == M3DS_TOUCH_ACTION_CAMERA_DOWN);
    assert(m3ds_touch_action_at(32.0f, 160.0f) == M3DS_TOUCH_ACTION_CAMERA_LEFT);
    assert(m3ds_touch_action_at(96.0f, 160.0f) == M3DS_TOUCH_ACTION_CAMERA_RIGHT);
}

static void test_region_boundaries(void) {
    assert(m3ds_touch_action_at(247.9f, 8.0f) == M3DS_TOUCH_ACTION_NONE);
    assert(m3ds_touch_action_at(248.0f, 8.0f) == M3DS_TOUCH_ACTION_MENU);
    assert(m3ds_touch_action_at(312.0f, 28.0f) == M3DS_TOUCH_ACTION_NONE);
    assert(m3ds_touch_action_at(280.0f, 48.0f) == M3DS_TOUCH_ACTION_NONE);

    assert(m3ds_touch_action_at(48.0f, 120.0f) == M3DS_TOUCH_ACTION_CAMERA_UP);
    assert(m3ds_touch_action_at(47.9f, 120.0f) == M3DS_TOUCH_ACTION_NONE);
    assert(m3ds_touch_action_at(80.0f, 120.0f) == M3DS_TOUCH_ACTION_NONE);
    assert(m3ds_touch_action_at(64.0f, 152.0f) == M3DS_TOUCH_ACTION_NONE);

    assert(m3ds_touch_action_at(16.0f, 140.0f) == M3DS_TOUCH_ACTION_CAMERA_LEFT);
    assert(m3ds_touch_action_at(48.0f, 160.0f) == M3DS_TOUCH_ACTION_NONE);
    assert(m3ds_touch_action_at(80.0f, 140.0f) == M3DS_TOUCH_ACTION_CAMERA_RIGHT);
    assert(m3ds_touch_action_at(112.0f, 160.0f) == M3DS_TOUCH_ACTION_NONE);
    assert(m3ds_touch_action_at(64.0f, 168.0f) == M3DS_TOUCH_ACTION_CAMERA_DOWN);
    assert(m3ds_touch_action_at(64.0f, 200.0f) == M3DS_TOUCH_ACTION_NONE);
}

static void test_menu_rows(void) {
    assert(m3ds_touch_menu_row(119.9f, 3u) == -1);
    assert(m3ds_touch_menu_row(120.0f, 3u) == 0);
    assert(m3ds_touch_menu_row(127.9f, 3u) == 0);
    assert(m3ds_touch_menu_row(128.0f, 3u) == 1);
    assert(m3ds_touch_menu_row(135.9f, 3u) == 1);
    assert(m3ds_touch_menu_row(136.0f, 3u) == 2);
    assert(m3ds_touch_menu_row(143.9f, 3u) == 2);
    assert(m3ds_touch_menu_row(144.0f, 3u) == -1);
    assert(m3ds_touch_menu_row(120.0f, 0u) == -1);
    assert(m3ds_touch_menu_row(240.0f, 3u) == -1);
}

static void test_outside_and_ids(void) {
    assert(m3ds_touch_action_at(-1.0f, 10.0f) == M3DS_TOUCH_ACTION_NONE);
    assert(m3ds_touch_action_at(10.0f, -1.0f) == M3DS_TOUCH_ACTION_NONE);
    assert(m3ds_touch_action_at(320.0f, 10.0f) == M3DS_TOUCH_ACTION_NONE);
    assert(m3ds_touch_action_at(10.0f, 240.0f) == M3DS_TOUCH_ACTION_NONE);
    assert(m3ds_touch_action_at(0.0f, 0.0f) == M3DS_TOUCH_ACTION_NONE);

    assert(strcmp(m3ds_touch_action_id(M3DS_TOUCH_ACTION_CAMERA_UP), "camera.up") == 0);
    assert(strcmp(m3ds_touch_action_id(M3DS_TOUCH_ACTION_ZOOM_OUT), "zoom.out") == 0);
    assert(strcmp(m3ds_touch_action_id(M3DS_TOUCH_ACTION_MENU), "menu") == 0);
    assert(strcmp(m3ds_touch_action_id(M3DS_TOUCH_ACTION_NONE), "none") == 0);
    assert(strcmp(m3ds_touch_action_id(M3DS_TOUCH_ACTION_COUNT), "none") == 0);

    assert(m3ds_touch_action_is_pan(M3DS_TOUCH_ACTION_CAMERA_LEFT));
    assert(m3ds_touch_action_is_pan(M3DS_TOUCH_ACTION_CAMERA_DOWN));
    assert(!m3ds_touch_action_is_pan(M3DS_TOUCH_ACTION_ZOOM_IN));
    assert(!m3ds_touch_action_is_pan(M3DS_TOUCH_ACTION_MENU));
    assert(!m3ds_touch_action_is_pan(M3DS_TOUCH_ACTION_NONE));
}

static void test_labels_inside_hit_zones(void) {
    size_t count = 0u;
    const M3dsTouchLabel *labels = m3ds_touch_labels(&count);
    assert(labels != NULL);
    assert(count == 7u);
    for (size_t i = 0u; i < count; ++i) {
        assert(labels[i].text != NULL);
        assert(labels[i].text[0] != '\0');
        float x = ((float)labels[i].col - 1.0f) * M3DS_TOUCH_TEXT_CELL
                + M3DS_TOUCH_TEXT_CELL / 2.0f;
        float y = ((float)labels[i].row - 1.0f) * M3DS_TOUCH_TEXT_CELL
                + M3DS_TOUCH_TEXT_CELL / 2.0f;
        assert(m3ds_touch_action_at(x, y) == labels[i].action);
    }
    assert(m3ds_touch_labels(NULL) == labels);
}

int main(void) {
    test_centers();
    test_region_boundaries();
    test_menu_rows();
    test_outside_and_ids();
    test_labels_inside_hit_zones();
    return 0;
}
