#ifndef MIN3DSTRY_PLATFORM_TEAVM_WORLD_LOADER_H
#define MIN3DSTRY_PLATFORM_TEAVM_WORLD_LOADER_H

#include <stdbool.h>
#include <stdint.h>

#include "platform/msav_world.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Optional in-process upstream SaveIO loader. The weak default implementation
 * reports unavailable; an embedded TeaVM loader overrides these symbols and
 * fills the resident M3dsWorldTiles arrays from the decoded save. Callers must
 * fall back to the verification-only native parser when this returns false.
 */
bool m3ds_teavm_world_loader_available(void);

bool m3ds_teavm_world_load(const char *save_path, M3dsWorldTiles *out);

/* Save version decoded by the most recent successful loader run. */
uint32_t m3ds_teavm_world_loaded_version(void);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_TEAVM_WORLD_LOADER_H */
