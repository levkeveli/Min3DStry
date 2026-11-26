#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Poll 3DS HID buttons, Circle Pad / D-pad movement axes, and touch press.
 * Returns -1 when APT requests exit or KEY_START is pressed; otherwise returns
 * packed bits matching m3ds_live_net_input for bits 0..19 plus bit 20 (X pause),
 * bit 21 (Y trigger wave), bit 22 (SELECT save .msav), bit 23 (L held at boot),
 * bit 25 (X held to join at boot), bit 26 (C-Stick up zoom in) and bit 27
 * (C-Stick down zoom out), and bit 28 (Y held for the title at boot).
 */
int32_t m3ds_input_poll(void);

/**
 * Return packed touch coordinates (px | (py << 16)) sampled by the most recent
 * m3ds_input_poll() when KEY_TOUCH fired, or -1 if no new touch occurred.
 */
int32_t m3ds_input_touch(void);

#ifdef __cplusplus
}
#endif
