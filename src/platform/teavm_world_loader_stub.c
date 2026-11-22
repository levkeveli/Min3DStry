#include "platform/teavm_world_loader.h"

#ifndef M3DS_TEAVM_LOADER_EMBEDDED

#if defined(__GNUC__)
#define M3DS_WEAK __attribute__((weak))
#else
#define M3DS_WEAK
#endif

M3DS_WEAK bool m3ds_teavm_world_loader_available(void) {
    return false;
}

M3DS_WEAK bool m3ds_teavm_world_load(
    const char *save_path, M3dsWorldTiles *out) {
    (void)save_path;
    (void)out;
    return false;
}

#endif /* M3DS_TEAVM_LOADER_EMBEDDED */
